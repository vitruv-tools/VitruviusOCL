import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import {
    constraintNameAtLine,
    extractConstraint,
    findConstraintHeaders,
    firstContextLine,
    isInAnnotationZone,
} from '../src/units';

const DOCUMENT = [
    '-- constraints',
    'import "families.ecore"',
    'context Member inv namesSet:',
    '  -- not the body',
    '  self.firstName <> null',
    '',
    '  context Family inv hasMembers :',
    '    @severity WARNING',
    '    self.members->notEmpty()',
].join('\n');

test('findConstraintHeaders finds every one-line header with its line', () => {
    assert.deepEqual(findConstraintHeaders(DOCUMENT), [
        { line: 2, name: 'namesSet' },
        { line: 6, name: 'hasMembers' },
    ]);
});

test('findConstraintHeaders misses a header whose inv is on the next line', () => {
    assert.deepEqual(findConstraintHeaders('context Member\ninv namesSet:\n  true'), []);
});

test('findConstraintHeaders ignores pre and post', () => {
    assert.deepEqual(findConstraintHeaders('context Member::age() pre agePositive: true'), []);
});

test('findConstraintHeaders keeps a trailing carriage return out of the name', () => {
    assert.deepEqual(findConstraintHeaders('context A inv a:\r\n  true\r\n'), [{ line: 0, name: 'a' }]);
});

test('constraintNameAtLine names the header on the line, else null', () => {
    assert.equal(constraintNameAtLine(DOCUMENT, 2), 'namesSet');
    assert.equal(constraintNameAtLine(DOCUMENT, 3), null);
    assert.equal(constraintNameAtLine(DOCUMENT, -1), null);
    assert.equal(constraintNameAtLine(DOCUMENT, 99), null);
});

test('firstContextLine is the first line starting with context', () => {
    assert.equal(firstContextLine(DOCUMENT), 2);
    assert.equal(firstContextLine('-- nothing'), -1);
    // any word starting with context counts
    assert.equal(firstContextLine('contextual\ncontext A inv a: true'), 0);
});

test('extractConstraint takes the header up to the next context and drops comments', () => {
    assert.equal(extractConstraint(DOCUMENT, 'namesSet'), 'context Member inv namesSet:\n  self.firstName <> null');
});

test('extractConstraint keeps annotations of the last constraint', () => {
    assert.equal(
        extractConstraint(DOCUMENT, 'hasMembers'),
        'context Family inv hasMembers :\n    @severity WARNING\n    self.members->notEmpty()');
});

test('extractConstraint is null for an unknown constraint', () => {
    assert.equal(extractConstraint(DOCUMENT, 'missing'), null);
});

test('extractConstraint drops the inv line when it is not on the context line', () => {
    assert.equal(extractConstraint('context A\ninv a:\n  true', 'a'), 'context A\n  true');
});

test('extractConstraint restarts at a comment that mentions the invariant', () => {
    assert.equal(
        extractConstraint('context A inv a:\n  true\n-- inv a: old\n  x', 'a'),
        'context A inv a:\n  x');
});

test('extractConstraint uses the name unescaped as a regular expression', () => {
    // the name is not escaped: "a.c" also matches "abc"
    assert.equal(extractConstraint('context A inv abc: true', 'a.c'), 'context A inv abc: true');
});

test('isInAnnotationZone is true directly below a header and below its annotations', () => {
    const lines = ['context A inv a:', '  @severity WARNING', '', '  @message "m"', ''];
    const lineAt = (i: number) => lines[i];
    assert.equal(isInAnnotationZone(lineAt, 1), true);
    assert.equal(isInAnnotationZone(lineAt, 4), true);
});

test('isInAnnotationZone is false once the body has begun, and without a header', () => {
    const lines = ['context A inv a:', '  self.x > 0', ''];
    assert.equal(isInAnnotationZone(i => lines[i], 2), false);
    assert.equal(isInAnnotationZone(i => ['', ''][i], 1), false);
});

test('isInAnnotationZone also accepts pre and post headers', () => {
    const lines = ['context A::f() post done:', ''];
    assert.equal(isInAnnotationZone(i => lines[i], 1), true);
});
