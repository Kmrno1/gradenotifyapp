/** Popup UI: shows poll status, recent changes and the current semester. */

import { DEFAULT_SETTINGS } from './checker.js';
import { describeChange } from './diff.js';

const statusEl = document.getElementById('status');
const changesEl = document.getElementById('changes');
const gradesEl = document.getElementById('grades');
const checkButton = document.getElementById('check-now');
const intervalEl = document.getElementById('interval');
const absencesEl = document.getElementById('notify-absences');
const enabledEl = document.getElementById('enabled');

const STATUS_TEXT = {
  ok: 'Up to date',
  baseline: 'Baseline saved — you will be notified from the next check',
  'logged-out': 'Sign in to my.beu.edu.az, then check again',
  disabled: 'Checks are paused',
};

function formatTime(timestamp) {
  if (!timestamp) return 'never';
  return new Date(timestamp).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function renderStatus(status) {
  if (!status) {
    statusEl.textContent = 'No check yet.';
    return;
  }
  const label = STATUS_TEXT[status.status] ?? status.message ?? 'Unknown state';
  statusEl.textContent = `${label} · last checked ${formatTime(status.at)}`;
  statusEl.classList.toggle('error', status.status === 'error');
}

function renderChanges(changes) {
  changesEl.replaceChildren();
  if (!changes || changes.length === 0) {
    const empty = document.createElement('li');
    empty.className = 'empty';
    empty.textContent = 'Nothing yet.';
    changesEl.append(empty);
    return;
  }
  for (const change of changes.slice(0, 8)) {
    const item = document.createElement('li');
    const text = document.createElement('div');
    text.textContent = describeChange(change);
    const course = document.createElement('div');
    course.className = 'course';
    course.textContent = `${change.name} · ${formatTime(change.at)}`;
    item.append(text, course);
    changesEl.append(item);
  }
}

/** Latest semester present in the snapshot, by the year/term in its label. */
function latestSemester(courses) {
  const rank = (semester) => {
    const [, year, term] = semester.match(/(\d{4})-\d{4}\s+(\d+)/) ?? [];
    return Number(year ?? 0) * 10 + Number(term ?? 0);
  };
  return courses.reduce(
    (best, course) => (rank(course.semester) > rank(best) ? course.semester : best),
    courses[0].semester,
  );
}

function renderGrades(snapshot) {
  gradesEl.replaceChildren();
  const courses = Object.values(snapshot ?? {});
  if (courses.length === 0) return;

  const semester = latestSemester(courses);
  const rows = courses.filter((course) => course.semester === semester);
  // Mark columns vary per semester, so take them from the data itself.
  const labels = [...new Set(rows.flatMap((row) => Object.keys(row.marks)))];

  const head = document.createElement('tr');
  for (const label of ['Course', ...labels]) {
    const cell = document.createElement('th');
    if (label === 'Course') cell.className = 'course-name';
    cell.textContent = label;
    head.append(cell);
  }
  gradesEl.append(head);

  for (const row of rows) {
    const tr = document.createElement('tr');
    const name = document.createElement('td');
    name.className = 'course-name';
    name.textContent = row.code;
    name.title = row.name;
    tr.append(name);
    for (const label of labels) {
      const cell = document.createElement('td');
      cell.textContent = row.marks[label] || '—';
      tr.append(cell);
    }
    gradesEl.append(tr);
  }
}

function renderSettings(settings) {
  intervalEl.value = String(settings.intervalMinutes);
  absencesEl.checked = settings.notifyAbsences;
  enabledEl.checked = settings.enabled;
}

async function refresh() {
  const state = await chrome.storage.local.get([
    'status',
    'recentChanges',
    'snapshot',
    'settings',
  ]);
  renderStatus(state.status);
  renderChanges(state.recentChanges);
  renderGrades(state.snapshot);
  renderSettings({ ...DEFAULT_SETTINGS, ...(state.settings ?? {}) });
}

async function saveSettings() {
  const settings = {
    intervalMinutes: Number(intervalEl.value),
    notifyAbsences: absencesEl.checked,
    enabled: enabledEl.checked,
  };
  await chrome.storage.local.set({ settings });
  await chrome.runtime.sendMessage({ type: 'settings-changed' });
}

checkButton.addEventListener('click', async () => {
  checkButton.disabled = true;
  checkButton.textContent = 'Checking…';
  try {
    await chrome.runtime.sendMessage({ type: 'check-now' });
    await refresh();
  } finally {
    checkButton.disabled = false;
    checkButton.textContent = 'Check now';
  }
});

for (const input of [intervalEl, absencesEl, enabledEl]) {
  input.addEventListener('change', saveSettings);
}

refresh();
