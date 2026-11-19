/**
 * The commands that run constraints with the CLI: one constraint, or all of a document.
 */
import * as vscode from 'vscode';
import * as fs from 'fs';
import { BatchEvalResult, EvalResult, evalArgs, evalBatchArgs, runJava, writeTempConstraint } from './cliRunner';
import { OCLCodeLensProvider } from './codeLens';
import { ConstraintDecorations } from './decorations';
import { updateDiagnosticsForConstraint, updateDiagnosticsForConstraintBatch } from './diagnostics';
import { findCompilerJar, findEcoreFiles, findInstanceFiles } from './discovery';
import { logStderr, showEvalResult, showSkipNotice, showSummaryResult } from './output';
import { ResultStore } from './resultStore';
import { extractConstraint } from './units';
import { containsVsum } from './vsumFiles';

/** What the CLI needs besides the constraint file. */
interface CliInputs {
    compilerPath: string;
    ecoreFiles: string[];
    instanceFiles: string[];
}

export class ConstraintRunner {
    constructor(
        private readonly results: ResultStore,
        private readonly decorations: ConstraintDecorations,
        private readonly codeLensProvider: OCLCodeLensProvider,
        private readonly diagnosticCollection: vscode.DiagnosticCollection,
    ) {}

    /**
     * Finds the compiler jar, the metamodels and the VSUM, telling the user what is missing.
     *
     * @returns the inputs, or `undefined` if one is missing
     */
    private async findCliInputs(documentUri: vscode.Uri): Promise<CliInputs | undefined> {
        const compilerPath = await findCompilerJar();
        if (!compilerPath) {
            vscode.window.showErrorMessage('vitruvocl.jar not found');
            return undefined;
        }

        const ecoreFiles = await findEcoreFiles(documentUri);
        const instanceFiles = await findInstanceFiles(documentUri);

        if (ecoreFiles.length === 0) {
            vscode.window.showWarningMessage('No .ecore metamodel files found in workspace.');
            return undefined;
        }
        if (!containsVsum(instanceFiles)) {
            vscode.window.showErrorMessage(
                'No VSUM found. Run VSUMExample.main() from your IDE to create one, then try again.'
            );
            return undefined;
        }
        return { compilerPath, ecoreFiles, instanceFiles };
    }

    /** Runs all constraints of the active `.ocl` document with `eval-batch`. */
    async runAllConstraints(): Promise<void> {
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

            const { stdout, stderr } = await runJava(
                evalBatchArgs(inputs.compilerPath, document.fileName, inputs.ecoreFiles, inputs.instanceFiles));

            // Surface CLI stderr in the output channel so debug prints are visible
            logStderr(stderr);

            const result = JSON.parse(stdout) as BatchEvalResult;

            if (!result.success) {
                vscode.window.showErrorMessage('Batch evaluation failed');
                return;
            }

            let passed = 0;
            let failed = 0;
            const constraintDetails = new Map<string, string[]>();

            for (const constraint of result.constraints) {
                this.results.record(constraint.name, constraint.satisfied);
                updateDiagnosticsForConstraintBatch(this.diagnosticCollection, document, constraint);

                if (constraint.satisfied) {
                    passed++;
                } else {
                    failed++;
                    if (constraint.warnings) {
                        constraintDetails.set(constraint.name, constraint.warnings);
                    }
                }
            }

            this.codeLensProvider.refresh();
            this.decorations.update(editor, constraintDetails);

            showSummaryResult(passed, failed, result.constraints);

        } catch (error: any) {
            vscode.window.showErrorMessage(`Run all failed: ${error.message}`);
        }
    }

    /** Runs one constraint of the document with `eval`, from a temporary file holding only it. */
    async runConstraint(constraintName: string, documentUri: vscode.Uri): Promise<void> {
        const document = await vscode.workspace.openTextDocument(documentUri);
        const editor = vscode.window.visibleTextEditors.find(e => e.document === document);

        try {
            const inputs = await this.findCliInputs(documentUri);
            if (!inputs) {
                return;
            }

            const singleConstraint = extractConstraint(document.getText(), constraintName);
            if (!singleConstraint) {
                vscode.window.showErrorMessage(`Constraint not found: ${constraintName}`);
                return;
            }

            const tempFile = writeTempConstraint(singleConstraint);

            vscode.window.showInformationMessage(`Running: ${constraintName}...`);

            const { stdout, stderr } = await runJava(
                evalArgs(inputs.compilerPath, tempFile, inputs.ecoreFiles, inputs.instanceFiles));

            logStderr(stderr);

            if (!stdout.trim()) {
                const hint = stderr ? `\n\nStderr:\n${stderr.trim()}` : '';
                throw new Error(`Java process produced no output.${hint}`);
            }

            const result = JSON.parse(stdout) as EvalResult;

            this.results.record(constraintName, result.satisfied);
            updateDiagnosticsForConstraint(this.diagnosticCollection, document, constraintName, result);

            this.codeLensProvider.refresh();
            if (editor) {
                const details = new Map<string, string[]>();
                if (!result.satisfied && result.warnings) {
                    details.set(constraintName, result.warnings);
                }
                this.decorations.update(editor, details);
            }

            showEvalResult(constraintName, result);
            showSkipNotice(constraintName, result.warnings);

            fs.unlinkSync(tempFile);

        } catch (error: any) {
            vscode.window.showErrorMessage(`Run failed: ${error.message}`);
            this.results.record(constraintName, false);
            this.codeLensProvider.refresh();
            if (editor) {
                this.decorations.update(editor);
            }
        }
    }
}
