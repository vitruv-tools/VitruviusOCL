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
exports.ConstraintDecorations = void 0;
/**
 * The marks on constraint headers: a gutter icon for whether the constraint has run and passed,
 * and the first violation at the end of a failed constraint's header line.
 *
 * The decoration types are created once and reused: an update only sets new ranges, so typing
 * does not create and dispose of decoration types on every keystroke.
 */
const vscode = __importStar(require("vscode"));
const decorationPlan_1 = require("./decorationPlan");
const output_1 = require("./output");
function svgIcon(svg) {
    return vscode.Uri.parse('data:image/svg+xml;utf8,' + encodeURIComponent(svg));
}
const RUN_ICON = svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="none" stroke="#858585" stroke-width="1.5"/>
            <path d="M 6 5 L 6 11 L 11 8 Z" fill="#858585"/>
        </svg>
    `);
const PASS_ICON = svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#73C991"/>
            <path d="M 5 8 L 7 10 L 11 6" stroke="white" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
    `);
const FAIL_ICON = svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#F48771"/>
            <path d="M 5.5 5.5 L 10.5 10.5 M 10.5 5.5 L 5.5 10.5" stroke="white" stroke-width="2" stroke-linecap="round"/>
        </svg>
    `);
function gutterType(icon) {
    return vscode.window.createTextEditorDecorationType({ gutterIconPath: icon, gutterIconSize: 'contain' });
}
/**
 * The hover of a failed constraint. Messages can contain model data, so they are appended as
 * plain text and the hover is neither trusted nor allowed to render HTML: no command links, no
 * markup injection.
 */
function hoverOf(entries) {
    const hover = new vscode.MarkdownString();
    for (const entry of entries) {
        hover.appendMarkdown(`${entry.symbol} **${entry.severity}:** `);
        hover.appendText(entry.message);
        hover.appendMarkdown('\n\n');
    }
    return hover;
}
function lineRanges(lines) {
    return lines.map(line => new vscode.Range(line, 0, line, 0));
}
/** Owns the four decoration types; an update only sets their ranges. */
class ConstraintDecorations {
    constructor(results, units) {
        this.results = results;
        this.units = units;
        this.notRun = gutterType(RUN_ICON);
        this.passed = gutterType(PASS_ICON);
        this.failed = gutterType(FAIL_ICON);
        this.inline = vscode.window.createTextEditorDecorationType({});
    }
    /**
     * Sets the gutter icon of every constraint header, and shows the first violation at the end
     * of every failed constraint's header line, all of them on hover. If the document changes
     * before its layout is known, the marks are left as they are (VS Code moves them with the
     * text) until the update for the change.
     */
    async update(editor) {
        const document = editor.document;
        const layout = await this.units.layoutOf(document);
        if (!layout) {
            return;
        }
        const lines = document.getText().split(/\r?\n/);
        const plan = (0, decorationPlan_1.planDecorations)(layout.headers, lines, this.results);
        editor.setDecorations(this.notRun, lineRanges(plan.notRun));
        editor.setDecorations(this.passed, lineRanges(plan.passed));
        editor.setDecorations(this.failed, lineRanges(plan.failed));
        editor.setDecorations(this.inline, plan.inline.map(mark => ({
            range: new vscode.Range(mark.line, mark.column, mark.line, mark.column),
            hoverMessage: hoverOf(mark.hover),
            renderOptions: {
                after: {
                    contentText: mark.text,
                    color: mark.color,
                    margin: '0 0 0 20px'
                }
            }
        })));
    }
    /**
     * Updates every visible `.ocl` editor, so that editors beside the active one, or a second
     * editor of the same document, do not keep stale marks.
     *
     * @param document if given, only the editors showing it
     */
    async updateVisible(document) {
        try {
            await Promise.all(vscode.window.visibleTextEditors
                .filter(editor => editor.document.languageId === 'vitruvocl')
                .filter(editor => !document || editor.document === document)
                .map(editor => this.update(editor)));
        }
        catch (error) {
            (0, output_1.logLine)(`[VitruvOCL] could not update the constraint marks: ${error instanceof Error ? error.message : String(error)}`);
        }
    }
    dispose() {
        [this.notRun, this.passed, this.failed, this.inline].forEach(type => type.dispose());
    }
}
exports.ConstraintDecorations = ConstraintDecorations;
//# sourceMappingURL=decorations.js.map