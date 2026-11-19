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
exports.findCompilerJar = findCompilerJar;
exports.findLanguageServerJar = findLanguageServerJar;
exports.workspaceRootOf = workspaceRootOf;
exports.findEcoreFiles = findEcoreFiles;
exports.forgetVsumChoices = forgetVsumChoices;
exports.findInstanceFiles = findInstanceFiles;
/**
 * Finding what the CLI needs in the workspace: the compiler jar, the metamodels and the model
 * instances of a VSUM.
 */
const vscode = __importStar(require("vscode"));
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const vsumFiles_1 = require("./vsumFiles");
const output_1 = require("./output");
/** The configured or bundled `vitruvOCL.jar`, or `null`. */
async function findCompilerJar() {
    const config = vscode.workspace.getConfiguration('vitruvocl');
    const configured = config.get('compilerPath');
    if (configured && fs.existsSync(configured))
        return configured;
    const extPath = vscode.extensions.getExtension('vitruvocl.vitruvocl')?.extensionPath;
    if (extPath) {
        const bundled = path.join(extPath, 'lib', 'vitruvOCL.jar');
        if (fs.existsSync(bundled))
            return bundled;
    }
    return null;
}
/** The configured or bundled `language-server.jar`, or `null`. */
function findLanguageServerJar(context) {
    const config = vscode.workspace.getConfiguration('vitruvocl');
    const configured = config.get('languageServerPath');
    if (configured && fs.existsSync(configured))
        return configured;
    const bundled = path.join(context.extensionPath, 'lib', 'language-server.jar');
    if (fs.existsSync(bundled))
        return bundled;
    return null;
}
/** The workspace folder of the constraint file, or the first one. */
function workspaceRootOf(constraintFileUri) {
    return constraintFileUri
        ? vscode.workspace.getWorkspaceFolder(constraintFileUri)?.uri.fsPath
        : vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
}
/** Folders whose `.ecore` files are copies, test fixtures or dependencies, not the project's own. */
const ECORE_EXCLUDE = '{**/target/**,**/bin/**,**/build/**,**/node_modules/**,**/.git/**,**/src/test/**}';
/**
 * The `.ecore` files of the project: all in the workspace folder of the constraint file, also
 * those in an `ecore/` folder at its root, except build output and dependencies. A metamodel
 * found twice under the same path is passed once.
 */
async function findEcoreFiles(constraintFileUri) {
    const workspaceRoot = workspaceRootOf(constraintFileUri);
    if (!workspaceRoot)
        return [];
    const files = await vscode.workspace.findFiles(new vscode.RelativePattern(workspaceRoot, '**/*.ecore'), ECORE_EXCLUDE);
    return [...new Set(files.map(u => path.normalize(u.fsPath)))].sort();
}
/** Where the VSUM chosen among several equally fitting ones is remembered, per workspace folder. */
const CHOSEN_VSUM_KEY = 'vitruvocl.chosenVsum:';
/** Forgets every remembered choice among VSUM folders. */
async function forgetVsumChoices(memento) {
    for (const key of memento.keys().filter(k => k.startsWith(CHOSEN_VSUM_KEY))) {
        await memento.update(key, undefined);
    }
}
/** The VSUM folders of the workspace: every folder with a `vsum/*.correspondence`. */
async function findVsumCandidates(workspaceRoot) {
    const correspondences = await vscode.workspace.findFiles(new vscode.RelativePattern(workspaceRoot, '**/vsum/*.correspondence'), '{**/node_modules/**,**/.git/**}');
    const roots = [...new Set(correspondences
            .map(u => (0, vsumFiles_1.vsumRootOf)(u.fsPath))
            .filter((root) => root !== undefined))];
    // natural order, so that size-20 comes before size-150
    return roots
        .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
        .map(root => ({ root, files: (0, vsumFiles_1.collectVsumFiles)(root) }));
}
/**
 * Lets the user choose among VSUM folders that fit the constraints equally well, and remembers
 * the choice for the workspace folder.
 */
async function chooseVsum(candidates, workspaceRoot, memento) {
    const key = CHOSEN_VSUM_KEY + workspaceRoot;
    const remembered = memento.get(key);
    const known = candidates.find(candidate => candidate.root === remembered);
    if (known)
        return known;
    const picked = await vscode.window.showQuickPick(candidates.map(candidate => ({
        label: path.relative(workspaceRoot, candidate.root) || '.',
        description: `${candidate.files.length} model files`,
        candidate,
    })), {
        title: 'VitruvOCL: choose the VSUM',
        placeHolder: 'Several VSUMs fit these constraints: which one should they be checked against?',
        // stays open when the focus moves, e.g. back to the editor the run was started from
        ignoreFocusOut: true,
    });
    if (picked) {
        await memento.update(key, picked.candidate.root);
    }
    return picked?.candidate;
}
/**
 * The model instance files: of the configured `vitruvocl.vsumPath`, else of the VSUM folder in the
 * workspace whose models fit the constraints best (asking the user if several fit equally well),
 * else of the legacy `instances/` folder next to the constraint file.
 *
 * @param memento where a choice among several VSUM folders is remembered
 * @returns the files, or `undefined` if the user cancelled the choice among several VSUM folders
 */
async function findInstanceFiles(document, memento) {
    const workspaceRoot = workspaceRootOf(document.uri);
    if (!workspaceRoot)
        return [];
    // 0. User-configured VSUM path (vitruvocl.vsumPath setting)
    const config = vscode.workspace.getConfiguration('vitruvocl');
    const configuredPath = config.get('vsumPath');
    if (configuredPath) {
        const resolved = path.isAbsolute(configuredPath)
            ? configuredPath
            : path.join(workspaceRoot, configuredPath);
        const files = (0, vsumFiles_1.collectVsumFiles)(resolved);
        if (files.length > 0)
            return files;
    }
    // 1. The VSUM folder whose models fit the metamodels the constraints use
    const best = (0, vsumFiles_1.bestVsumCandidates)(await findVsumCandidates(workspaceRoot), (0, vsumFiles_1.metamodelNamesIn)(document.getText()));
    const chosen = best.length > 1 ? await chooseVsum(best, workspaceRoot, memento) : best[0];
    if (chosen) {
        (0, output_1.logLine)(`[VSUM] ${path.relative(workspaceRoot, chosen.root) || '.'} (${chosen.files.length} model files)`);
        return chosen.files;
    }
    if (best.length > 1)
        return undefined; // the user cancelled the choice
    // 2. Legacy instances/ folder relative to the constraint file
    const instancesPath = path.join(path.dirname(document.uri.fsPath), 'instances');
    if (fs.existsSync(instancesPath)) {
        return fs.readdirSync(instancesPath)
            .map(f => path.join(instancesPath, f))
            .filter(f => fs.statSync(f).isFile());
    }
    return [];
}
//# sourceMappingURL=discovery.js.map