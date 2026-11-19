import { after, before, test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { collectVsumFiles, containsVsum } from '../src/vsumFiles';

let root: string;

function touch(...segments: string[]): void {
    const file = path.join(root, ...segments);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, '');
}

before(() => {
    root = fs.mkdtempSync(path.join(os.tmpdir(), 'vitruvocl-vsum-'));
    touch('families.xmi');
    touch('models', 'persons.xmi');
    touch('vsum', 'vsum.correspondence');
    touch('vsum', 'uuid.uuid');
    touch('vsum', 'models.models');
    touch('vsum', 'x.marker_vitruv');
    touch('families.ecore');
    touch('Families.GENMODEL');
    touch('target', 'built.xmi');
    touch('src', 'Main.java');
    touch('.git', 'HEAD');
    touch('node_modules', 'm.xmi');
});

after(() => {
    fs.rmSync(root, { recursive: true, force: true });
});

test('collectVsumFiles collects model files recursively and skips metadata', () => {
    const relative = collectVsumFiles(root).map(f => path.relative(root, f).split(path.sep).join('/')).sort();
    assert.deepEqual(relative, ['families.xmi', 'models/persons.xmi', 'vsum/vsum.correspondence']);
});

test('collectVsumFiles is empty for a missing folder or a file', () => {
    assert.deepEqual(collectVsumFiles(path.join(root, 'missing')), []);
    assert.deepEqual(collectVsumFiles(path.join(root, 'families.xmi')), []);
});

test('containsVsum needs a correspondence file', () => {
    assert.equal(containsVsum(['a.xmi', 'vsum/vsum.correspondence']), true);
    assert.equal(containsVsum(['a.xmi']), false);
    assert.equal(containsVsum([]), false);
});
