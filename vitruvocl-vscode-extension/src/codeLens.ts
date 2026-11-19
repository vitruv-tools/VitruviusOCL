/**
 * The run buttons above the constraints: "Run All" above the first `context`, and one per
 * named invariant.
 */
import * as vscode from 'vscode';
import { ConstraintUnitIndex } from './unitIndex';

export class OCLCodeLensProvider implements vscode.CodeLensProvider {
    private _onDidChangeCodeLenses = new vscode.EventEmitter<void>();
    public readonly onDidChangeCodeLenses = this._onDidChangeCodeLenses.event;

    constructor(private readonly units: ConstraintUnitIndex) {}

    refresh() {
        this._onDidChangeCodeLenses.fire();
    }

    async provideCodeLenses(document: vscode.TextDocument): Promise<vscode.CodeLens[]> {
        const layout = await this.units.layoutOf(document);
        if (!layout) {
            return []; // the document changed meanwhile; VS Code asks again
        }
        const codeLenses: vscode.CodeLens[] = [];

        if (layout.runAllLine >= 0) {
            codeLenses.push(new vscode.CodeLens(
                new vscode.Range(layout.runAllLine, 0, layout.runAllLine, 0),
                {
                    title: '▶ Run All',
                    command: 'vitruvocl.runAllConstraints'
                }
            ));
        }

        for (const header of layout.headers) {
            const lineLength = header.line < document.lineCount ? document.lineAt(header.line).text.length : 0;
            codeLenses.push(new vscode.CodeLens(
                new vscode.Range(header.line, 0, header.line, lineLength),
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
