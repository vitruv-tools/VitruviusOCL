import * as vscode from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';
import { OCLCodeLensProvider } from './codeLens';
import { ConstraintDecorations } from './decorations';
import { startLanguageClient } from './languageClient';
import { ResultStore } from './resultStore';
import { ConstraintRunner } from './constraintRunner';
import { triggerSuggestAfterInvNewline } from './suggest';

let decorations: ConstraintDecorations | undefined;
let languageClient: LanguageClient | undefined;

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

    const results = new ResultStore();
    const codeLensProvider = new OCLCodeLensProvider();
    const constraintDecorations = new ConstraintDecorations(results);
    decorations = constraintDecorations;
    const runner = new ConstraintRunner(results, constraintDecorations, codeLensProvider, diagnosticCollection);

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
        vscode.window.onDidChangeActiveTextEditor(editor => {
            if (editor && editor.document.languageId === 'vitruvocl') {
                constraintDecorations.update(editor);
            }
        })
    );

    context.subscriptions.push(
        vscode.workspace.onDidChangeTextDocument(event => {
            const editor = vscode.window.activeTextEditor;
            if (editor && editor.document === event.document && editor.document.languageId === 'vitruvocl') {
                constraintDecorations.update(editor);
                triggerSuggestAfterInvNewline(editor, event);
            }
        })
    );

    if (vscode.window.activeTextEditor?.document.languageId === 'vitruvocl') {
        constraintDecorations.update(vscode.window.activeTextEditor);
    }

    languageClient = startLanguageClient(context);
}

export function deactivate(): Thenable<void> | undefined {
    decorations?.dispose();
    return languageClient?.stop();
}
