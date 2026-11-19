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
exports.activate = activate;
exports.deactivate = deactivate;
const vscode = __importStar(require("vscode"));
const codeLens_1 = require("./codeLens");
const decorations_1 = require("./decorations");
const languageClient_1 = require("./languageClient");
const resultStore_1 = require("./resultStore");
const constraintRunner_1 = require("./constraintRunner");
const suggest_1 = require("./suggest");
let decorations;
let languageClient;
async function applyThemeOnFirstInstall(context) {
    const THEME_KEY = 'vitruvocl.themeApplied';
    const alreadyApplied = context.globalState.get(THEME_KEY, false);
    if (alreadyApplied) {
        return;
    }
    const config = vscode.workspace.getConfiguration();
    const currentTheme = config.get('workbench.colorTheme');
    if (currentTheme === 'MultiModelOCL Dark') {
        await context.globalState.update(THEME_KEY, true);
        return;
    }
    const answer = await vscode.window.showInformationMessage('VitruvOCL: Apply the included "MultiModelOCL Dark" theme for optimized syntax highlighting?', 'Yes', 'No');
    if (answer === 'Yes') {
        await config.update('workbench.colorTheme', 'MultiModelOCL Dark', vscode.ConfigurationTarget.Global);
    }
    await context.globalState.update(THEME_KEY, true);
}
function activate(context) {
    applyThemeOnFirstInstall(context);
    const diagnosticCollection = vscode.languages.createDiagnosticCollection('vitruvocl');
    context.subscriptions.push(diagnosticCollection);
    const results = new resultStore_1.ResultStore();
    const codeLensProvider = new codeLens_1.OCLCodeLensProvider();
    const constraintDecorations = new decorations_1.ConstraintDecorations(results);
    decorations = constraintDecorations;
    const runner = new constraintRunner_1.ConstraintRunner(results, constraintDecorations, codeLensProvider, diagnosticCollection);
    context.subscriptions.push(vscode.languages.registerCodeLensProvider({ language: 'vitruvocl' }, codeLensProvider));
    context.subscriptions.push(vscode.commands.registerCommand('vitruvocl.runConstraint', async (constraintName, documentUri) => {
        await runner.runConstraint(constraintName, documentUri);
    }));
    context.subscriptions.push(vscode.commands.registerCommand('vitruvocl.runAllConstraints', async () => {
        await runner.runAllConstraints();
    }));
    context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(editor => {
        if (editor && editor.document.languageId === 'vitruvocl') {
            constraintDecorations.update(editor);
        }
    }));
    context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(event => {
        const editor = vscode.window.activeTextEditor;
        if (editor && editor.document === event.document && editor.document.languageId === 'vitruvocl') {
            constraintDecorations.update(editor);
            (0, suggest_1.triggerSuggestAfterInvNewline)(editor, event);
        }
    }));
    if (vscode.window.activeTextEditor?.document.languageId === 'vitruvocl') {
        constraintDecorations.update(vscode.window.activeTextEditor);
    }
    languageClient = (0, languageClient_1.startLanguageClient)(context);
}
function deactivate() {
    decorations?.dispose();
    return languageClient?.stop();
}
//# sourceMappingURL=extension.js.map