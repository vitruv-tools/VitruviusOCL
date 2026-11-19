import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { planDecorations } from '../src/decorationPlan';
import { ResultStore } from '../src/resultStore';

const VIOLATION = '[CRITICAL] a\nMessage : a is wrong';

const DOCUMENT = [
    'context A inv a:',
    '  true',
    'context B inv b: true',
    'context C inv c:',
].join('\n');

test('every header is marked as not run without results', () => {
    assert.deepEqual(planDecorations(DOCUMENT, new ResultStore()), {
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
    const plan = planDecorations(DOCUMENT, results);
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
    assert.deepEqual(planDecorations(DOCUMENT, results).inline, [{
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
    const plan = planDecorations(edited, results);
    assert.deepEqual(plan.failed, [4]);
    assert.equal(plan.inline[0].line, 4);
    assert.equal(plan.inline[0].text, ' ◀ ✖ CRITICAL: a is wrong');
});

test('the note of a CRLF document stands at the end of the header, before the carriage return', () => {
    const results = new ResultStore();
    results.record('a', false);
    const plan = planDecorations('context A inv a:\r\n  true\r\n', results);
    assert.equal(plan.inline[0].column, 'context A inv a:'.length);
});

test('two headers with the same name share the result', () => {
    const results = new ResultStore();
    results.record('a', true);
    assert.deepEqual(planDecorations('context A inv a: true\ncontext B inv a: true', results).passed, [0, 1]);
});
