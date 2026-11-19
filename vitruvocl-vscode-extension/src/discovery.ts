/**
 * Finding what the CLI needs in the workspace: the compiler jar, the metamodels and the model
 * instances of a VSUM.
 */
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import {
    bestVsumCandidates,
    collectVsumFiles,
    metamodelNamesIn,
    VsumCandidate,
    vsumRootOf,
} from './vsumFiles';
import { logLine } from './output';

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

/** The workspace folder of the constraint file, or the first one. */
export function workspaceRootOf(constraintFileUri?: vscode.Uri): string | undefined {
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
export async function findEcoreFiles(constraintFileUri?: vscode.Uri): Promise<string[]> {
    const workspaceRoot = workspaceRootOf(constraintFileUri);
    if (!workspaceRoot) return [];
    const files = await vscode.workspace.findFiles(
        new vscode.RelativePattern(workspaceRoot, '**/*.ecore'), ECORE_EXCLUDE);
    return [...new Set(files.map(u => path.normalize(u.fsPath)))].sort();
}

/** Where the VSUM chosen among several equally fitting ones is remembered, per workspace folder. */
const CHOSEN_VSUM_KEY = 'vitruvocl.chosenVsum:';

/** Forgets every remembered choice among VSUM folders. */
export async function forgetVsumChoices(memento: vscode.Memento): Promise<void> {
    for (const key of memento.keys().filter(k => k.startsWith(CHOSEN_VSUM_KEY))) {
        await memento.update(key, undefined);
    }
}

/** The VSUM folders of the workspace: every folder with a `vsum/*.correspondence`. */
async function findVsumCandidates(workspaceRoot: string): Promise<VsumCandidate[]> {
    const correspondences = await vscode.workspace.findFiles(
        new vscode.RelativePattern(workspaceRoot, '**/vsum/*.correspondence'), '{**/node_modules/**,**/.git/**}');
    const roots = [...new Set(correspondences
        .map(u => vsumRootOf(u.fsPath))
        .filter((root): root is string => root !== undefined))];
    return roots.sort().map(root => ({ root, files: collectVsumFiles(root) }));
}

/**
 * Lets the user choose among VSUM folders that fit the constraints equally well, and remembers
 * the choice for the workspace folder.
 */
async function chooseVsum(
    candidates: VsumCandidate[],
    workspaceRoot: string,
    memento: vscode.Memento
): Promise<VsumCandidate | undefined> {
    const key = CHOSEN_VSUM_KEY + workspaceRoot;
    const remembered = memento.get<string>(key);
    const known = candidates.find(candidate => candidate.root === remembered);
    if (known) return known;

    const picked = await vscode.window.showQuickPick(
        candidates.map(candidate => ({
            label: path.relative(workspaceRoot, candidate.root) || '.',
            description: `${candidate.files.length} model files`,
            candidate,
        })),
        { placeHolder: 'Several VSUMs fit these constraints: which one should they be checked against?' }
    );
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
export async function findInstanceFiles(document: vscode.TextDocument, memento: vscode.Memento): Promise<string[] | undefined> {
    const workspaceRoot = workspaceRootOf(document.uri);
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

    // 1. The VSUM folder whose models fit the metamodels the constraints use
    const best = bestVsumCandidates(await findVsumCandidates(workspaceRoot), metamodelNamesIn(document.getText()));
    const chosen = best.length > 1 ? await chooseVsum(best, workspaceRoot, memento) : best[0];
    if (chosen) {
        logLine(`[VSUM] ${path.relative(workspaceRoot, chosen.root) || '.'} (${chosen.files.length} model files)`);
        return chosen.files;
    }
    if (best.length > 1) return undefined; // the user cancelled the choice

    // 2. Legacy instances/ folder relative to the constraint file
    const instancesPath = path.join(path.dirname(document.uri.fsPath), 'instances');
    if (fs.existsSync(instancesPath)) {
        return fs.readdirSync(instancesPath)
            .map(f => path.join(instancesPath, f))
            .filter(f => fs.statSync(f).isFile());
    }

    return [];
}
