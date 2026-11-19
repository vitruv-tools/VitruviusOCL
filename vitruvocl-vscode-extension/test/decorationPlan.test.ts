import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { layoutFromText } from '../src/constraintLayout';
import { planDecorations } from '../src/decorationPlan';
import { ResultStore } from '../src/resultStore';

/** The plan for the headers the regex finds in the text. */
function planFor(text: string, results: ResultStore) {
    return planDecorations(layoutFromText(text).headers, text.split(/\r?\n/), results);
}

const VIOLATION = '[CRITICAL] a\nMessage : a is wrong';

const DOCUMENT = [
    'context A inv a:',
    '  true',
    'context B inv b: true',
    'context C inv c:',
].join('\n');

test('every header is marked as not run without results', () => {
    assert.deepEqual(planFor(DOCUMENT, new ResultStore()), {
        notRun: [0, 2, 3],
        passed: [],
        failed: [],
        inline: [],
    });
});

test('a failed constraint gets its first violation at the end of its header, all on hover', () => {
    const results = new ResultStore();
    results.record('a', false, [VIOLATION, '[INFO] a\nMessage : second']);
    results.record('b', true);
    const plan = planFor(DOCUMENT, results);
    assert.deepEqual(plan.notRun, [3]);
    assert.deepEqual(plan.passed, [2]);
    assert.deepEqual(plan.failed, [0]);
    assert.deepEqual(plan.inline, [{
        line: 0,
        column: 'context A inv a:'.length,
        text: ' ◀ ✖ CRITICAL: a is wrong (+1 more)',
        color: '#e05252',
        hover: [
            { symbol: '✖', severity: 'CRITICAL', message: 'a is wrong' },
            { symbol: 'ℹ', severity: 'INFO', message: 'second' },
        ],
    }]);
});

test('a failed constraint without violations gets a generic note', () => {
    const results = new ResultStore();
    results.record('c', false);
    assert.deepEqual(planFor(DOCUMENT, results).inline, [{
        line: 3,
        column: 'context C inv c:'.length,
        text: ' ◀ ⚠ WARNING: c violated',
        color: '#e0b84a',
        hover: [{ symbol: '⚠', severity: 'WARNING', message: 'c violated' }],
    }]);
});

test('the marks follow the header when lines are inserted above it, keeping the violations', () => {
    const results = new ResultStore();
    results.record('b', false, [VIOLATION]);
    const edited = '-- new comment\n\n' + DOCUMENT;
    const plan = planFor(edited, results);
    assert.deepEqual(plan.failed, [4]);
    assert.equal(plan.inline[0].line, 4);
    assert.equal(plan.inline[0].text, ' ◀ ✖ CRITICAL: a is wrong');
});

test('the note of a CRLF document stands at the end of the header, before the carriage return', () => {
    const results = new ResultStore();
    results.record('a', false);
    const plan = planFor('context A inv a:\r\n  true\r\n', results);
    assert.equal(plan.inline[0].column, 'context A inv a:'.length);
});

test('two headers with the same name share the result', () => {
    const results = new ResultStore();
    results.record('a', true);
    assert.deepEqual(planFor('context A inv a: true\ncontext B inv a: true', results).passed, [0, 1]);
});
