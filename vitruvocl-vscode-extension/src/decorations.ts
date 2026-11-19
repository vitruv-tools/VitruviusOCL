/**
 * The marks on constraint headers: a gutter icon for whether the constraint has run and passed,
 * and the first violation at the end of a failed constraint's header line.
 */
import * as vscode from 'vscode';
import { findConstraintHeaders } from './units';
import { ResultStore, ConstraintState } from './resultStore';
import { inlineSummary, parseViolationBlock, styleOf } from './violations';

function svgIcon(svg: string): vscode.Uri {
    return vscode.Uri.parse('data:image/svg+xml;utf8,' + encodeURIComponent(svg));
}

function createRunIcon(): vscode.Uri {
    return svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="none" stroke="#858585" stroke-width="1.5"/>
            <path d="M 6 5 L 6 11 L 11 8 Z" fill="#858585"/>
        </svg>
    `);
}

function createPassIcon(): vscode.Uri {
    return svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#73C991"/>
            <path d="M 5 8 L 7 10 L 11 6" stroke="white" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
    `);
}

function createFailIcon(): vscode.Uri {
    return svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#F48771"/>
            <path d="M 5.5 5.5 L 10.5 10.5 M 10.5 5.5 L 5.5 10.5" stroke="white" stroke-width="2" stroke-linecap="round"/>
        </svg>
    `);
}

function iconFor(state: ConstraintState): vscode.Uri {
    switch (state) {
        case 'notRun': return createRunIcon();
        case 'passed': return createPassIcon();
        case 'failed': return createFailIcon();
    }
}

/**
 * The hover of a failed constraint, listing all violations. Messages can contain model data, so
 * they are appended as plain text and the hover is neither trusted nor allowed to render HTML:
 * no command links, no markup injection.
 */
function violationHover(constraintName: string, details: string[] | undefined): vscode.MarkdownString {
    const hover = new vscode.MarkdownString();
    if (details && details.length > 0) {
        for (const d of details) {
            const { severity, message } = parseViolationBlock(d);
            hover.appendMarkdown(`${styleOf(severity).symbol} **${severity}:** `);
            hover.appendText(message);
            hover.appendMarkdown('\n\n');
        }
    } else {
        hover.appendMarkdown('⚠ **WARNING:** ');
        hover.appendText(`${constraintName} violated`);
    }
    return hover;
}

/** The decoration types currently set, so that they can be disposed of when replaced. */
export class ConstraintDecorations implements vscode.Disposable {
    private readonly current = new Map<string, vscode.TextEditorDecorationType>();

    constructor(private readonly results: ResultStore) {}

    /** Sets the gutter icon of every constraint header. */
    updateGutterIcons(editor: vscode.TextEditor): void {
        this.current.forEach((decoration, key) => {
            if (key.startsWith('constraint_')) {
                decoration.dispose();
            }
        });

        for (const header of findConstraintHeaders(editor.document.getText())) {
            const decoration = vscode.window.createTextEditorDecorationType({
                gutterIconPath: iconFor(this.results.stateOf(header.name)),
                gutterIconSize: 'contain'
            });
            editor.setDecorations(decoration, [new vscode.Range(header.line, 0, header.line, 0)]);
            this.current.set(`constraint_${header.line}`, decoration);
        }
    }

    /**
     * Shows the first violation at the end of every failed constraint's header line, and all of
     * them on hover.
     *
     * @param constraintDetails the violations by constraint name; without them, a generic note
     */
    updateInlineErrors(editor: vscode.TextEditor, constraintDetails?: Map<string, string[]>): void {
        this.current.get('inline_errors')?.dispose();

        const lines = editor.document.getText().split('\n');
        const inlineDecorations: vscode.DecorationOptions[] = [];
        for (const header of findConstraintHeaders(editor.document.getText())) {
            if (!this.results.hasFailed(header.name)) {
                continue;
            }
            const lineLength = lines[header.line].length;
            const details = constraintDetails?.get(header.name);
            const inline = inlineSummary(header.name, details);
            inlineDecorations.push({
                range: new vscode.Range(header.line, lineLength, header.line, lineLength),
                hoverMessage: violationHover(header.name, details),
                renderOptions: {
                    after: {
                        contentText: inline.text,
                        color: inline.color,
                        margin: '0 0 0 20px'
                    }
                }
            });
        }

        const inlineDecorationType = vscode.window.createTextEditorDecorationType({});
        editor.setDecorations(inlineDecorationType, inlineDecorations);
        this.current.set('inline_errors', inlineDecorationType);
    }

    /** Updates both the gutter icons and the inline violations. */
    update(editor: vscode.TextEditor, constraintDetails?: Map<string, string[]>): void {
        this.updateGutterIcons(editor);
        this.updateInlineErrors(editor, constraintDetails);
    }

    dispose(): void {
        this.current.forEach(decoration => decoration.dispose());
    }
}
