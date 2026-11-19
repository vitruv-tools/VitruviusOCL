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
exports.isModelFile = isModelFile;
exports.collectVsumFiles = collectVsumFiles;
exports.containsVsum = containsVsum;
exports.vsumRootOf = vsumRootOf;
exports.metamodelNamesIn = metamodelNamesIn;
exports.bestVsumCandidates = bestVsumCandidates;
/**
 * Collecting the model instance files of a VSUM storage folder, and choosing among several VSUM
 * folders the one whose models the constraints are about. Uses only `fs` and `path`, not `vscode`,
 * so that it can be tested with `node --test`.
 */
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const units_1 = require("./units");
/** Directories that should never be treated as model instance sources. */
const VSUM_SKIP_DIRS = new Set(['.git', 'target', 'src', 'node_modules']);
/** Extensions of build artifacts, source files and internal Vitruvius metadata. */
const SKIPPED_EXTENSIONS = new Set([
    '.ecore', '.genmodel', '.java', '.class', '.jar', '.mwe2', '.reactions', '.xtend', '.uuid', '.models',
    '.xml', '.project', '.classpath', '.prefs',
]);
/** How many bytes of a file are read to tell whether it is a model. */
const MODEL_SNIFF_BYTES = 512;
/**
 * Whether the file is a model: models are XMI, so XML, which starts with `<` after an optional
 * byte order mark and white space. A `LICENSE`, a CSV file or a JSON file is not.
 */
function isModelFile(file) {
    let descriptor;
    try {
        descriptor = fs.openSync(file, 'r');
        const buffer = Buffer.alloc(MODEL_SNIFF_BYTES);
        const length = fs.readSync(descriptor, buffer, 0, MODEL_SNIFF_BYTES, 0);
        const start = buffer.toString('utf-8', 0, length).replace(/^﻿/, '').trimStart();
        return start.startsWith('<');
    }
    catch {
        return false;
    }
    finally {
        if (descriptor !== undefined)
            fs.closeSync(descriptor);
    }
}
/**
 * Recursively collects all model instance files from a VSUM storage folder.
 * Skips the internal vsum/ metadata files, other non-model directories and files that are not
 * models (see `isModelFile`). Model files can be at the root level OR inside subdirectories.
 */
function collectVsumFiles(vsumFolder) {
    if (!fs.existsSync(vsumFolder) || !fs.statSync(vsumFolder).isDirectory())
        return [];
    const result = [];
    function walk(dir) {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const fullPath = path.join(dir, entry.name);
            if (entry.isFile()) {
                const ext = path.extname(entry.name).toLowerCase();
                if (SKIPPED_EXTENSIONS.has(ext) || SKIPPED_EXTENSIONS.has(entry.name.toLowerCase())
                    || entry.name.endsWith('.marker_vitruv') || !isModelFile(fullPath)) {
                    continue;
                }
                result.push(fullPath);
            }
            else if (entry.isDirectory()) {
                if (VSUM_SKIP_DIRS.has(entry.name))
                    continue;
                walk(fullPath);
            }
        }
    }
    walk(vsumFolder);
    return result;
}
/** Whether the files contain a VSUM, recognized by its `.correspondence` file. */
function containsVsum(instanceFiles) {
    return instanceFiles.some(f => f.endsWith('.correspondence'));
}
/**
 * The root of the VSUM a correspondence file belongs to: Vitruvius keeps it in `<root>/vsum/`.
 *
 * @returns the root, or `undefined` if the file is not in a `vsum` folder
 */
function vsumRootOf(correspondenceFile) {
    const folder = path.dirname(correspondenceFile);
    return path.basename(folder) === 'vsum' ? path.dirname(folder) : undefined;
}
/** The metamodel names the constraints refer to, like `c1` in `context c1::System`. */
function metamodelNamesIn(constraints) {
    const names = new Set();
    for (const line of constraints.split('\n')) {
        for (const match of (0, units_1.stripComment)(line).matchAll(/([A-Za-z_]\w*)\s*::/g)) {
            names.add(match[1].toLowerCase());
        }
    }
    return names;
}
function fitOf(candidate, metamodels) {
    const keys = new Set();
    for (const file of candidate.files) {
        const base = path.basename(file).toLowerCase();
        const ext = path.extname(base);
        keys.add(ext.slice(1));
        keys.add(base.slice(0, base.length - ext.length));
    }
    const matches = [...metamodels].filter(name => keys.has(name)).length;
    const segments = candidate.root.split(/[\\/]/).map(segment => segment.toLowerCase());
    const secondary = segments.includes('target') || segments.some((segment, i) => segment === 'src' && segments[i + 1] === 'test');
    return { matches, primary: secondary ? 0 : 1 };
}
/**
 * The VSUM folders that fit the constraints best: most metamodels with models, preferring folders
 * outside build output and test fixtures. Several are returned only if they fit equally well.
 */
function bestVsumCandidates(candidates, metamodels) {
    const scored = candidates
        .filter(candidate => containsVsum(candidate.files))
        .map(candidate => ({ candidate, fit: fitOf(candidate, metamodels) }));
    if (scored.length === 0)
        return [];
    const best = scored.reduce((a, b) => b.fit.matches > a.fit.matches || (b.fit.matches === a.fit.matches && b.fit.primary > a.fit.primary) ? b : a);
    return scored
        .filter(s => s.fit.matches === best.fit.matches && s.fit.primary === best.fit.primary)
        .map(s => s.candidate);
}
//# sourceMappingURL=vsumFiles.js.map