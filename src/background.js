/**
 * Service worker: schedules polls and turns detected changes into
 * notifications.
 */

import { DEFAULT_SETTINGS, runCheck } from './checker.js';
import { describeChange } from './diff.js';

const ALARM_NAME = 'beu-grade-poll';
// Root-relative: a bare path would resolve against /src/ and fail to load.
const ICON = '/icons/icon128.png';

/** Above this many changes a single summary reads better than a burst. */
const SUMMARY_THRESHOLD = 4;

const store = {
  get: (keys) => chrome.storage.local.get(keys),
  set: (values) => chrome.storage.local.set(values),
};

async function notify(changes) {
  const marks = changes.filter((change) => change.type === 'mark');
  const headline = marks.length > 0 ? marks : changes;

  if (headline.length > SUMMARY_THRESHOLD) {
    chrome.notifications.create({
      type: 'basic',
      iconUrl: ICON,
      title: `${headline.length} grade updates`,
      message: headline.slice(0, 3).map(describeChange).join('\n'),
      contextMessage: 'Open the extension to see all of them',
    });
    return;
  }

  for (const change of headline) {
    chrome.notifications.create({
      type: 'basic',
      iconUrl: ICON,
      title: change.type === 'absence' ? 'Attendance updated' : 'New grade',
      message: describeChange(change),
      contextMessage: change.name,
    });
  }
}

async function check() {
  const { settings } = await chrome.storage.local.get('settings');
  if (settings && settings.enabled === false) {
    const result = { status: 'disabled', changes: [] };
    await store.set({ status: { at: Date.now(), ...result } });
    return result;
  }
  return runCheck({ fetchImpl: fetch, store, notify });
}

async function scheduleAlarm() {
  const { settings } = await chrome.storage.local.get('settings');
  const { intervalMinutes } = { ...DEFAULT_SETTINGS, ...(settings ?? {}) };
  await chrome.alarms.create(ALARM_NAME, {
    periodInMinutes: intervalMinutes,
    delayInMinutes: 1,
  });
}

chrome.runtime.onInstalled.addListener(async () => {
  const { settings } = await chrome.storage.local.get('settings');
  if (!settings) await chrome.storage.local.set({ settings: DEFAULT_SETTINGS });
  await scheduleAlarm();
});

chrome.runtime.onStartup.addListener(scheduleAlarm);

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === ALARM_NAME) check();
});

// Clicking a notification opens the grades page.
chrome.notifications.onClicked.addListener((id) => {
  chrome.tabs.create({ url: 'https://my.beu.edu.az/index.php?mod=grades' });
  chrome.notifications.clear(id);
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.type === 'check-now') {
    check().then(sendResponse);
    return true; // response is async
  }
  if (message.type === 'settings-changed') {
    scheduleAlarm().then(() => sendResponse({ ok: true }));
    return true;
  }
  return false;
});
