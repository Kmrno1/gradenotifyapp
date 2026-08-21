/**
 * Comparison of two grade snapshots.
 *
 * A snapshot maps courseKey -> course record, so a poll only has to look up
 * the previous value of each mark.
 */

import { courseKey } from './parser.js';

/**
 * Index a parsed course list by course key.
 * @param {Array<object>} courses
 * @returns {Record<string, object>}
 */
export function toSnapshot(courses) {
  return Object.fromEntries(courses.map((course) => [courseKey(course), course]));
}

/**
 * Compare snapshots and describe what a teacher changed.
 *
 * A mark going from empty to a value is a change like any other: that is the
 * common case of a newly entered grade, so it must not be filtered out.
 *
 * @param {Record<string, object>} previous last stored snapshot
 * @param {Record<string, object>} current freshly parsed snapshot
 * @param {{notifyAbsences?: boolean}} [options]
 * @returns {Array<object>} one entry per changed value
 */
export function diffSnapshots(previous, current, options = {}) {
  const { notifyAbsences = true } = options;
  const changes = [];

  for (const [key, course] of Object.entries(current)) {
    const before = previous[key];
    const isNewCourse = before === undefined;

    for (const [label, value] of Object.entries(course.marks)) {
      const oldValue = isNewCourse ? '' : (before.marks[label] ?? '');
      if (oldValue === value) continue;
      // A course appearing with no marks yet is registration, not grading.
      if (isNewCourse && !value) continue;

      changes.push({
        type: 'mark',
        key,
        semester: course.semester,
        code: course.code,
        name: course.name,
        label,
        from: oldValue,
        to: value,
      });
    }

    if (notifyAbsences && !isNewCourse && course.absences !== before.absences) {
      changes.push({
        type: 'absence',
        key,
        semester: course.semester,
        code: course.code,
        name: course.name,
        label: 'Qaib',
        from: before.absences ?? '',
        to: course.absences ?? '',
      });
    }
  }

  return changes;
}

/**
 * Human readable one-liner for a change, used as notification text.
 * @param {object} change
 */
export function describeChange(change) {
  if (change.type === 'absence') {
    return `${change.code} — absences ${change.from || '0'} → ${change.to || '0'}`;
  }
  if (!change.from) {
    return `${change.code} — ${change.label}: ${change.to}`;
  }
  return `${change.code} — ${change.label}: ${change.from} → ${change.to}`;
}
