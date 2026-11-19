import * as vscode from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';
import { OCLCodeLensProvider } from './codeLens';
import { ConstraintDecorations } from './decorations';
import { startLanguageClient, unitSourceOf } from './languageClient';
import { logLine } from './output';
import { ResultStore } from './resultStore';
import { ConstraintRunner } from './constraintRunner';
import { triggerSuggestAfterInvNewline } from './suggest';
import { ConstraintUnitIndex } from './unitIndex';

let languageClient: LanguageClient | undefined;

/** How long the marks wait after a keystroke, so that typing asks the language server less often. */
const MARK_UPDATE_DELAY_MS = 200;

async function applyThemeOnFirstInstall(context: vscode.ExtensionContext): Promise<void> {
    const THEME_KEY = 'vitruvocl.themeApplied';
    const alreadyApplied = context.globalState.get<boolean>(THEME_KEY, false);
    if (alreadyApplied) {
        return;
    }
    const config = vscode.workspace.getConfiguration();
    const currentTheme = config.get<string>('workbench.colorTheme');
    if (currentTheme === 'MultiModelOCL Dark') {
        await context.globalState.update(THEME_KEY, true);
        return;
    }
    const answer = await vscode.window.showInformationMessage(
        'VitruvOCL: Apply the included "MultiModelOCL Dark" theme for optimized syntax highlighting?',
        'Yes', 'No'
    );
    if (answer === 'Yes') {
        await config.update('workbench.colorTheme', 'MultiModelOCL Dark', vscode.ConfigurationTarget.Global);
    }
    await context.globalState.update(THEME_KEY, true);
}

export function activate(context: vscode.ExtensionContext) {
    applyThemeOnFirstInstall(context);
    const diagnosticCollection = vscode.languages.createDiagnosticCollection('vitruvocl');
    context.subscriptions.push(diagnosticCollection);

    // Started first: the constraint units come from the language server if it runs
    languageClient = startLanguageClient(context);
    const units = new ConstraintUnitIndex(languageClient && unitSourceOf(languageClient), logLine);

    const results = new ResultStore();
    const codeLensProvider = new OCLCodeLensProvider(units);
    const constraintDecorations = new ConstraintDecorations(results, units);
    context.subscriptions.push(constraintDecorations);
    const runner = new ConstraintRunner(results, constraintDecorations, codeLensProvider, diagnosticCollection, units);

    context.subscriptions.push(
        vscode.languages.registerCodeLensProvider(
            { language: 'vitruvocl' },
            codeLensProvider
        )
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('vitruvocl.runConstraint', async (constraintName, documentUri) => {
            await runner.runConstraint(constraintName, documentUri);
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('vitruvocl.runAllConstraints', async () => {
            await runner.runAllConstraints();
        })
    );

    context.subscriptions.push(
        vscode.window.onDidChangeVisibleTextEditors(() => void constraintDecorations.updateVisible())
    );

    const pendingUpdates = new Map<string, ReturnType<typeof setTimeout>>();
    context.subscriptions.push(new vscode.Disposable(() => pendingUpdates.forEach(clearTimeout)));
    context.subscriptions.push(
        vscode.workspace.onDidChangeTextDocument(event => {
            if (event.document.languageId !== 'vitruvocl') {
                return;
            }
            const key = event.document.uri.toString();
            clearTimeout(pendingUpdates.get(key));
            pendingUpdates.set(key, setTimeout(() => {
                pendingUpdates.delete(key);
                void constraintDecorations.updateVisible(event.document);
            }, MARK_UPDATE_DELAY_MS));
            const editor = vscode.window.activeTextEditor;
            if (editor && editor.document === event.document) {
                triggerSuggestAfterInvNewline(editor, event);
            }
        })
    );

    context.subscriptions.push(
        vscode.workspace.onDidCloseTextDocument(document => units.forget(document))
    );

    // Found by the own scan until the language server runs, then from its units
    if (languageClient) {
        context.subscriptions.push(languageClient.onDidChangeState(() => {
            codeLensProvider.refresh();
            void constraintDecorations.updateVisible();
        }));
    }

    void constraintDecorations.updateVisible();
}

export function deactivate(): Thenable<void> | undefined {
    return languageClient?.stop();
}
