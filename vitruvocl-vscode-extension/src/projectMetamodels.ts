/**
 * The metamodels of a project's Maven dependencies, for the CLI's runs and for the language
 * server, which gets every one it does not know yet.
 */
import * as vscode from 'vscode';
import { DependencyEcores } from './dependencyEcores';
import { findEcoreFiles, workspaceRootOf } from './discovery';
import { MavenMode } from './mavenClasspath';

/** The setting `vitruvocl.mavenDependencies`: how far Maven may go to find them. */
type MavenSetting = 'online' | 'offline' | 'off';

function mavenSetting(): MavenSetting {
    const value = vscode.workspace.getConfiguration('vitruvocl').get<string>('mavenDependencies');
    return value === 'offline' || value === 'off' ? value : 'online';
}

export class ProjectMetamodels {
    constructor(
        private readonly dependencies: DependencyEcores,
        /** Passes metamodels to the language server. */
        private readonly addToLanguageServer: (paths: string[]) => Promise<void>,
        private readonly log: (line: string) => void,
    ) {}

    /**
     * The metamodels from the dependencies that a run of the document's constraints on the model
     * files needs; the language server gets them too. Maven may download what the local
     * repository lacks unless `vitruvocl.mavenDependencies` says otherwise, which can take a
     * while the first time; the status bar shows it.
     */
    async forRun(document: vscode.TextDocument, workspaceEcores: string[], modelFiles: string[]): Promise<string[]> {
        const setting = mavenSetting();
        return setting === 'off' ? [] : this.resolve(document, workspaceEcores, modelFiles, setting);
    }

    /**
     * Gives the language server the metamodels from the dependencies that the document's
     * constraints refer to, so that it knows their types. Only the local Maven repository is used:
     * opening a document never downloads anything.
     */
    async forEditor(document: vscode.TextDocument): Promise<void> {
        if (document.languageId !== 'vitruvocl' || document.uri.scheme !== 'file' || mavenSetting() === 'off') return;
        await this.resolve(document, await findEcoreFiles(document.uri), [], 'offline');
    }

    /** Resolves the dependencies again on the next run, e.g. after `mvn install`. */
    forget(): void {
        this.dependencies.forget();
    }

    private async resolve(
        document: vscode.TextDocument,
        workspaceEcores: string[],
        modelFiles: string[],
        mode: MavenMode
    ): Promise<string[]> {
        const workspaceRoot = workspaceRootOf(document.uri);
        if (!workspaceRoot) return [];
        const ecores = await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Window, title: 'VitruvOCL: metamodels of the Maven dependencies' },
            () => this.dependencies.ecoresFor({
                constraintFile: document.uri.fsPath,
                constraintsText: document.getText(),
                workspaceRoot,
                workspaceEcores,
                modelFiles,
            }, mode));
        if (ecores.length > 0) {
            this.log(`[Maven] metamodels from the dependencies: ${ecores.map(file => vscode.workspace.asRelativePath(file)).join(', ')}`);
        }
        await this.addToLanguageServer(ecores);
        return ecores;
    }
}
