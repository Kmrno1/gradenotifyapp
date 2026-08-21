import assert from 'node:assert/strict';
import { test } from 'node:test';

import { describeChange, diffSnapshots, toSnapshot } from '../src/diff.js';

const course = (marks, absences = '4') => ({
  semester: '2025-2026 1. semester',
  code: 'CENG 203',
  section: '01',
  group: '02',
  name: 'Kompüterlərin arxitekturası',
  absences,
  marks,
});

const snapshotOf = (marks, absences) => toSnapshot([course(marks, absences)]);

test('reports a newly entered grade', () => {
  const changes = diffSnapshots(snapshotOf({ SSI: '' }), snapshotOf({ SSI: '47' }));
  assert.equal(changes.length, 1);
  assert.deepEqual(
    { label: changes[0].label, from: changes[0].from, to: changes[0].to },
    { label: 'SSI', from: '', to: '47' },
  );
});

test('reports a corrected grade', () => {
  const changes = diffSnapshots(snapshotOf({ SSI: '44' }), snapshotOf({ SSI: '47' }));
  assert.equal(changes.length, 1);
  assert.equal(changes[0].from, '44');
});

test('reports a withdrawn grade', () => {
  const changes = diffSnapshots(snapshotOf({ SSI: '44' }), snapshotOf({ SSI: '' }));
  assert.equal(changes.length, 1);
  assert.equal(changes[0].to, '');
});

test('stays silent when nothing changed', () => {
  const snapshot = snapshotOf({ SDF1: '9', SSI: '32' });
  assert.deepEqual(diffSnapshots(snapshot, snapshot), []);
});

test('reports each changed mark separately', () => {
  const changes = diffSnapshots(
    snapshotOf({ SDF1: '9', SDF2: '', SSI: '' }),
    snapshotOf({ SDF1: '9', SDF2: '8', SSI: '32' }),
  );
  assert.deepEqual(changes.map((change) => change.label), ['SDF2', 'SSI']);
});

test('a newly registered course with no marks is not an update', () => {
  const changes = diffSnapshots({}, snapshotOf({ SDF1: '', SSI: '' }));
  assert.deepEqual(changes, []);
});

test('a course that appears already graded is reported', () => {
  const changes = diffSnapshots({}, snapshotOf({ SDF1: '10', SSI: '' }));
  assert.deepEqual(changes.map((change) => change.label), ['SDF1']);
});

test('a mark column added mid-semester is reported once it has a value', () => {
  const changes = diffSnapshots(snapshotOf({ SDF1: '9' }), snapshotOf({ SDF1: '9', LAB: '10' }));
  assert.deepEqual(changes.map((change) => change.label), ['LAB']);
});

test('reports rising absences when enabled', () => {
  const changes = diffSnapshots(snapshotOf({}, '4'), snapshotOf({}, '6'));
  assert.equal(changes.length, 1);
  assert.equal(changes[0].type, 'absence');
});

test('absence alerts can be switched off', () => {
  const changes = diffSnapshots(snapshotOf({}, '4'), snapshotOf({}, '6'), {
    notifyAbsences: false,
  });
  assert.deepEqual(changes, []);
});

test('describes changes for notification text', () => {
  assert.equal(
    describeChange({ type: 'mark', code: 'CS 105', label: 'SSI', from: '', to: '47' }),
    'CS 105 — SSI: 47',
  );
  assert.equal(
    describeChange({ type: 'mark', code: 'CS 105', label: 'SSI', from: '44', to: '47' }),
    'CS 105 — SSI: 44 → 47',
  );
  assert.equal(
    describeChange({ type: 'absence', code: 'CS 105', from: '4', to: '6' }),
    'CS 105 — absences 4 → 6',
  );
});
