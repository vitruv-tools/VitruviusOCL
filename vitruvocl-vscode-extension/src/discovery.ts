/**
 * Finding what the CLI needs in the workspace: the compiler jar, the metamodels and the model
 * instances of a VSUM.
 */
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { collectVsumFiles } from './vsumFiles';

/** The configured or bundled `vitruvOCL.jar`, or `null`. */
export async function findCompilerJar(): Promise<string | null> {
    const config = vscode.workspace.getConfiguration('vitruvocl');
    const configured = config.get<string>('compilerPath');
    if (configured && fs.existsSync(configured)) return configured;

    const extPath = vscode.extensions.getExtension('vitruvocl.vitruvocl')?.extensionPath;
    if (extPath) {
        const bundled = path.join(extPath, 'lib', 'vitruvOCL.jar');
        if (fs.existsSync(bundled)) return bundled;
    }

    return null;
}

/** The configured or bundled `language-server.jar`, or `null`. */
export function findLanguageServerJar(context: vscode.ExtensionContext): string | null {
    const config = vscode.workspace.getConfiguration('vitruvocl');
    const configured = config.get<string>('languageServerPath');
    if (configured && fs.existsSync(configured)) return configured;

    const bundled = path.join(context.extensionPath, 'lib', 'language-server.jar');
    if (fs.existsSync(bundled)) return bundled;

    return null;
}

function workspaceRootOf(constraintFileUri?: vscode.Uri): string | undefined {
    return constraintFileUri
        ? vscode.workspace.getWorkspaceFolder(constraintFileUri)?.uri.fsPath
        : vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
}

/** The `.ecore` files: those in `ecore/` at the workspace root, else all in the workspace. */
export async function findEcoreFiles(constraintFileUri?: vscode.Uri): Promise<string[]> {
    const workspaceRoot = workspaceRootOf(constraintFileUri);
    if (!workspaceRoot) return [];

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
export async function findInstanceFiles(constraintFileUri?: vscode.Uri): Promise<string[]> {
    const workspaceRoot = workspaceRootOf(constraintFileUri);
    if (!workspaceRoot) return [];

    // 0. User-configured VSUM path (vitruvocl.vsumPath setting)
    const config = vscode.workspace.getConfiguration('vitruvocl');
    const configuredPath: string | undefined = config.get('vsumPath');
    if (configuredPath) {
        const resolved = path.isAbsolute(configuredPath)
            ? configuredPath
            : path.join(workspaceRoot, configuredPath);
        const files = collectVsumFiles(resolved);
        if (files.length > 0) return files;
    }

    // 1. Standard Maven test VSUM: vsum/target/vsumtest/
    const vsumBase = path.join(workspaceRoot, 'vsum', 'target', 'vsumtest');
    const files1 = collectVsumFiles(vsumBase);
    if (files1.length > 0) return files1;

    // 2. VSUMExample default: vsumexample/ at workspace root or inside vsum/
    for (const candidate of [
        path.join(workspaceRoot, 'vsumexample'),
        path.join(workspaceRoot, 'vsum', 'vsumexample'),
    ]) {
        const files2 = collectVsumFiles(candidate);
        if (files2.length > 0) return files2;
    }

    // 3. Broader search: any folder named vsumtest, vsumexample, or vsum-storage
    const broader = await vscode.workspace.findFiles(
        '{**/vsumtest/**,**/vsumexample/**,**/vsum-storage/**}',
        '**/target/**'
    );
    if (broader.length > 0) return broader.map(u => u.fsPath);

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
