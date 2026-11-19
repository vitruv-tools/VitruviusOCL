"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.bundleNameOf = bundleNameOf;
exports.fileNameOf = fileNameOf;
exports.parseEcore = parseEcore;
exports.providedBy = providedBy;
exports.selectMetamodels = selectMetamodels;
exports.nsUrisOfModels = nsUrisOfModels;
/**
 * Choosing the metamodels a project gets from its dependencies: of all `.ecore` files in the jars
 * of the project's classpath, those that the models (by namespace URI) or the constraints (by
 * package name) need and the workspace does not provide, together with the metamodels these refer
 * to. Pure functions without `vscode`, so that they can be tested with `node --test`.
 */
const fs = __importStar(require("fs"));
/**
 * Namespace URIs whose packages the CLI and the language server have built in, from EMF and
 * Vitruvius: loading `.ecore` copies of them would replace the built-in, generated packages, so
 * metamodels declaring only these are never chosen.
 */
const BUILT_IN_NS_URIS = new Set([
    'http://www.eclipse.org/emf/2002/Ecore',
    'http://www.eclipse.org/emf/2003/XMLType',
    'http://www.w3.org/XML/1998/namespace',
    'http://vitruv.tools/metamodels/change/correspondence/1.0',
]);
const PACKAGE_TAG = /<(?:ecore:EPackage|eSubpackages)\b[^>]*>/g;
const NAME_ATTRIBUTE = /\bname="([^"]*)"/;
const NS_URI_ATTRIBUTE = /\bnsURI="([^"]*)"/;
const PLATFORM_REF = /platform:\/plugin\/[^"#\s]+\/([^/"#\s]+\.ecore)/g;
const NS_URI_REF = /([a-z][\w+.-]*:\/\/[^"#\s]+)#\/\//g;
/**
 * The bundle name of a jar: its manifest's `Bundle-SymbolicName`, else its file name without the
 * version, e.g. `org.palladiosimulator.pcm` for `org.palladiosimulator.pcm-5.2.1.jar`.
 */
function bundleNameOf(jarPath, manifest) {
    // a manifest line longer than 72 bytes continues on the next line, which starts with a space
    const unfolded = manifest?.replace(/\r?\n /g, '');
    const symbolicName = unfolded?.match(/^Bundle-SymbolicName:\s*([^;\r\n]+)/m)?.[1].trim();
    if (symbolicName)
        return symbolicName;
    return fileNameOf(jarPath).replace(/\.jar$/i, '').replace(/[-_]\d[\w.-]*$/, '');
}
/** The file name of a path in a jar or on disk. */
function fileNameOf(entry) {
    return entry.split(/[\\/]/).pop() ?? entry;
}
/** What an `.ecore` file declares and refers to. */
function parseEcore(origin, entry, content, bundle = '') {
    const packages = [];
    for (const tag of content.match(PACKAGE_TAG) ?? []) {
        const name = tag.match(NAME_ATTRIBUTE)?.[1];
        const nsUri = tag.match(NS_URI_ATTRIBUTE)?.[1];
        if (name && nsUri)
            packages.push({ name, nsUri });
    }
    const fileRefs = [...new Set([...content.matchAll(PLATFORM_REF)].map(m => m[1]))];
    const nsUriRefs = [...new Set([...content.matchAll(NS_URI_REF)].map(m => m[1]))]
        .filter(uri => !uri.startsWith('platform:') && !uri.startsWith('file:'));
    return { origin, entry, bundle, content, packages, fileRefs, nsUriRefs };
}
/** What the given `.ecore` files provide. */
function providedBy(infos) {
    const packages = infos.flatMap(info => info.packages);
    return {
        nsUris: new Set(packages.map(p => p.nsUri)),
        names: new Set(packages.map(p => p.name.toLowerCase())),
    };
}
function isBuiltIn(info) {
    return info.packages.length > 0 && info.packages.every(p => BUILT_IN_NS_URIS.has(p.nsUri));
}
/**
 * The metamodels to load from the dependencies: those declaring a needed namespace URI or package
 * name that the workspace does not provide, and the metamodels these refer to, transitively. A
 * metamodel that is in several jars or twice in one jar is taken once.
 *
 * @param neededNsUris the namespace URIs the models use
 * @param neededNames the package names the constraints use, in lower case
 */
function selectMetamodels(candidates, neededNsUris, neededNames, provided) {
    // of copies of one metamodel in a jar, the one in a folder (model/x.ecore) is the one other
    // metamodels refer to relatively, so it is preferred
    const depth = (info) => info.entry.split('/').length;
    const usable = candidates
        .filter(info => info.packages.length > 0 && !isBuiltIn(info))
        .sort((a, b) => depth(b) - depth(a));
    const selected = [];
    const covered = new Set(); // root nsURIs of the selected metamodels
    const done = new Set();
    const queue = [
        ...[...neededNsUris].map(uri => `uri:${uri}`),
        ...[...neededNames].map(name => `name:${name.toLowerCase()}`),
    ];
    const select = (info) => {
        const root = info.packages[0].nsUri;
        if (covered.has(root))
            return;
        covered.add(root);
        selected.push(info);
        info.fileRefs.forEach(file => queue.push(`file:${file}`));
        info.nsUriRefs.forEach(uri => queue.push(`uri:${uri}`));
    };
    while (queue.length > 0) {
        const key = queue.shift();
        if (done.has(key))
            continue;
        done.add(key);
        const [kind, value] = [key.slice(0, key.indexOf(':')), key.slice(key.indexOf(':') + 1)];
        if (kind === 'uri' && (provided.nsUris.has(value) || BUILT_IN_NS_URIS.has(value)))
            continue;
        if (kind === 'name' && provided.names.has(value))
            continue;
        const provider = usable.find(info => kind === 'uri' ? info.packages.some(p => p.nsUri === value)
            : kind === 'name' ? info.packages.some(p => p.name.toLowerCase() === value)
                : fileNameOf(info.entry) === value);
        if (provider)
            select(provider);
    }
    return selected;
}
const XMLNS = /\bxmlns:[\w.-]+="([^"]+)"/g;
/** How much of a model file is read for its namespace declarations, which stand at its start. */
const MODEL_HEAD_BYTES = 16 * 1024;
/** The namespace URIs the model files declare, i.e. the metamodels they need. */
function nsUrisOfModels(files) {
    const uris = new Set();
    for (const file of files) {
        let descriptor;
        try {
            descriptor = fs.openSync(file, 'r');
            const buffer = Buffer.alloc(MODEL_HEAD_BYTES);
            const length = fs.readSync(descriptor, buffer, 0, MODEL_HEAD_BYTES, 0);
            for (const match of buffer.toString('utf-8', 0, length).matchAll(XMLNS)) {
                uris.add(match[1]);
            }
        }
        catch {
            // an unreadable model is reported when the CLI loads it
        }
        finally {
            if (descriptor !== undefined)
                fs.closeSync(descriptor);
        }
    }
    return uris;
}
//# sourceMappingURL=dependencyMetamodels.js.map