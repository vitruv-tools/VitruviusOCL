import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import {
    inlineSummary,
    isSkipNotice,
    parseViolationBlock,
    PRE_POST_SKIPPED_PREFIX,
    SEVERITY_STYLE,
    styleOf,
    violationsOf,
} from '../src/violations';

const BLOCK = [
    '----------------------------------------',
    '[CRITICAL] namesSet',
    'Message : First name must be set',
    'Context : Member',
].join('\n');

test('parseViolationBlock reads severity and message', () => {
    assert.deepEqual(parseViolationBlock(BLOCK), { severity: 'CRITICAL', message: 'First name must be set' });
});

test('parseViolationBlock defaults to WARNING and the first plain line', () => {
    assert.deepEqual(
        parseViolationBlock('------\nsomething is wrong\nmore'),
        { severity: 'WARNING', message: 'something is wrong' });
});

test('parseViolationBlock falls back to the whole block', () => {
    assert.deepEqual(parseViolationBlock('[INFO] x'), { severity: 'INFO', message: '[INFO] x' });
});

test('styleOf shows unknown severities as warnings', () => {
    assert.equal(styleOf('CRITICAL'), SEVERITY_STYLE['CRITICAL']);
    assert.equal(styleOf('FATAL'), SEVERITY_STYLE['WARNING']);
});

test('inlineSummary shows the first violation and how many more', () => {
    assert.deepEqual(inlineSummary('namesSet', [BLOCK, BLOCK, BLOCK]), {
        text: ' ◀ ✖ CRITICAL: First name must be set (+2 more)',
        color: '#e05252',
    });
    assert.deepEqual(inlineSummary('namesSet', [BLOCK]).text, ' ◀ ✖ CRITICAL: First name must be set');
});

test('inlineSummary without details is a generic warning', () => {
    const expected = { text: ' ◀ ⚠ WARNING: namesSet violated', color: '#e0b84a' };
    assert.deepEqual(inlineSummary('namesSet', []), expected);
});

test('isSkipNotice recognizes the Java prefix', () => {
    assert.equal(PRE_POST_SKIPPED_PREFIX, 'PRE/POST SKIPPED:');
    assert.equal(isSkipNotice('PRE/POST SKIPPED: post done'), true);
    assert.equal(isSkipNotice(' PRE/POST SKIPPED: post done'), false);
    assert.equal(isSkipNotice(BLOCK), false);
});

test('violationsOf drops the skip notices', () => {
    assert.deepEqual(violationsOf(['PRE/POST SKIPPED: post done', BLOCK]), [BLOCK]);
    assert.deepEqual(violationsOf(undefined), []);
});
