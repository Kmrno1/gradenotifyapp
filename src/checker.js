/**
 * The poll cycle: fetch the grades page, diff it against the stored snapshot
 * and report what changed.
 *
 * Dependencies are injected so this runs under test without a browser.
 */

import { isLoggedOut, parseGrades } from './parser.js';
import { diffSnapshots, toSnapshot } from './diff.js';

export const GRADES_URL = 'https://my.beu.edu.az/index.php?mod=grades';

/** Kept for the popup's "recent activity" list. */
const MAX_RECENT_CHANGES = 30;

export const DEFAULT_SETTINGS = {
  intervalMinutes: 20,
  notifyAbsences: true,
  enabled: true,
};

/**
 * @typedef {object} CheckResult
 * @property {'ok'|'baseline'|'logged-out'|'error'} status
 * @property {Array<object>} changes
 * @property {Array<object>} courses
 * @property {string} [message]
 */

/**
 * Run one check.
 *
 * The first successful check only records a baseline: without a previous
 * snapshot every existing mark would look new and bury the user in
 * notifications for grades they already know about.
 *
 * @param {object} deps
 * @param {typeof fetch} deps.fetchImpl
 * @param {{get: function, set: function}} deps.store
 * @param {(changes: Array<object>) => Promise<void>|void} deps.notify
 * @param {() => number} [deps.now]
 * @returns {Promise<CheckResult>}
 */
export async function runCheck({ fetchImpl, store, notify, now = Date.now }) {
  const state = await store.get(['snapshot', 'settings', 'recentChanges']);
  const settings = { ...DEFAULT_SETTINGS, ...(state.settings ?? {}) };

  let body;
  try {
    const response = await fetchImpl(GRADES_URL, {
      credentials: 'include',
      redirect: 'follow',
    });
    if (!response.ok) {
      throw new Error(`portal returned HTTP ${response.status}`);
    }
    body = await response.text();
  } catch (error) {
    const result = { status: 'error', changes: [], courses: [], message: error.message };
    await store.set({ status: { at: now(), ...result } });
    return result;
  }

  if (isLoggedOut(body)) {
    const result = {
      status: 'logged-out',
      changes: [],
      courses: [],
      message: 'Not signed in to the portal',
    };
    await store.set({ status: { at: now(), ...result } });
    return result;
  }

  const courses = parseGrades(body);
  if (courses.length === 0) {
    const result = {
      status: 'error',
      changes: [],
      courses: [],
      message: 'Grades page loaded but no courses could be read',
    };
    await store.set({ status: { at: now(), ...result } });
    return result;
  }

  const snapshot = toSnapshot(courses);

  if (!state.snapshot) {
    const result = { status: 'baseline', changes: [], courses };
    await store.set({ snapshot, status: { at: now(), ...result } });
    return result;
  }

  const changes = diffSnapshots(state.snapshot, snapshot, {
    notifyAbsences: settings.notifyAbsences,
  });

  const recentChanges = [
    ...changes.map((change) => ({ ...change, at: now() })),
    ...(state.recentChanges ?? []),
  ].slice(0, MAX_RECENT_CHANGES);

  await store.set({
    snapshot,
    recentChanges,
    status: { at: now(), status: 'ok', changeCount: changes.length },
  });

  if (changes.length > 0) await notify(changes);

  return { status: 'ok', changes, courses };
}
