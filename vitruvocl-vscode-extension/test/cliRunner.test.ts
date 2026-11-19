import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'fs';
import { evalArgs, evalBatchArgs, writeTempConstraint } from '../src/cliRunner';

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
        assert.match(file, /vitruvocl-\d+\.ocl$/);
        assert.equal(fs.readFileSync(file, 'utf-8'), 'context A inv ä: true');
    } finally {
        fs.unlinkSync(file);
    }
});
