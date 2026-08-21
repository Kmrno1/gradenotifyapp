import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import { courseKey, isLoggedOut, parseGrades, unwrapResponse } from '../src/parser.js';

const fixture = (name) => readFileSync(new URL(`fixtures/${name}`, import.meta.url), 'utf8');

const pageHtml = fixture('grades-tables.html');
const ajaxJson = fixture('grades-response.json');

test('parses every course row across all semesters', () => {
  const courses = parseGrades(pageHtml);
  assert.equal(courses.length, 24);
  assert.equal(new Set(courses.map((course) => course.semester)).size, 4);
});

test('reads course metadata', () => {
  const course = parseGrades(pageHtml).find((row) => row.code === 'CS 105');
  assert.equal(course.semester, '2024-2025 1. semester');
  assert.equal(course.name, 'Kompüter elmlərinə giriş');
  assert.equal(course.ects, '8');
});

test('maps marks by header label, not by column index', () => {
  const courses = parseGrades(pageHtml);
  // Older semesters carry DVM; from 2025-2026 it is replaced by SEM.
  const older = courses.find((course) => course.code === 'CS 105');
  assert.ok('DVM' in older.marks);
  assert.ok(!('SEM' in older.marks));

  const newer = courses.find((course) => course.code === 'CENG 203');
  assert.ok('SEM' in newer.marks);
  assert.ok(!('DVM' in newer.marks));
});

test('LAB belongs to the semester, and is blank for courses without a lab', () => {
  const courses = parseGrades(pageHtml);
  // LAB appears in the 2025-2026 1st semester header, so every course in that
  // table carries the column even when the course has no lab component.
  assert.notEqual(courses.find((course) => course.code === 'CENG 205').marks.LAB, '');
  assert.equal(courses.find((course) => course.code === 'CENG 203').marks.LAB, '');
});

test('represents an ungraded mark as an empty string', () => {
  const courses = parseGrades(pageHtml);
  // Re-exam columns are blank for anyone who did not sit one.
  assert.equal(courses.every((course) => course.marks['ƏI'] === ''), true);
});

test('unwraps the JSON envelope returned by the semester selector', () => {
  assert.match(unwrapResponse(ajaxJson), /^<div id="divShowStudGrades"/);
  assert.deepEqual(parseGrades(ajaxJson), parseGrades(pageHtml));
});

test('passes through a body that is not JSON', () => {
  assert.equal(unwrapResponse('<table></table>'), '<table></table>');
});

test('returns no courses for unrelated html rather than throwing', () => {
  assert.deepEqual(parseGrades('<html><body>hello</body></html>'), []);
});

test('detects the login page', () => {
  assert.equal(isLoggedOut('<form><input type="password" name="pw"></form>'), true);
  assert.equal(isLoggedOut(pageHtml), false);
});

test('course key distinguishes sections of the same course', () => {
  const base = { semester: '2025-2026 1. semester', code: 'CS 105', section: '01', group: '02' };
  assert.notEqual(courseKey(base), courseKey({ ...base, section: '02' }));
  assert.equal(courseKey(base), courseKey({ ...base }));
});
