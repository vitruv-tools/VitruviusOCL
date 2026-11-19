import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { ResultStore } from '../src/resultStore';

test('a constraint without a result has not run', () => {
    const store = new ResultStore();
    assert.equal(store.get('a'), undefined);
    assert.equal(store.stateOf('a'), 'notRun');
    assert.equal(store.hasFailed('a'), false);
});

test('record keeps whether the constraint passed', () => {
    const store = new ResultStore();
    store.record('a', true);
    store.record('b', false);
    assert.deepEqual(store.get('a'), { name: 'a', passed: true, ran: true });
    assert.equal(store.stateOf('a'), 'passed');
    assert.equal(store.stateOf('b'), 'failed');
    assert.equal(store.hasFailed('b'), true);
});

test('clear forgets all results', () => {
    const store = new ResultStore();
    store.record('a', false);
    store.clear();
    assert.equal(store.stateOf('a'), 'notRun');
});
