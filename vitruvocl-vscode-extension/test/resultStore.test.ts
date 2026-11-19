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
    assert.deepEqual(store.get('a'), { name: 'a', passed: true, ran: true, violations: [] });
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

test('the violations of a failed constraint are kept until it runs again', () => {
    const store = new ResultStore();
    const violations = ['v1', 'v2'];
    store.record('a', false, violations);
    violations.push('v3');
    assert.deepEqual(store.violationsOf('a'), ['v1', 'v2']);
    store.record('a', false);
    assert.deepEqual(store.violationsOf('a'), []);
});

test('a passed constraint has no violations', () => {
    const store = new ResultStore();
    store.record('a', true, ['ignored']);
    assert.deepEqual(store.violationsOf('a'), []);
    assert.deepEqual(store.violationsOf('unknown'), []);
});

test('forget shows the constraint as not run again', () => {
    const store = new ResultStore();
    store.record('a', false, ['v']);
    store.forget('a');
    assert.equal(store.stateOf('a'), 'notRun');
    assert.deepEqual(store.violationsOf('a'), []);
});
