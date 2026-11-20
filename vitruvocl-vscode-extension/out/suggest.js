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
exports.triggerSuggestAfterInvNewline = triggerSuggestAfterInvNewline;
/**
 * Opening the suggestion widget for annotations after a new line below a constraint header.
 */
const vscode = __importStar(require("vscode"));
const units_1 = require("./units");
/**
 * After the user presses Enter on an 'inv/pre/post ...:' line, automatically opens
 * the suggestion widget on the new blank/indented line below.
 */
function triggerSuggestAfterInvNewline(editor, event) {
    for (const change of event.contentChanges) {
        if (!change.text.includes('\n'))
            continue;
        const newLine = change.range.start.line + 1;
        const doc = editor.document;
        if (newLine >= doc.lineCount)
            continue;
        // The new line must be empty / whitespace only.
        const newLineText = doc.lineAt(newLine).text;
        if (newLineText.trim() !== '')
            continue;
        // Only suggest annotations when every non-blank line between here and the
        // 'inv/pre/post ...:' header is itself an annotation line.
        if (!(0, units_1.isInAnnotationZone)(i => doc.lineAt(i).text, newLine))
            continue;
        // Move cursor to end of indentation and fire suggest.
        const indentEnd = newLineText.length;
        const pos = new vscode.Position(newLine, indentEnd);
        editor.selection = new vscode.Selection(pos, pos);
        vscode.commands.executeCommand('editor.action.triggerSuggest');
        break;
    }
}
//# sourceMappingURL=suggest.js.map