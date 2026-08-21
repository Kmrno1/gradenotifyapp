import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import { GRADES_URL, runCheck } from '../src/checker.js';

const pageHtml = readFileSync(new URL('fixtures/grades-tables.html', import.meta.url), 'utf8');

/** In-memory stand-in for chrome.storage.local. */
function fakeStore(initial = {}) {
  const data = { ...initial };
  return {
    data,
    get: async (keys) =>
      Object.fromEntries(
        [keys].flat().filter((key) => key in data).map((key) => [key, data[key]]),
      ),
    set: async (values) => Object.assign(data, values),
  };
}

const respondWith = (body, init = {}) => async () => ({
  ok: init.ok ?? true,
  status: init.status ?? 200,
  text: async () => body,
});

function harness(initial, body, init) {
  const store = fakeStore(initial);
  const notified = [];
  return {
    store,
    notified,
    run: () =>
      runCheck({
        fetchImpl: respondWith(body, init),
        store,
        notify: (changes) => notified.push(changes),
        now: () => 1_700_000_000_000,
      }),
  };
}

test('requests the grades page with portal cookies', async () => {
  const calls = [];
  await runCheck({
    fetchImpl: async (url, options) => {
      calls.push([url, options]);
      return { ok: true, status: 200, text: async () => pageHtml };
    },
    store: fakeStore(),
    notify: () => {},
  });
  assert.equal(calls[0][0], GRADES_URL);
  assert.equal(calls[0][1].credentials, 'include');
});

test('first check stores a baseline and notifies nothing', async () => {
  const { store, notified, run } = harness({}, pageHtml);
  const result = await run();

  assert.equal(result.status, 'baseline');
  assert.deepEqual(notified, []);
  assert.equal(Object.keys(store.data.snapshot).length, 24);
});

test('second check notifies only what changed', async () => {
  const { store, notified, run } = harness({}, pageHtml);
  await run();

  const [key] = Object.keys(store.data.snapshot);
  store.data.snapshot[key] = {
    ...store.data.snapshot[key],
    marks: { ...store.data.snapshot[key].marks, SSI: '' },
  };

  const result = await run();
  assert.equal(result.status, 'ok');
  assert.equal(result.changes.length, 1);
  assert.equal(notified.length, 1);
  assert.equal(notified[0][0].label, 'SSI');
});

test('an unchanged portal produces no notification', async () => {
  const { notified, run } = harness({}, pageHtml);
  await run();
  const result = await run();

  assert.equal(result.status, 'ok');
  assert.deepEqual(result.changes, []);
  assert.deepEqual(notified, []);
});

test('recent changes are recorded for the popup', async () => {
  const { store, run } = harness({}, pageHtml);
  await run();
  const [key] = Object.keys(store.data.snapshot);
  store.data.snapshot[key] = {
    ...store.data.snapshot[key],
    marks: { ...store.data.snapshot[key].marks, SSI: '' },
  };
  await run();

  assert.equal(store.data.recentChanges.length, 1);
  assert.equal(store.data.recentChanges[0].at, 1_700_000_000_000);
});

test('an expired session is reported without clearing the snapshot', async () => {
  const { store, notified, run } = harness({}, pageHtml);
  await run();
  const snapshot = store.data.snapshot;

  const loggedOut = await runCheck({
    fetchImpl: respondWith('<form><input type="password"></form>'),
    store,
    notify: (changes) => notified.push(changes),
  });

  assert.equal(loggedOut.status, 'logged-out');
  assert.deepEqual(notified, []);
  assert.equal(store.data.snapshot, snapshot);
});

test('a network failure is reported as an error', async () => {
  const store = fakeStore();
  const result = await runCheck({
    fetchImpl: async () => {
      throw new Error('offline');
    },
    store,
    notify: () => {},
  });

  assert.equal(result.status, 'error');
  assert.equal(result.message, 'offline');
  assert.equal(store.data.status.status, 'error');
});

test('an http error is reported as an error', async () => {
  const { run } = harness({}, '', { ok: false, status: 503 });
  const result = await run();
  assert.equal(result.status, 'error');
  assert.match(result.message, /503/);
});

test('an unreadable grades page does not overwrite the snapshot', async () => {
  const { store, run } = harness({}, pageHtml);
  await run();
  const snapshot = store.data.snapshot;

  const result = await runCheck({
    fetchImpl: respondWith('<html><body>Dərs kodu but no table</body></html>'),
    store,
    notify: () => {},
  });

  assert.equal(result.status, 'error');
  assert.equal(store.data.snapshot, snapshot);
});

test('absence alerts respect the stored setting', async () => {
  const { store, run } = harness({ settings: { notifyAbsences: false } }, pageHtml);
  await run();
  const [key] = Object.keys(store.data.snapshot);
  store.data.snapshot[key] = { ...store.data.snapshot[key], absences: '0' };

  const result = await run();
  assert.deepEqual(result.changes, []);
});
