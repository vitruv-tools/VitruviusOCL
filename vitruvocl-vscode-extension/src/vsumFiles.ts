/**
 * Collecting the model instance files of a VSUM storage folder. Uses only `fs` and `path`, not
 * `vscode`, so that it can be tested with `node --test`.
 */
import * as fs from 'fs';
import * as path from 'path';

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
export function collectVsumFiles(vsumFolder: string): string[] {
    if (!fs.existsSync(vsumFolder) || !fs.statSync(vsumFolder).isDirectory()) return [];
    const result: string[] = [];

    function walk(dir: string) {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const fullPath = path.join(dir, entry.name);
            if (entry.isFile()) {
                const ext = path.extname(entry.name).toLowerCase();
                if (SKIPPED_EXTENSIONS.has(ext) || entry.name.endsWith('.marker_vitruv')) {
                    continue;
                }
                result.push(fullPath);
            } else if (entry.isDirectory()) {
                if (VSUM_SKIP_DIRS.has(entry.name)) continue;
                walk(fullPath);
            }
        }
    }

    walk(vsumFolder);
    return result;
}

/** Whether the files contain a VSUM, recognized by its `.correspondence` file. */
export function containsVsum(instanceFiles: string[]): boolean {
    return instanceFiles.some(f => f.endsWith('.correspondence'));
}
