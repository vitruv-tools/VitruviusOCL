import { after, test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
    bundleNameOf,
    EcoreInfo,
    nsUrisOfModels,
    parseEcore,
    providedBy,
    selectMetamodels,
} from '../src/dependencyMetamodels';

/** An `.ecore` text with a root package, sub-packages and references. */
function ecore(name: string, nsUri: string, body = ''): string {
    return `<?xml version="1.0" encoding="UTF-8"?>
<ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
    name="${name}" nsURI="${nsUri}" nsPrefix="${name}">
${body}
</ecore:EPackage>`;
}

const PCM = ecore('pcm', 'http://pcm/5.2', `
  <eSubpackages name="allocation" nsURI="http://pcm/Allocation/5.2"/>
  <eSubpackages name="repository" nsURI="http://pcm/Repository/5.2">
    <eClassifiers eSuperTypes="platform:/plugin/de.uka.ipd.sdq.identifier/model/identifier.ecore#//Identifier"/>
    <eClassifiers eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString"/>
  </eSubpackages>`);
const IDENTIFIER = ecore('identifier', 'http://identifier/2.1');
const EXTENSION = ecore('confidentiality', 'http://ext/0.1', `
  <eClassifiers eSuperTypes="../../org.palladiosimulator.pcm/model/pcm.ecore#//Thing">
    <eStructuralFeatures eType="ecore:EClass http://stoex/2.2#//Expression"/>
  </eClassifiers>`);
const STOEX = ecore('stoex', 'http://stoex/2.2');
const ECORE = ecore('ecore', 'http://www.eclipse.org/emf/2002/Ecore');
const CORRESPONDENCE = ecore('correspondence', 'http://vitruv.tools/metamodels/change/correspondence/1.0');

function info(entry: string, content: string, origin = `${entry}.jar`): EcoreInfo {
    return parseEcore(origin, entry, content, 'bundle');
}

const CANDIDATES = [
    info('pcm.ecore', PCM, 'pcm.jar'),
    info('model/pcm.ecore', PCM, 'pcm.jar'),
    info('model/identifier.ecore', IDENTIFIER),
    info('model/dataFlowConfidentiality.ecore', EXTENSION),
    info('model/stoex.ecore', STOEX),
    info('model/Ecore.ecore', ECORE),
    info('src/main/ecore/correspondence.ecore', CORRESPONDENCE),
];

const NOTHING: { nsUris: Set<string>; names: Set<string> } = { nsUris: new Set(), names: new Set() };

test('parseEcore reads the packages and the references to other metamodels', () => {
    const pcm = info('pcm.ecore', PCM);
    assert.deepEqual(pcm.packages, [
        { name: 'pcm', nsUri: 'http://pcm/5.2' },
        { name: 'allocation', nsUri: 'http://pcm/Allocation/5.2' },
        { name: 'repository', nsUri: 'http://pcm/Repository/5.2' },
    ]);
    assert.deepEqual(pcm.fileRefs, ['identifier.ecore']);
    assert.deepEqual(pcm.nsUriRefs, ['http://www.eclipse.org/emf/2002/Ecore']);
    assert.deepEqual(info('x.ecore', EXTENSION).nsUriRefs, ['http://stoex/2.2']);
});

test('a name of the constraints selects a metamodel with its references, the copy in a folder', () => {
    const selected = selectMetamodels(CANDIDATES, [], ['allocation'], NOTHING);
    assert.deepEqual(selected.map(s => s.entry), ['model/pcm.ecore', 'model/identifier.ecore']);
});

test('a namespace URI of the models selects a metamodel with the ones it refers to', () => {
    const selected = selectMetamodels(CANDIDATES, ['http://ext/0.1', 'http://www.omg.org/XMI'], [], NOTHING);
    assert.deepEqual(selected.map(s => s.entry), ['model/dataFlowConfidentiality.ecore', 'model/stoex.ecore']);
});

test('what the workspace provides and what is built in is not selected', () => {
    const provided = providedBy([info('pcm.ecore', PCM)]);
    assert.deepEqual(selectMetamodels(CANDIDATES, ['http://pcm/Repository/5.2'], ['allocation'], provided), []);
    assert.deepEqual(selectMetamodels(CANDIDATES,
        ['http://www.eclipse.org/emf/2002/Ecore', 'http://vitruv.tools/metamodels/change/correspondence/1.0'],
        ['ecore', 'correspondence'], NOTHING), []);
});

test('bundleNameOf takes the symbolic name, also a folded one, else the artifact name', () => {
    const folded = 'Manifest-Version: 1.0\r\nBundle-SymbolicName: org.dataflowanalysis.pcm.extension.dictionary.chara\r\n cterized;singleton:=true\r\nBundle-Version: 5.2.1\r\n';
    assert.equal(bundleNameOf('x.jar', folded), 'org.dataflowanalysis.pcm.extension.dictionary.characterized');
    assert.equal(bundleNameOf('/m2/org.palladiosimulator.pcm-5.2.1.202412151831.jar', undefined), 'org.palladiosimulator.pcm');
    assert.equal(bundleNameOf('C:\\m2\\tools.vitruv.change.atomic-4.0.0.jar', 'Manifest-Version: 1.0\n'), 'tools.vitruv.change.atomic');
});

const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'vitruvocl-models-'));
after(() => fs.rmSync(folder, { recursive: true, force: true }));

test('nsUrisOfModels reads the namespace declarations of the models', () => {
    const model = path.join(folder, 'default.repository');
    fs.writeFileSync(model, '<?xml version="1.0"?>\n<repository:Repository xmlns:xmi="http://www.omg.org/XMI" xmlns:repository="http://pcm/Repository/5.2" xmlns:confidentiality="http://ext/0.1"/>');
    assert.deepEqual([...nsUrisOfModels([model, path.join(folder, 'missing')])],
        ['http://www.omg.org/XMI', 'http://pcm/Repository/5.2', 'http://ext/0.1']);
});
