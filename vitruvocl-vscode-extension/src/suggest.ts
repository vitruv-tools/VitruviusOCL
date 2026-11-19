/**
 * Opening the suggestion widget for annotations after a new line below a constraint header.
 */
import * as vscode from 'vscode';
import { isInAnnotationZone } from './units';

/**
 * After the user presses Enter on an 'inv/pre/post ...:' line, automatically opens
 * the suggestion widget on the new blank/indented line below.
 */
export function triggerSuggestAfterInvNewline(
    editor: vscode.TextEditor,
    event: vscode.TextDocumentChangeEvent
): void {
    for (const change of event.contentChanges) {
        if (!change.text.includes('\n')) continue;

        const newLine = change.range.start.line + 1;
        const doc = editor.document;
        if (newLine >= doc.lineCount) continue;

        // The new line must be empty / whitespace only.
        const newLineText = doc.lineAt(newLine).text;
        if (newLineText.trim() !== '') continue;

        // Only suggest annotations when every non-blank line between here and the
        // 'inv/pre/post ...:' header is itself an annotation line.
        if (!isInAnnotationZone(i => doc.lineAt(i).text, newLine)) continue;

        // Move cursor to end of indentation and fire suggest.
        const indentEnd = newLineText.length;
        const pos = new vscode.Position(newLine, indentEnd);
        editor.selection = new vscode.Selection(pos, pos);
        vscode.commands.executeCommand('editor.action.triggerSuggest');
        break;
    }
}
