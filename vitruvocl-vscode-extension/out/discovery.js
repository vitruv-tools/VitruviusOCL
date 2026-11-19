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
exports.findEcoreFiles = findEcoreFiles;
exports.findInstanceFiles = findInstanceFiles;
/**
 * Finding what the CLI needs in the workspace: the compiler jar, the metamodels and the model
 * instances of a VSUM.
 */
const vscode = __importStar(require("vscode"));
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const vsumFiles_1 = require("./vsumFiles");
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
function workspaceRootOf(constraintFileUri) {
    return constraintFileUri
        ? vscode.workspace.getWorkspaceFolder(constraintFileUri)?.uri.fsPath
        : vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
}
/** The `.ecore` files: those in `ecore/` at the workspace root, else all in the workspace. */
async function findEcoreFiles(constraintFileUri) {
    const workspaceRoot = workspaceRootOf(constraintFileUri);
    if (!workspaceRoot)
        return [];
    // Primary: ecore/ folder at workspace root
    const ecorePath = path.join(workspaceRoot, 'ecore');
    if (fs.existsSync(ecorePath)) {
        return fs.readdirSync(ecorePath)
            .filter(f => f.endsWith('.ecore'))
            .map(f => path.join(ecorePath, f))
            .filter(f => fs.statSync(f).isFile());
    }
    // Fallback: workspace-wide search (skips target/ implicitly via VS Code glob)
    const files = await vscode.workspace.findFiles('**/*.ecore', '**/target/**');
    return files.map(u => u.fsPath);
}
/**
 * The model instance files, from the first of these that has any: the configured
 * `vitruvocl.vsumPath`, `vsum/target/vsumtest/`, `vsumexample/` (at the root or in `vsum/`), any
 * `vsumtest`, `vsumexample` or `vsum-storage` folder, and the legacy `instances/` folder next to
 * the constraint file.
 */
async function findInstanceFiles(constraintFileUri) {
    const workspaceRoot = workspaceRootOf(constraintFileUri);
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
    // 1. Standard Maven test VSUM: vsum/target/vsumtest/
    const vsumBase = path.join(workspaceRoot, 'vsum', 'target', 'vsumtest');
    const files1 = (0, vsumFiles_1.collectVsumFiles)(vsumBase);
    if (files1.length > 0)
        return files1;
    // 2. VSUMExample default: vsumexample/ at workspace root or inside vsum/
    for (const candidate of [
        path.join(workspaceRoot, 'vsumexample'),
        path.join(workspaceRoot, 'vsum', 'vsumexample'),
    ]) {
        const files2 = (0, vsumFiles_1.collectVsumFiles)(candidate);
        if (files2.length > 0)
            return files2;
    }
    // 3. Broader search: any folder named vsumtest, vsumexample, or vsum-storage
    const broader = await vscode.workspace.findFiles('{**/vsumtest/**,**/vsumexample/**,**/vsum-storage/**}', '**/target/**');
    if (broader.length > 0)
        return broader.map(u => u.fsPath);
    // Fallback: legacy instances/ folder relative to constraint file
    const searchRoot = constraintFileUri ? path.dirname(constraintFileUri.fsPath) : workspaceRoot;
    const instancesPath = path.join(searchRoot, 'instances');
    if (fs.existsSync(instancesPath)) {
        return fs.readdirSync(instancesPath)
            .map(f => path.join(instancesPath, f))
            .filter(f => fs.statSync(f).isFile());
    }
    return [];
}
//# sourceMappingURL=discovery.js.map