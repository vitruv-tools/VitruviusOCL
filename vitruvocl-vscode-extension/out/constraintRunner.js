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
const cliRunner_1 = require("./cliRunner");
const constraintLayout_1 = require("./constraintLayout");
const diagnostics_1 = require("./diagnostics");
const discovery_1 = require("./discovery");
const output_1 = require("./output");
const violations_1 = require("./violations");
const vsumFiles_1 = require("./vsumFiles");
/**
 * How often the language server is asked for the layout of a document that keeps changing before
 * it answers; then the extension's own scan finds the constraints instead.
 */
const LAYOUT_ATTEMPTS = 2;
class ConstraintRunner {
    constructor(results, decorations, codeLensProvider, diagnosticCollection, units, 
    /** Where the choice among several equally fitting VSUM folders is remembered. */
    workspaceState, projectMetamodels) {
        this.results = results;
        this.decorations = decorations;
        this.codeLensProvider = codeLensProvider;
        this.diagnosticCollection = diagnosticCollection;
        this.units = units;
        this.workspaceState = workspaceState;
        this.projectMetamodels = projectMetamodels;
    }
    /**
     * The layout of the document as it is now. If it keeps changing while the language server
     * answers, the extension's own scan finds the constraints instead.
     */
    async currentLayout(document) {
        for (let attempt = 0; attempt < LAYOUT_ATTEMPTS; attempt++) {
            const layout = await this.units.layoutOf(document);
            if (layout) {
                return layout;
            }
        }
        return (0, constraintLayout_1.layoutFromText)(document.getText());
    }
    /**
     * How the error lines of each `eval-batch` result map to the document: through the unit the
     * result belongs to, if the units of the saved document line up with the results; else onto
     * the header of the invariant of the result's name.
     *
     * @param units the units of the document, or `undefined` if unknown or not saved
     * @param layout the layout of the document as the editor has it
     */
    static batchLineMappers(units, layout, result) {
        const aligned = units && (0, constraintLayout_1.alignBatchResults)(units, result.constraints.map(c => c.name));
        return result.constraints.map((constraint, i) => aligned ? (line) => (0, constraintLayout_1.unitDocumentLine)(aligned[i], line) : (0, constraintLayout_1.headerLineMapper)(layout, constraint.name));
    }
    /**
     * Finds the compiler jar, the metamodels and the VSUM, telling the user what is missing.
     *
     * @returns the inputs, or `undefined` if one is missing
     */
    async findCliInputs(document) {
        const compilerPath = await (0, discovery_1.findCompilerJar)();
        if (!compilerPath) {
            vscode.window.showErrorMessage('vitruvocl.jar not found');
            return undefined;
        }
        const instanceFiles = await (0, discovery_1.findInstanceFiles)(document, this.workspaceState);
        if (instanceFiles === undefined) {
            vscode.window.showInformationMessage('VitruvOCL: no VSUM chosen, the constraints were not run.');
            return undefined;
        }
        if (!(0, vsumFiles_1.containsVsum)(instanceFiles)) {
            vscode.window.showErrorMessage('No VSUM found. Run VSUMExample.main() from your IDE to create one, then try again.');
            return undefined;
        }
        const workspaceEcores = await (0, discovery_1.findEcoreFiles)(document.uri);
        // the metamodels the project gets from its Maven dependencies, e.g. PCM
        const ecoreFiles = [
            ...workspaceEcores,
            ...await this.projectMetamodels.forRun(document, workspaceEcores, instanceFiles),
        ];
        if (ecoreFiles.length === 0) {
            vscode.window.showWarningMessage('No .ecore metamodel files found in workspace.');
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
        void this.decorations.updateVisible();
        try {
            const inputs = await this.findCliInputs(document);
            if (!inputs) {
                return;
            }
            vscode.window.showInformationMessage('Running all constraints...');
            // eval-batch reads the saved file: the units of the editor's text only fit it if saved
            const layout = await this.currentLayout(document);
            const units = document.isDirty ? undefined : await this.units.unitsOf(document);
            const { stdout, stderr } = await (0, cliRunner_1.runJava)((0, cliRunner_1.evalBatchArgs)(inputs.compilerPath, document.fileName, inputs.ecoreFiles, inputs.instanceFiles));
            // Surface CLI stderr in the output channel so debug prints are visible
            (0, output_1.logStderr)(stderr);
            if (!stdout.trim()) {
                const hint = stderr ? `\n\nStderr:\n${stderr.trim()}` : '';
                throw new Error(`Java process produced no output.${hint}`);
            }
            const result = JSON.parse(stdout);
            if (!result.success) {
                const reason = (0, cliRunner_1.cliFailure)(result);
                vscode.window.showErrorMessage(reason ? `Batch evaluation failed: ${reason}` : 'Batch evaluation failed');
                (0, output_1.logLine)(`[CLI] eval-batch failed: ${reason ?? stdout.trim()}`);
                return;
            }
            let passed = 0;
            let failed = 0;
            const lineMappers = ConstraintRunner.batchLineMappers(units, layout, result);
            result.constraints.forEach((constraint, i) => {
                this.results.record(constraint.name, constraint.satisfied, (0, violations_1.violationsOf)(constraint.warnings));
                (0, diagnostics_1.updateDiagnosticsForConstraintBatch)(this.diagnosticCollection, document, constraint, lineMappers[i]);
                if (constraint.satisfied) {
                    passed++;
                }
                else {
                    failed++;
                }
            });
            this.codeLensProvider.refresh();
            void this.decorations.updateVisible();
            (0, output_1.showSummaryResult)(passed, failed, result.constraints);
        }
        catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Run all failed: ${message}`);
            (0, output_1.logLine)(`[CLI] eval-batch was not evaluated: ${message}`);
        }
    }
    /**
     * Runs one invariant of the document with `eval`, from a temporary file holding the text the
     * CLI would evaluate for it in the whole document; its errors are mapped back to the document.
     */
    async runConstraint(constraintName, documentUri) {
        const document = await vscode.workspace.openTextDocument(documentUri);
        try {
            const inputs = await this.findCliInputs(document);
            if (!inputs) {
                return;
            }
            const layout = await this.currentLayout(document);
            const singleConstraint = layout.textOf(constraintName);
            if (!singleConstraint) {
                vscode.window.showErrorMessage(`Constraint not found: ${constraintName}`);
                return;
            }
            vscode.window.showInformationMessage(`Running: ${constraintName}...`);
            const result = await this.evaluate(singleConstraint, inputs);
            this.results.record(constraintName, result.satisfied, (0, violations_1.violationsOf)(result.warnings));
            (0, diagnostics_1.updateDiagnosticsForConstraint)(this.diagnosticCollection, document, layout, constraintName, result);
            this.codeLensProvider.refresh();
            void this.decorations.updateVisible();
            (0, output_1.showEvalResult)(constraintName, result);
            (0, output_1.showSkipNotice)(constraintName, result.warnings);
        }
        catch (error) {
            // The constraint was not evaluated: it is neither satisfied nor violated
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Run failed: ${message}`);
            (0, output_1.logLine)(`[CLI] ${constraintName} was not evaluated: ${message}`);
            this.results.forget(constraintName);
            (0, diagnostics_1.clearDiagnosticsOf)(this.diagnosticCollection, document, constraintName);
            this.codeLensProvider.refresh();
            void this.decorations.updateVisible();
        }
    }
    /**
     * Evaluates one constraint with `eval`, from a temporary file that is deleted afterwards.
     *
     * @returns the result, whether satisfied, violated or not compiling
     * @throws Error if the CLI printed nothing or failed as a whole, with its reason
     */
    async evaluate(constraint, inputs) {
        const tempFile = (0, cliRunner_1.writeTempConstraint)(constraint);
        try {
            const { stdout, stderr } = await (0, cliRunner_1.runJava)((0, cliRunner_1.evalArgs)(inputs.compilerPath, tempFile, inputs.ecoreFiles, inputs.instanceFiles));
            (0, output_1.logStderr)(stderr);
            if (!stdout.trim()) {
                const hint = stderr ? `\n\nStderr:\n${stderr.trim()}` : '';
                throw new Error(`Java process produced no output.${hint}`);
            }
            const result = JSON.parse(stdout);
            const failure = (0, cliRunner_1.cliFailure)(result);
            if (failure) {
                throw new Error(failure);
            }
            return result;
        }
        finally {
            (0, cliRunner_1.deleteTempConstraint)(tempFile);
        }
    }
}
exports.ConstraintRunner = ConstraintRunner;
//# sourceMappingURL=constraintRunner.js.map