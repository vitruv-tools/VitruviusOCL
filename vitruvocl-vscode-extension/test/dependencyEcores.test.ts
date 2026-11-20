import { after, test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { DependencyEcores } from '../src/dependencyEcores';
import { findPom, mavenCommand, mavenCommandLine, parseClasspath } from '../src/mavenClasspath';
import { writeZip } from './zipFixture';

const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'vitruvocl-deps-'));
after(() => fs.rmSync(folder, { recursive: true, force: true }));

function write(file: string, content: string): string {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content);
    return file;
}

const PCM = '<ecore:EPackage name="pcm" nsURI="http://pcm/5.2"><eSubpackages name="allocation" nsURI="http://pcm/Allocation/5.2"/></ecore:EPackage>';
const OWN = '<ecore:EPackage name="c1" nsURI="http://c1"/>';

/** A project with a module whose dependency jar holds PCM. */
function project() {
    const root = path.join(folder, `project-${Math.random().toString(36).slice(2)}`);
    write(path.join(root, 'pom.xml'), '<project/>');
    const modulePom = write(path.join(root, 'consistency', 'pom.xml'), '<project/>');
    const constraintFile = write(path.join(root, 'consistency', 'src', 'constraints.ocl'),
        'context allocation::AllocationContext inv a: true\ncontext c1::System inv b: true');
    const ownEcore = write(path.join(root, 'model', 'c1.ecore'), OWN);
    const jar = path.join(root, 'org.palladiosimulator.pcm-5.2.1.jar');
    writeZip(jar, [
        { name: 'META-INF/MANIFEST.MF', content: 'Bundle-SymbolicName: org.palladiosimulator.pcm;singleton:=true\n' },
        { name: 'model/pcm.ecore', content: PCM, deflate: true },
    ]);
    return { root, modulePom, constraintFile, ownEcore, jar };
}

test('the metamodels of the dependencies are extracted where an Eclipse installation has them', async () => {
    const p = project();
    const poms: string[] = [];
    const cache = path.join(p.root, 'cache');
    const modes: string[] = [];
    const dependencies = new DependencyEcores(cache, () => undefined, async (pom, _root, _cache, mode) => {
        poms.push(pom);
        modes.push(mode);
        return [p.jar];
    });
    const needs = {
        constraintFile: p.constraintFile,
        constraintsText: fs.readFileSync(p.constraintFile, 'utf-8'),
        workspaceRoot: p.root,
        workspaceEcores: [p.ownEcore],
        modelFiles: [],
    };

    const ecores = await dependencies.ecoresFor(needs, 'offline');
    assert.deepEqual(ecores, [path.join(cache, 'plugins', 'org.palladiosimulator.pcm', 'model', 'pcm.ecore')]);
    assert.equal(fs.readFileSync(ecores[0], 'utf-8'), PCM);
    assert.deepEqual(poms, [p.modulePom]);
    assert.deepEqual(modes, ['offline']);
});

test('a project Maven cannot resolve is not tried again until its pom changes', async () => {
    const p = project();
    let calls = 0;
    const logged: string[] = [];
    const dependencies = new DependencyEcores(path.join(p.root, 'cache'), line => logged.push(line), async () => {
        calls++;
        return undefined;
    });
    const needs = {
        constraintFile: p.constraintFile, constraintsText: '', workspaceRoot: p.root, workspaceEcores: [], modelFiles: [],
    };
    assert.deepEqual(await dependencies.ecoresFor(needs, 'offline'), []);
    assert.deepEqual(await dependencies.ecoresFor(needs, 'offline'), []);
    assert.equal(calls, 1);
    assert.equal(logged.length, 1);

    // a run that may download tries again; so does one after forget()
    await dependencies.ecoresFor(needs, 'online');
    assert.equal(calls, 2);
    dependencies.forget();
    await dependencies.ecoresFor(needs, 'offline');
    assert.equal(calls, 3);

    const later = new Date(Date.now() + 5000);
    fs.utimesSync(p.modulePom, later, later);
    await dependencies.ecoresFor(needs, 'offline');
    assert.equal(calls, 4);
});

test('without a pom.xml no dependencies are resolved', async () => {
    const root = path.join(folder, 'plain');
    const file = write(path.join(root, 'a.ocl'), 'context x::A inv a: true');
    const dependencies = new DependencyEcores(path.join(root, 'cache'), () => undefined, async () => {
        throw new Error('must not be called');
    });
    assert.deepEqual(await dependencies.ecoresFor({
        constraintFile: file, constraintsText: '', workspaceRoot: root, workspaceEcores: [], modelFiles: [],
    }, 'online'), []);
});

test('findPom takes the nearest pom.xml, not above the workspace', () => {
    const p = project();
    assert.equal(findPom(path.dirname(p.constraintFile), p.root), p.modulePom);
    assert.equal(findPom(path.join(p.root, 'model'), p.root), path.join(p.root, 'pom.xml'));
    assert.equal(findPom(path.join(p.root, 'model'), path.join(p.root, 'model')), undefined);
});

test('mavenCommand prefers the wrapper of the workspace', () => {
    const p = project();
    assert.equal(mavenCommand(p.root, 'win32'), 'mvn.cmd');
    assert.equal(mavenCommand(p.root, 'linux'), 'mvn');
    write(path.join(p.root, 'mvnw.cmd'), '');
    assert.equal(mavenCommand(p.root, 'win32'), path.join(p.root, 'mvnw.cmd'));
});

test('parseClasspath keeps the jars', () => {
    assert.deepEqual(parseClasspath(['a.jar', ' b.jar ', 'classes', ''].join(path.delimiter)), ['a.jar', 'b.jar']);
});

test('an entry whose path leaves the cache folder is not extracted', async () => {
    const p = project();
    const evil = path.join(p.root, 'evil.jar');
    writeZip(evil, [
        { name: 'META-INF/MANIFEST.MF', content: 'Bundle-SymbolicName: evil\n' },
        { name: '../../../escaped.ecore', content: PCM },
    ]);
    const logged: string[] = [];
    const cache = path.join(p.root, 'cache');
    const dependencies = new DependencyEcores(cache, line => logged.push(line), async () => [evil]);
    const ecores = await dependencies.ecoresFor({
        constraintFile: p.constraintFile, constraintsText: 'context allocation::A inv a: true',
        workspaceRoot: p.root, workspaceEcores: [], modelFiles: [],
    }, 'online');
    assert.deepEqual(ecores, []);
    assert.equal(fs.existsSync(path.join(p.root, 'escaped.ecore')), false);
    assert.match(logged.join('\n'), /leaves the cache folder/);
});

test('mavenCommandLine takes the paths from the environment, so the shell interprets none of them', () => {
    assert.equal(mavenCommandLine('mvn.cmd', 'offline', 'win32'),
        '"%VOCL_MVN%" -o -q -B dependency:build-classpath "-Dmdep.outputFile=%VOCL_OUT%" -f "%VOCL_POM%"');
    assert.equal(mavenCommandLine('/p/mvnw', 'online', 'linux'),
        'sh "$VOCL_MVN" -q -B dependency:build-classpath "-Dmdep.outputFile=$VOCL_OUT" -f "$VOCL_POM"');
    assert.equal(mavenCommandLine('mvn', 'online', 'linux'),
        '"$VOCL_MVN" -q -B dependency:build-classpath "-Dmdep.outputFile=$VOCL_OUT" -f "$VOCL_POM"');
});
