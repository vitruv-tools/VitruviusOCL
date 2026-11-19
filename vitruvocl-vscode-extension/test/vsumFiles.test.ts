import { after, before, test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
    bestVsumCandidates,
    collectVsumFiles,
    containsVsum,
    isModelFile,
    metamodelNamesIn,
    VsumCandidate,
    vsumRootOf,
} from '../src/vsumFiles';

const XMI = '<?xml version="1.0" encoding="UTF-8"?>\n<xmi:XMI/>';

let root: string;

function touch(content: string, ...segments: string[]): void {
    const file = path.join(root, ...segments);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content);
}

before(() => {
    root = fs.mkdtempSync(path.join(os.tmpdir(), 'vitruvocl-vsum-'));
    touch(XMI, 'families.xmi');
    touch('﻿  \n<persons/>', 'models', 'persons.xmi');
    touch(XMI, 'vsum', 'vsum.correspondence');
    touch(XMI, 'vsum', 'uuid.uuid');
    touch(XMI, 'vsum', 'models.models');
    touch(XMI, 'vsum', 'x.marker_vitruv');
    touch('Apache License\nVersion 2.0', 'LICENSE');
    touch('a;b\n1;2', 'statistics.csv');
    touch('{"pddc": true}', 'cwa-original.pddc');
    touch('', 'empty.xmi');
    touch(XMI, 'pom.xml');
    touch(XMI, '.project');
    touch(XMI, 'families.ecore');
    touch(XMI, 'Families.GENMODEL');
    touch(XMI, 'target', 'built.xmi');
    touch('class Main {}', 'src', 'Main.java');
    touch('ref', '.git', 'HEAD');
    touch(XMI, 'node_modules', 'm.xmi');
});

after(() => {
    fs.rmSync(root, { recursive: true, force: true });
});

test('collectVsumFiles collects model files recursively and skips metadata and non-models', () => {
    const relative = collectVsumFiles(root).map(f => path.relative(root, f).split(path.sep).join('/')).sort();
    assert.deepEqual(relative, ['families.xmi', 'models/persons.xmi', 'vsum/vsum.correspondence']);
});

test('collectVsumFiles is empty for a missing folder or a file', () => {
    assert.deepEqual(collectVsumFiles(path.join(root, 'missing')), []);
    assert.deepEqual(collectVsumFiles(path.join(root, 'families.xmi')), []);
});

test('isModelFile recognizes XML, also after a byte order mark', () => {
    assert.equal(isModelFile(path.join(root, 'families.xmi')), true);
    assert.equal(isModelFile(path.join(root, 'models', 'persons.xmi')), true);
    assert.equal(isModelFile(path.join(root, 'LICENSE')), false);
    assert.equal(isModelFile(path.join(root, 'empty.xmi')), false);
    assert.equal(isModelFile(path.join(root, 'missing.xmi')), false);
});

test('containsVsum needs a correspondence file', () => {
    assert.equal(containsVsum(['a.xmi', 'vsum/vsum.correspondence']), true);
    assert.equal(containsVsum(['a.xmi']), false);
    assert.equal(containsVsum([]), false);
});

test('vsumRootOf is the folder above vsum/', () => {
    assert.equal(vsumRootOf(path.join('w', 'vsumexample-cwa', 'vsum', 'correspondences.correspondence')),
        path.join('w', 'vsumexample-cwa'));
    assert.equal(vsumRootOf(path.join('w', 'other', 'correspondences.correspondence')), undefined);
});

test('metamodelNamesIn finds the qualifiers of the constraints, not those in comments', () => {
    const constraints = [
        '-- context old::Thing inv x: true',
        'context allocation::AllocationContext inv a:',
        '  c2::Container.allInstances()->notEmpty() and self.x = "ticket::not"',
    ].join('\n');
    assert.deepEqual([...metamodelNamesIn(constraints)].sort(), ['allocation', 'c2', 'ticket']);
});

/** VSUM folders as in the CoronaWarnApp case study. */
function caseStudy(): VsumCandidate[] {
    const cwaFiles = ['adr.adr', 'c1.c1', 'c2.c2', 'default.allocation', 'default.repository', 'ticket.ticket'];
    const vsum = (root: string, files: string[]): VsumCandidate =>
        ({ root, files: [...files.map(f => path.join(root, f)), path.join(root, 'vsum', 'correspondences.correspondence')] });
    return [
        vsum(path.join('w', 'vsum', 'src', 'test', 'scenario1', 'vsumStateTestExecution'), cwaFiles),
        vsum(path.join('w', 'vsum', 'target', 'test-classes', 'scenario1', 'vsumStateTestExecution'), cwaFiles),
        vsum(path.join('w', 'vsumexample'), ['example.model', 'example.model2']),
        vsum(path.join('w', 'vsumexample-cwa'), cwaFiles),
        { root: path.join('w', 'novsum'), files: cwaFiles.map(f => path.join('w', 'novsum', f)) },
    ];
}

test('bestVsumCandidates takes the VSUM whose models fit the constraints, outside tests and build output', () => {
    const best = bestVsumCandidates(caseStudy(), new Set(['allocation', 'c1', 'c2', 'ticket', 'repository']));
    assert.deepEqual(best.map(candidate => candidate.root), [path.join('w', 'vsumexample-cwa')]);
});

test('bestVsumCandidates returns all VSUMs that fit equally well', () => {
    const tied = caseStudy().filter(candidate => !candidate.root.includes('src') && !candidate.root.includes('target'));
    const twice = [...tied, { ...tied[1], root: path.join('w', 'copy-cwa') }];
    const best = bestVsumCandidates(twice, new Set(['c1']));
    assert.deepEqual(best.map(candidate => candidate.root), [path.join('w', 'vsumexample-cwa'), path.join('w', 'copy-cwa')]);
});

test('bestVsumCandidates without matching metamodels prefers folders outside tests, and needs a VSUM', () => {
    const best = bestVsumCandidates(caseStudy(), new Set());
    assert.deepEqual(best.map(candidate => candidate.root), [path.join('w', 'vsumexample'), path.join('w', 'vsumexample-cwa')]);
    assert.deepEqual(bestVsumCandidates([], new Set(['c1'])), []);
});
