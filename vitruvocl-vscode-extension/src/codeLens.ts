/**
 * The run buttons above the constraints: "Run All" above the first `context`, and one per
 * constraint header.
 */
import * as vscode from 'vscode';
import { findConstraintHeaders, firstContextLine } from './units';

export class OCLCodeLensProvider implements vscode.CodeLensProvider {
    private _onDidChangeCodeLenses = new vscode.EventEmitter<void>();
    public readonly onDidChangeCodeLenses = this._onDidChangeCodeLenses.event;

    refresh() {
        this._onDidChangeCodeLenses.fire();
    }

    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const codeLenses: vscode.CodeLens[] = [];
        const text = document.getText();
        const lines = text.split('\n');

        const runAllLine = firstContextLine(text);
        if (runAllLine >= 0) {
            codeLenses.push(new vscode.CodeLens(
                new vscode.Range(runAllLine, 0, runAllLine, 0),
                {
                    title: '▶ Run All',
                    command: 'vitruvocl.runAllConstraints'
                }
            ));
        }

        for (const header of findConstraintHeaders(text)) {
            codeLenses.push(new vscode.CodeLens(
                new vscode.Range(header.line, 0, header.line, lines[header.line].length),
                {
                    title: '▶',
                    command: 'vitruvocl.runConstraint',
                    arguments: [header.name, document.uri]
                }
            ));
        }

        return codeLenses;
    }
}
