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
 */
const vscode = __importStar(require("vscode"));
const units_1 = require("./units");
const violations_1 = require("./violations");
function svgIcon(svg) {
    return vscode.Uri.parse('data:image/svg+xml;utf8,' + encodeURIComponent(svg));
}
function createRunIcon() {
    return svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="none" stroke="#858585" stroke-width="1.5"/>
            <path d="M 6 5 L 6 11 L 11 8 Z" fill="#858585"/>
        </svg>
    `);
}
function createPassIcon() {
    return svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#73C991"/>
            <path d="M 5 8 L 7 10 L 11 6" stroke="white" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
    `);
}
function createFailIcon() {
    return svgIcon(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#F48771"/>
            <path d="M 5.5 5.5 L 10.5 10.5 M 10.5 5.5 L 5.5 10.5" stroke="white" stroke-width="2" stroke-linecap="round"/>
        </svg>
    `);
}
function iconFor(state) {
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
function violationHover(constraintName, details) {
    const hover = new vscode.MarkdownString();
    if (details && details.length > 0) {
        for (const d of details) {
            const { severity, message } = (0, violations_1.parseViolationBlock)(d);
            hover.appendMarkdown(`${(0, violations_1.styleOf)(severity).symbol} **${severity}:** `);
            hover.appendText(message);
            hover.appendMarkdown('\n\n');
        }
    }
    else {
        hover.appendMarkdown('⚠ **WARNING:** ');
        hover.appendText(`${constraintName} violated`);
    }
    return hover;
}
/** The decoration types currently set, so that they can be disposed of when replaced. */
class ConstraintDecorations {
    constructor(results) {
        this.results = results;
        this.current = new Map();
    }
    /** Sets the gutter icon of every constraint header. */
    updateGutterIcons(editor) {
        this.current.forEach((decoration, key) => {
            if (key.startsWith('constraint_')) {
                decoration.dispose();
            }
        });
        for (const header of (0, units_1.findConstraintHeaders)(editor.document.getText())) {
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
    updateInlineErrors(editor, constraintDetails) {
        this.current.get('inline_errors')?.dispose();
        const lines = editor.document.getText().split('\n');
        const inlineDecorations = [];
        for (const header of (0, units_1.findConstraintHeaders)(editor.document.getText())) {
            if (!this.results.hasFailed(header.name)) {
                continue;
            }
            const lineLength = lines[header.line].length;
            const details = constraintDetails?.get(header.name);
            const inline = (0, violations_1.inlineSummary)(header.name, details);
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
    update(editor, constraintDetails) {
        this.updateGutterIcons(editor);
        this.updateInlineErrors(editor, constraintDetails);
    }
    dispose() {
        this.current.forEach(decoration => decoration.dispose());
    }
}
exports.ConstraintDecorations = ConstraintDecorations;
//# sourceMappingURL=decorations.js.map