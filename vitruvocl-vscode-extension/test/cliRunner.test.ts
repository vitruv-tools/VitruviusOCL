import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';
import { cliFailure, deleteTempConstraint, evalArgs, evalBatchArgs, writeTempConstraint } from '../src/cliRunner';

test('cliFailure is the reason the CLI failed as a whole', () => {
    assert.equal(cliFailure({ success: false, error: 'IOException: no such file' }), 'IOException: no such file');
    // a constraint that does not compile is a result, not a failure of the CLI
    assert.equal(cliFailure({ success: false }), undefined);
    assert.equal(cliFailure({ success: true, error: 'ignored' }), undefined);
    assert.equal(cliFailure({ success: false, error: 42 }), undefined);
});

test('evalBatchArgs runs eval-batch on the document', () => {
    assert.deepEqual(evalBatchArgs('c.jar', 'doc.ocl', ['a.ecore', 'b.ecore'], ['x.xmi', 'v.correspondence']), [
        '-jar', 'c.jar', 'eval-batch', 'doc.ocl',
        '--ecore', 'a.ecore,b.ecore',
        '--xmi', 'x.xmi,v.correspondence',
    ]);
});

test('evalArgs runs eval on the file of one constraint', () => {
    assert.deepEqual(evalArgs('c.jar', 'one.ocl', ['a.ecore'], ['x.xmi']), [
        '-jar', 'c.jar', 'eval', 'one.ocl', '--ecore', 'a.ecore', '--xmi', 'x.xmi',
    ]);
});

test('writeTempConstraint writes the constraint to a new .ocl file', () => {
    const file = writeTempConstraint('context A inv ä: true');
    try {
        assert.match(path.basename(path.dirname(file)), /^vitruvocl-/);
        assert.equal(path.extname(file), '.ocl');
        assert.equal(fs.readFileSync(file, 'utf-8'), 'context A inv ä: true');
    } finally {
        deleteTempConstraint(file);
    }
});

test('deleteTempConstraint removes the file with its directory', () => {
    const file = writeTempConstraint('context A inv a: true');
    deleteTempConstraint(file);
    assert.equal(fs.existsSync(path.dirname(file)), false);
    deleteTempConstraint(file); // deleting twice is no error
});
