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
exports.collectVsumFiles = collectVsumFiles;
exports.containsVsum = containsVsum;
/**
 * Collecting the model instance files of a VSUM storage folder. Uses only `fs` and `path`, not
 * `vscode`, so that it can be tested with `node --test`.
 */
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
/** Directories that should never be treated as model instance sources. */
const VSUM_SKIP_DIRS = new Set(['.git', 'target', 'src', 'node_modules']);
/** Extensions of build artifacts, source files and internal Vitruvius metadata. */
const SKIPPED_EXTENSIONS = new Set([
    '.ecore', '.genmodel', '.java', '.class', '.jar', '.mwe2', '.reactions', '.xtend', '.uuid', '.models',
]);
/**
 * Recursively collects all model instance files from a VSUM storage folder.
 * Skips the internal vsum/ metadata subfolder and other non-model directories.
 * Model files can be at the root level OR inside subdirectories.
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
                if (SKIPPED_EXTENSIONS.has(ext) || entry.name.endsWith('.marker_vitruv')) {
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
//# sourceMappingURL=vsumFiles.js.map