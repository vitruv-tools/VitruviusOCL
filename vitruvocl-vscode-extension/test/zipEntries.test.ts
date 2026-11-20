import { after, test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { MAX_ENTRY_BYTES, readZipEntries } from '../src/zipEntries';
import { writeZip } from './zipFixture';

const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'vitruvocl-zip-'));

after(() => fs.rmSync(folder, { recursive: true, force: true }));

test('readZipEntries reads stored and deflated entries the filter accepts', () => {
    const jar = path.join(folder, 'a.jar');
    writeZip(jar, [
        { name: 'META-INF/MANIFEST.MF', content: 'Manifest-Version: 1.0\n' },
        { name: 'model/', content: '' },
        { name: 'model/pcm.ecore', content: '<ecore:EPackage name="pcm"/>'.repeat(50), deflate: true },
        { name: 'Main.class', content: 'binary' },
        { name: 'pcm.ecore', content: '<ecore:EPackage name="pcm"/>' },
    ]);
    const entries = readZipEntries(jar, name => name.endsWith('.ecore'));
    assert.deepEqual(entries.map(e => e.name), ['model/pcm.ecore', 'pcm.ecore']);
    assert.equal(entries[0].data.toString(), '<ecore:EPackage name="pcm"/>'.repeat(50));
    assert.equal(entries[1].data.toString(), '<ecore:EPackage name="pcm"/>');
});

test('readZipEntries rejects a file that is no zip archive', () => {
    const file = path.join(folder, 'not.jar');
    fs.writeFileSync(file, 'just text');
    assert.throws(() => readZipEntries(file, () => true), /not a zip archive/);
});

test('readZipEntries rejects an entry that inflates beyond the limit', () => {
    const jar = path.join(folder, 'bomb.jar');
    writeZip(jar, [{ name: 'big.ecore', content: 'x'.repeat(MAX_ENTRY_BYTES + 1), deflate: true }]);
    assert.throws(() => readZipEntries(jar, () => true), /larger than/);
});
