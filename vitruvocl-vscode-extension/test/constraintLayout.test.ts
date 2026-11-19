import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import {
    alignBatchResults,
    ConstraintUnitInfo,
    headerLineMapper,
    layoutFromText,
    layoutFromUnits,
    unitDocumentLine,
} from '../src/constraintLayout';

/**
 * The units the language server returns for this document (see ConstraintUnitsRequestTest):
 *
 * 1 import "spaceMission.ecore"
 * 2 -- comment
 * 3 context spaceMission::Spacecraft
 * 4   inv massPositive:
 * 5     -- inside
 * 6     self.mass > 0
 * 7   pre ready: true
 * 8 context spaceMission::Mission
 */
const UNITS: ConstraintUnitInfo[] = [
    {
        kind: 'INV', name: 'massPositive', contextType: 'spaceMission::Spacecraft',
        text: 'context spaceMission::Spacecraft\ninv massPositive:\n    self.mass > 0',
        textLines: [3, 4, 6], line: 4, column: 2,
    },
    {
        kind: 'PRE', name: 'ready', contextType: 'spaceMission::Spacecraft',
        text: 'context spaceMission::Spacecraft\npre ready: true',
        textLines: [3, 7], line: 7, column: 2,
    },
    {
        kind: 'EMPTY_CONTEXT', name: null, contextType: 'spaceMission::Mission',
        text: 'context spaceMission::Mission',
        textLines: [8], line: 8, column: 0,
    },
];

test('the units give a header for every named invariant, also below its context line', () => {
    const layout = layoutFromUnits(UNITS);
    assert.deepEqual(layout.headers, [{ line: 3, name: 'massPositive' }]);
    assert.equal(layout.runAllLine, 2);
});

test('the units give the text the CLI evaluates', () => {
    const layout = layoutFromUnits(UNITS);
    assert.equal(layout.textOf('massPositive'), UNITS[0].text);
    assert.equal(layout.textOf('ready'), null); // pre/post cannot be run on their own
    assert.equal(layout.textOf('missing'), null);
});

test('error lines of a unit map to the document, skipping the comment', () => {
    const layout = layoutFromUnits(UNITS);
    assert.equal(layout.documentLine('massPositive', 1), 2);
    assert.equal(layout.documentLine('massPositive', 3), 5);
    // unknown or out-of-range lines go to the keyword line
    assert.equal(layout.documentLine('massPositive', 0), 3);
    assert.equal(layout.documentLine('massPositive', 9), 3);
    // a constraint without unit keeps the old mapping
    assert.equal(layout.documentLine('missing', 4), 3);
});

test('an unnamed invariant, whose name the server leaves out, gets no header', () => {
    const unnamed: ConstraintUnitInfo = { kind: 'INV', text: 'context A\ninv: true', textLines: [1, 1], line: 1, column: 10 };
    assert.deepEqual(layoutFromUnits([unnamed, ...UNITS]).headers, [{ line: 3, name: 'massPositive' }]);
    assert.equal(alignBatchResults([unnamed], ['unknown'])?.length, 1);
});

test('headerLineMapper puts errors on the header of the invariant, else on their own line', () => {
    const layout = layoutFromUnits(UNITS);
    assert.equal(headerLineMapper(layout, 'massPositive')(9), 3);
    assert.equal(headerLineMapper(layout, 'unknown')(2), 1);
    assert.equal(headerLineMapper(layout, 'unknown')(0), 0);
});

test('a document without units has no run all line', () => {
    assert.equal(layoutFromUnits([]).runAllLine, -1);
});

test('unitDocumentLine falls back to the keyword line', () => {
    assert.equal(unitDocumentLine(UNITS[1], 2), 6);
    assert.equal(unitDocumentLine(UNITS[1], 0), 6);
});

test('without the language server the own scan gives the same layout', () => {
    const text = 'context A inv a:\n  true\ncontext B\n  inv b: true';
    const layout = layoutFromText(text);
    assert.deepEqual(layout.headers, [{ line: 0, name: 'a' }, { line: 3, name: 'b' }]);
    assert.equal(layout.runAllLine, 0);
    assert.equal(layout.textOf('b'), 'context B\ninv b: true');
    assert.equal(layout.documentLine('b', 1), 2);
    assert.equal(layout.documentLine('b', 2), 3);
});

test('batch results align with the units by order and name', () => {
    assert.equal(alignBatchResults(UNITS, ['massPositive', 'ready', 'unknown']), UNITS);
    assert.equal(alignBatchResults(UNITS, ['massPositive', 'ready']), undefined);
    assert.equal(alignBatchResults(UNITS, ['ready', 'massPositive', 'unknown']), undefined);
});
