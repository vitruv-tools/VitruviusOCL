import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { ConstraintUnitInfo } from '../src/constraintLayout';
import { ConstraintUnitIndex, METHOD_NOT_FOUND, UnitDocument, UnitSource } from '../src/unitIndex';

const TEXT = 'context A\n  inv a: true';

const UNITS: ConstraintUnitInfo[] = [
    // named differently than in TEXT, so that the tests tell the server's units from the own scan
    { kind: 'INV', name: 'fromServer', contextType: 'A', text: 'context A\ninv fromServer: true', textLines: [1, 2], line: 2, column: 2 },
];

/** A document whose text and version a test can change. */
class FakeDocument implements UnitDocument {
    readonly uri = { toString: () => 'file:///a.ocl' };
    version = 1;
    isClosed = false;
    constructor(public text = TEXT) {}
    getText(): string {
        return this.text;
    }
}

/** A language server whose answers a test controls; it counts the requests. */
class FakeSource implements UnitSource {
    requests = 0;
    running = true;
    constructor(private readonly answer: (document: UnitDocument) => Promise<ConstraintUnitInfo[]>) {}
    ready(): boolean {
        return this.running;
    }
    request(document: UnitDocument): Promise<ConstraintUnitInfo[]> {
        this.requests++;
        return this.answer(document);
    }
}

function index(source: UnitSource | undefined, warnings: string[] = []): ConstraintUnitIndex {
    return new ConstraintUnitIndex(source, message => warnings.push(message));
}

test('the layout comes from the language server and is cached per version', async () => {
    const source = new FakeSource(async () => UNITS);
    const units = index(source);
    const document = new FakeDocument();

    assert.deepEqual((await units.layoutOf(document))?.headers, [{ line: 1, name: 'fromServer' }]);
    await units.layoutOf(document);
    assert.equal(source.requests, 1);

    document.version = 2;
    await units.layoutOf(document);
    assert.equal(source.requests, 2);
});

test('concurrent requests for one version are shared', async () => {
    const source = new FakeSource(async () => UNITS);
    const units = index(source);
    const document = new FakeDocument();
    await Promise.all([units.layoutOf(document), units.unitsOf(document), units.layoutOf(document)]);
    assert.equal(source.requests, 1);
});

test('an answer for an outdated version gives no layout', async () => {
    const document = new FakeDocument();
    const source = new FakeSource(async () => {
        document.version = 2;
        return UNITS;
    });
    assert.equal(await index(source).layoutOf(document), undefined);
});

test('without a language server, or before it runs, the own scan finds the constraints', async () => {
    const regexDocument = new FakeDocument('context A inv a: true');
    assert.deepEqual((await index(undefined).layoutOf(regexDocument))?.headers, [{ line: 0, name: 'a' }]);

    const source = new FakeSource(async () => UNITS);
    source.running = false;
    assert.deepEqual((await index(source).layoutOf(new FakeDocument()))?.headers, [{ line: 1, name: 'a' }]);
    assert.equal(source.requests, 0);
});

test('a language server without the request is not asked again', async () => {
    const warnings: string[] = [];
    const source = new FakeSource(async () => {
        throw Object.assign(new Error('Unhandled method'), { code: METHOD_NOT_FOUND });
    });
    const units = index(source, warnings);
    const document = new FakeDocument();

    assert.deepEqual((await units.layoutOf(document))?.headers, [{ line: 1, name: 'a' }]);
    document.version = 2;
    await units.layoutOf(document);
    assert.equal(source.requests, 1);
    assert.equal(warnings.length, 1);
});

test('any other failure falls back to the own scan once and asks again next time', async () => {
    let fail = true;
    const source = new FakeSource(async () => {
        if (fail) {
            throw new Error('Client is not running');
        }
        return UNITS;
    });
    const units = index(source);
    const document = new FakeDocument();

    assert.deepEqual((await units.layoutOf(document))?.headers, [{ line: 1, name: 'a' }]);
    fail = false;
    assert.deepEqual((await units.layoutOf(document))?.headers, [{ line: 1, name: 'fromServer' }]);
    assert.equal(source.requests, 2);
});

test('the units of a closed or forgotten document are not kept', async () => {
    const document = new FakeDocument();
    const source = new FakeSource(async () => {
        document.isClosed = true;
        return UNITS;
    });
    const units = index(source);
    await units.unitsOf(document);
    await units.unitsOf(document);
    assert.equal(source.requests, 2);

    document.isClosed = false;
    await units.unitsOf(document);
    units.forget(document);
    await units.unitsOf(document);
    assert.equal(source.requests, 4);
});
