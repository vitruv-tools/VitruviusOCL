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
exports.ConstraintRunner = void 0;
/**
 * The commands that run constraints with the CLI: one constraint, or all of a document.
 */
const vscode = __importStar(require("vscode"));
const fs = __importStar(require("fs"));
const cliRunner_1 = require("./cliRunner");
const diagnostics_1 = require("./diagnostics");
const discovery_1 = require("./discovery");
const output_1 = require("./output");
const units_1 = require("./units");
const vsumFiles_1 = require("./vsumFiles");
class ConstraintRunner {
    constructor(results, decorations, codeLensProvider, diagnosticCollection) {
        this.results = results;
        this.decorations = decorations;
        this.codeLensProvider = codeLensProvider;
        this.diagnosticCollection = diagnosticCollection;
    }
    /**
     * Finds the compiler jar, the metamodels and the VSUM, telling the user what is missing.
     *
     * @returns the inputs, or `undefined` if one is missing
     */
    async findCliInputs(documentUri) {
        const compilerPath = await (0, discovery_1.findCompilerJar)();
        if (!compilerPath) {
            vscode.window.showErrorMessage('vitruvocl.jar not found');
            return undefined;
        }
        const ecoreFiles = await (0, discovery_1.findEcoreFiles)(documentUri);
        const instanceFiles = await (0, discovery_1.findInstanceFiles)(documentUri);
        if (ecoreFiles.length === 0) {
            vscode.window.showWarningMessage('No .ecore metamodel files found in workspace.');
            return undefined;
        }
        if (!(0, vsumFiles_1.containsVsum)(instanceFiles)) {
            vscode.window.showErrorMessage('No VSUM found. Run VSUMExample.main() from your IDE to create one, then try again.');
            return undefined;
        }
        return { compilerPath, ecoreFiles, instanceFiles };
    }
    /** Runs all constraints of the active `.ocl` document with `eval-batch`. */
    async runAllConstraints() {
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== 'vitruvocl') {
            vscode.window.showErrorMessage('No vitruvocl file open');
            return;
        }
        const document = editor.document;
        this.results.clear();
        this.diagnosticCollection.clear();
        this.codeLensProvider.refresh();
        this.decorations.update(editor);
        try {
            const inputs = await this.findCliInputs(document.uri);
            if (!inputs) {
                return;
            }
            vscode.window.showInformationMessage('Running all constraints...');
            const { stdout, stderr } = await (0, cliRunner_1.runJava)((0, cliRunner_1.evalBatchArgs)(inputs.compilerPath, document.fileName, inputs.ecoreFiles, inputs.instanceFiles));
            // Surface CLI stderr in the output channel so debug prints are visible
            (0, output_1.logStderr)(stderr);
            const result = JSON.parse(stdout);
            if (!result.success) {
                vscode.window.showErrorMessage('Batch evaluation failed');
                return;
            }
            let passed = 0;
            let failed = 0;
            const constraintDetails = new Map();
            for (const constraint of result.constraints) {
                this.results.record(constraint.name, constraint.satisfied);
                (0, diagnostics_1.updateDiagnosticsForConstraintBatch)(this.diagnosticCollection, document, constraint);
                if (constraint.satisfied) {
                    passed++;
                }
                else {
                    failed++;
                    if (constraint.warnings) {
                        constraintDetails.set(constraint.name, constraint.warnings);
                    }
                }
            }
            this.codeLensProvider.refresh();
            this.decorations.update(editor, constraintDetails);
            (0, output_1.showSummaryResult)(passed, failed, result.constraints);
        }
        catch (error) {
            vscode.window.showErrorMessage(`Run all failed: ${error.message}`);
        }
    }
    /** Runs one constraint of the document with `eval`, from a temporary file holding only it. */
    async runConstraint(constraintName, documentUri) {
        const document = await vscode.workspace.openTextDocument(documentUri);
        const editor = vscode.window.visibleTextEditors.find(e => e.document === document);
        try {
            const inputs = await this.findCliInputs(documentUri);
            if (!inputs) {
                return;
            }
            const singleConstraint = (0, units_1.extractConstraint)(document.getText(), constraintName);
            if (!singleConstraint) {
                vscode.window.showErrorMessage(`Constraint not found: ${constraintName}`);
                return;
            }
            const tempFile = (0, cliRunner_1.writeTempConstraint)(singleConstraint);
            vscode.window.showInformationMessage(`Running: ${constraintName}...`);
            const { stdout, stderr } = await (0, cliRunner_1.runJava)((0, cliRunner_1.evalArgs)(inputs.compilerPath, tempFile, inputs.ecoreFiles, inputs.instanceFiles));
            (0, output_1.logStderr)(stderr);
            if (!stdout.trim()) {
                const hint = stderr ? `\n\nStderr:\n${stderr.trim()}` : '';
                throw new Error(`Java process produced no output.${hint}`);
            }
            const result = JSON.parse(stdout);
            this.results.record(constraintName, result.satisfied);
            (0, diagnostics_1.updateDiagnosticsForConstraint)(this.diagnosticCollection, document, constraintName, result);
            this.codeLensProvider.refresh();
            if (editor) {
                const details = new Map();
                if (!result.satisfied && result.warnings) {
                    details.set(constraintName, result.warnings);
                }
                this.decorations.update(editor, details);
            }
            (0, output_1.showEvalResult)(constraintName, result);
            (0, output_1.showSkipNotice)(constraintName, result.warnings);
            fs.unlinkSync(tempFile);
        }
        catch (error) {
            vscode.window.showErrorMessage(`Run failed: ${error.message}`);
            this.results.record(constraintName, false);
            this.codeLensProvider.refresh();
            if (editor) {
                this.decorations.update(editor);
            }
        }
    }
}
exports.ConstraintRunner = ConstraintRunner;
//# sourceMappingURL=constraintRunner.js.map