import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { isInAnnotationZone, stripComment, unitsFromText } from '../src/units';

/** The document of ConstraintUnitsRequestTest on the Java side. */
const DOCUMENT = [
    'import "spaceMission.ecore"',
    '-- comment',
    'context spaceMission::Spacecraft',
    '  inv massPositive:',
    '    -- inside',
    '    self.mass > 0',
    '  pre ready: true',
    'context spaceMission::Mission',
].join('\n');

function headersOf(text: string): Array<[string | null | undefined, number]> {
    return unitsFromText(text).map(unit => [unit.name, unit.line]);
}

test('unitsFromText finds the units the language server finds', () => {
    assert.deepEqual(unitsFromText(DOCUMENT), [
        {
            kind: 'INV', name: 'massPositive',
            text: 'context spaceMission::Spacecraft\ninv massPositive:\n    self.mass > 0',
            textLines: [3, 4, 6], line: 4, column: 2,
        },
        {
            kind: 'PRE', name: 'ready',
            text: 'context spaceMission::Spacecraft\npre ready: true',
            textLines: [3, 7], line: 7, column: 2,
        },
        {
            kind: 'EMPTY_CONTEXT', name: null,
            text: 'context spaceMission::Mission',
            textLines: [8], line: 8, column: 0,
        },
    ]);
});

test('an inv on the context line and on the line below are both found', () => {
    assert.deepEqual(headersOf('context A inv a: true\ncontext B\n  inv b:\n    true'), [['a', 1], ['b', 3]]);
});

test('an inv in a comment or a string is not a block', () => {
    const text = [
        'context A',
        '  -- inv old: false',
        '  inv a: self.name <> "inv b: x" -- inv c:',
        '  -- context B inv d: true',
    ].join('\n');
    const units = unitsFromText(text);
    assert.deepEqual(units.map(unit => unit.name), ['a']);
    assert.equal(units[0].text, 'context A\ninv a: self.name <> "inv b: x"');
});

test('all blocks of an operation context are found, unnamed and parameterized too', () => {
    const text = [
        'context shop::Item::setPrice(p : Integer) : Boolean',
        '  pre positive: p > 0',
        '  post: result = true',
        '  post changed(x): self.price@pre <> self.price',
    ].join('\n');
    assert.deepEqual(unitsFromText(text).map(unit => [unit.kind, unit.name, unit.line]), [
        ['PRE', 'positive', 2],
        ['POST', null, 3],
        ['POST', 'changed', 4],
    ]);
});

test('a word followed by :: is no block name', () => {
    assert.deepEqual(unitsFromText('context A inv a::b').map(unit => [unit.kind, unit.name]), [['INV', null]]);
});

test('a text without context has no units, and CRLF lines are kept apart', () => {
    assert.deepEqual(unitsFromText('-- only a comment\nimport "a.ecore"'), []);
    const units = unitsFromText('context A\r\n  inv a:\r\n    true\r\n');
    assert.equal(units[0].text, 'context A\ninv a:\n    true');
    assert.deepEqual(units[0].textLines, [1, 2, 3]);
});

test('stripComment keeps -- inside strings', () => {
    assert.equal(stripComment('x = "a--b" -- note'), 'x = "a--b" ');
    assert.equal(stripComment('-- all'), '');
    assert.equal(stripComment('"\\"--"'), '"\\"--"');
});

test('isInAnnotationZone is true directly below a header and below its annotations', () => {
    const lines = ['context A inv a:', '  @severity WARNING', '', '  -- note', '  @message "m"', ''];
    const lineAt = (i: number) => lines[i];
    assert.equal(isInAnnotationZone(lineAt, 1), true);
    assert.equal(isInAnnotationZone(lineAt, 5), true);
});

test('isInAnnotationZone is false once the body has begun, and without a header', () => {
    const lines = ['context A inv a:', '  self.x > 0', ''];
    assert.equal(isInAnnotationZone(i => lines[i], 2), false);
    assert.equal(isInAnnotationZone(i => ['', ''][i], 1), false);
});

test('isInAnnotationZone accepts pre, post and unnamed headers, but not commented ones', () => {
    assert.equal(isInAnnotationZone(i => ['context A::f() post done:', ''][i], 1), true);
    assert.equal(isInAnnotationZone(i => ['  inv:', ''][i], 1), true);
    assert.equal(isInAnnotationZone(i => ['  inv x(y):', ''][i], 1), true);
    assert.equal(isInAnnotationZone(i => ['  -- inv a:', ''][i], 1), false);
});

test('every inv starts a block, also one whose header is still being typed', () => {
    const units = unitsFromText('context A\n  inv a: true\n  inv b\n    self.x > 0');
    assert.deepEqual(units.map(unit => [unit.name, unit.line]), [['a', 2], [null, 3]]);
    assert.equal(units[0].text, 'context A\ninv a: true');
});

test('an unbalanced parenthesis does not swallow the following blocks', () => {
    const units = unitsFromText('context A\n  inv a(: true\n  inv b: false');
    assert.deepEqual(units.map(unit => unit.name), ['a', 'b']);
    assert.equal(units[1].text, 'context A\ninv b: false');
});

test('an escaped line break in a string keeps the line count', () => {
    const units = unitsFromText('context A\ninv a: "x\\\ny" = ""\ninv b: true');
    assert.deepEqual(units.map(unit => [unit.name, unit.line]), [['a', 2], ['b', 4]]);
});

test('isInAnnotationZone ignores headers in strings and after @, and takes nested parameters', () => {
    assert.equal(isInAnnotationZone(i => ['  self.n <> "pre:"', ''][i], 1), false);
    assert.equal(isInAnnotationZone(i => ['  self.x@pre : y', ''][i], 1), false);
    assert.equal(isInAnnotationZone(i => ['  inv a(x: Set(Integer)):', ''][i], 1), true);
});
