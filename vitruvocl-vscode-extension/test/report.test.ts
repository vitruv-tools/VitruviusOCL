import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { countSkipNotices, evalReport, summaryReport } from '../src/report';

const SKIP = 'PRE/POST SKIPPED: post done';

test('evalReport of a satisfied constraint', () => {
    assert.deepEqual(evalReport('a', { success: true, satisfied: true, errors: [], warnings: [] }), [
        '=== a ===\n',
        '✅ CONSTRAINT SATISFIED\nAll instances pass.',
        '',
    ]);
});

test('evalReport of a violated constraint lists its violations, then skipped pre/post', () => {
    assert.deepEqual(evalReport('a', { success: true, satisfied: false, errors: [], warnings: ['v1', SKIP] }), [
        '=== a ===\n',
        '❌ CONSTRAINT VIOLATED',
        'v1',
        '',
        'ℹ NOTE: this only ran invariants — the following were not evaluated:',
        '  ' + SKIP,
        '',
    ]);
});

test('evalReport of a constraint that does not compile lists its errors', () => {
    const errors = [{ line: 2, column: 3, message: 'Unknown type', severity: 'ERROR' }];
    assert.deepEqual(evalReport('a', { success: false, satisfied: false, errors, warnings: [] }), [
        '=== a ===\n',
        '❌ COMPILATION ERRORS:',
        '  Line 2: Unknown type',
        '',
    ]);
});

test('evalReport of a failed result without errors or warnings lists none', () => {
    assert.deepEqual(evalReport('a', { success: false, satisfied: false }), [
        '=== a ===\n',
        '❌ COMPILATION ERRORS:',
        '',
    ]);
});

test('summaryReport when all passed', () => {
    const report = summaryReport(1, 0, [{ name: 'a', success: true, satisfied: true, warnings: [SKIP] }]);
    assert.deepEqual(report, {
        lines: [
            '=== All Constraints (1 total) ===\n',
            '✅ ALL PASSED (1/1)\n',
            'Individual Results:',
            '  ✅ a',
            '     ℹ ' + SKIP,
            '',
        ],
        skippedCount: 1,
    });
});

test('summaryReport when some failed shows their violations', () => {
    const report = summaryReport(1, 1, [
        { name: 'a', success: true, satisfied: true, warnings: ['ignored'] },
        { name: 'b', success: true, satisfied: false, warnings: ['v1'] },
    ]);
    assert.deepEqual(report.lines, [
        '=== All Constraints (2 total) ===\n',
        '❌ SOME FAILED (1/2)\n',
        'Passed: 1',
        'Failed: 1\n',
        'Individual Results:',
        '  ✅ a',
        '  ❌ b',
        '     v1',
        '',
    ]);
    assert.equal(report.skippedCount, 0);
});

test('summaryReport of a failed constraint shows its violations before its skip notices', () => {
    const report = summaryReport(0, 1, [{ name: 'b', success: true, satisfied: false, warnings: [SKIP, 'v1'] }]);
    assert.deepEqual(report.lines.slice(-4), ['  ❌ b', '     v1', '     ℹ ' + SKIP, '']);
    assert.equal(report.skippedCount, 1);
});

test('countSkipNotices counts the skip notices', () => {
    assert.equal(countSkipNotices([SKIP, 'v', SKIP]), 2);
    assert.equal(countSkipNotices(undefined), 0);
});
