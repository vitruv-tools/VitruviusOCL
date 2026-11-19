/**
 * The commands that run constraints with the CLI: one constraint, or all of a document.
 */
import * as vscode from 'vscode';
import {
    BatchEvalResult,
    cliFailure,
    deleteTempConstraint,
    EvalResult,
    evalArgs,
    evalBatchArgs,
    runJava,
    writeTempConstraint,
} from './cliRunner';
import { OCLCodeLensProvider } from './codeLens';
import {
    alignBatchResults,
    ConstraintLayout,
    ConstraintUnitInfo,
    headerLineMapper,
    layoutFromText,
    unitDocumentLine,
} from './constraintLayout';
import { ConstraintDecorations } from './decorations';
import { clearDiagnosticsOf, updateDiagnosticsForConstraint, updateDiagnosticsForConstraintBatch } from './diagnostics';
import { findCompilerJar, findEcoreFiles, findInstanceFiles } from './discovery';
import { logLine, logStderr, showEvalResult, showSkipNotice, showSummaryResult } from './output';
import { ProjectMetamodels } from './projectMetamodels';
import { ResultStore } from './resultStore';
import { ConstraintUnitIndex } from './unitIndex';
import { violationsOf } from './violations';
import { containsVsum } from './vsumFiles';

/**
 * How often the language server is asked for the layout of a document that keeps changing before
 * it answers; then the extension's own scan finds the constraints instead.
 */
const LAYOUT_ATTEMPTS = 2;

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
        private readonly units: ConstraintUnitIndex,
        /** Where the choice among several equally fitting VSUM folders is remembered. */
        private readonly workspaceState: vscode.Memento,
        private readonly projectMetamodels: ProjectMetamodels,
    ) {}

    /**
     * The layout of the document as it is now. If it keeps changing while the language server
     * answers, the extension's own scan finds the constraints instead.
     */
    private async currentLayout(document: vscode.TextDocument): Promise<ConstraintLayout> {
        for (let attempt = 0; attempt < LAYOUT_ATTEMPTS; attempt++) {
            const layout = await this.units.layoutOf(document);
            if (layout) {
                return layout;
            }
        }
        return layoutFromText(document.getText());
    }

    /**
     * How the error lines of each `eval-batch` result map to the document: through the unit the
     * result belongs to, if the units of the saved document line up with the results; else onto
     * the header of the invariant of the result's name.
     *
     * @param units the units of the document, or `undefined` if unknown or not saved
     * @param layout the layout of the document as the editor has it
     */
    private static batchLineMappers(
        units: ConstraintUnitInfo[] | undefined,
        layout: ConstraintLayout,
        result: BatchEvalResult
    ): Array<(line: number) => number> {
        const aligned = units && alignBatchResults(units, result.constraints.map(c => c.name));
        return result.constraints.map((constraint, i) =>
            aligned ? (line: number) => unitDocumentLine(aligned[i], line) : headerLineMapper(layout, constraint.name));
    }

    /**
     * Finds the compiler jar, the metamodels and the VSUM, telling the user what is missing.
     *
     * @returns the inputs, or `undefined` if one is missing
     */
    private async findCliInputs(document: vscode.TextDocument): Promise<CliInputs | undefined> {
        const compilerPath = await findCompilerJar();
        if (!compilerPath) {
            vscode.window.showErrorMessage('vitruvocl.jar not found');
            return undefined;
        }

        const instanceFiles = await findInstanceFiles(document, this.workspaceState);
        if (instanceFiles === undefined) {
            vscode.window.showInformationMessage(
                'VitruvOCL: no VSUM chosen, the constraints were not run. Run them again to choose one, '
                + 'or set "vitruvocl.vsumPath" to the VSUM folder.');
            return undefined;
        }
        if (!containsVsum(instanceFiles)) {
            vscode.window.showErrorMessage(
                'No VSUM found. Run VSUMExample.main() from your IDE to create one, then try again.'
            );
            return undefined;
        }

        const workspaceEcores = await findEcoreFiles(document.uri);
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

            const { stdout, stderr } = await runJava(
                evalBatchArgs(inputs.compilerPath, document.fileName, inputs.ecoreFiles, inputs.instanceFiles));

            // Surface CLI stderr in the output channel so debug prints are visible
            logStderr(stderr);

            if (!stdout.trim()) {
                const hint = stderr ? `\n\nStderr:\n${stderr.trim()}` : '';
                throw new Error(`Java process produced no output.${hint}`);
            }
            const result = JSON.parse(stdout) as BatchEvalResult;

            if (!result.success) {
                const reason = cliFailure(result);
                vscode.window.showErrorMessage(reason ? `Batch evaluation failed: ${reason}` : 'Batch evaluation failed');
                logLine(`[CLI] eval-batch failed: ${reason ?? stdout.trim()}`);
                return;
            }

            let passed = 0;
            let failed = 0;
            const lineMappers = ConstraintRunner.batchLineMappers(units, layout, result);
            result.constraints.forEach((constraint, i) => {
                this.results.record(constraint.name, constraint.satisfied, violationsOf(constraint.warnings));
                updateDiagnosticsForConstraintBatch(this.diagnosticCollection, document, constraint, lineMappers[i]);

                if (constraint.satisfied) {
                    passed++;
                } else {
                    failed++;
                }
            });

            this.codeLensProvider.refresh();
            void this.decorations.updateVisible();

            showSummaryResult(passed, failed, result.constraints);

        } catch (error: unknown) {
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Run all failed: ${message}`);
            logLine(`[CLI] eval-batch was not evaluated: ${message}`);
        }
    }

    /**
     * Runs one invariant of the document with `eval`, from a temporary file holding the text the
     * CLI would evaluate for it in the whole document; its errors are mapped back to the document.
     */
    async runConstraint(constraintName: string, documentUri: vscode.Uri): Promise<void> {
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

            this.results.record(constraintName, result.satisfied, violationsOf(result.warnings));
            updateDiagnosticsForConstraint(this.diagnosticCollection, document, layout, constraintName, result);

            this.codeLensProvider.refresh();
            void this.decorations.updateVisible();

            showEvalResult(constraintName, result);
            showSkipNotice(constraintName, result.warnings);

        } catch (error: unknown) {
            // The constraint was not evaluated: it is neither satisfied nor violated
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Run failed: ${message}`);
            logLine(`[CLI] ${constraintName} was not evaluated: ${message}`);
            this.results.forget(constraintName);
            clearDiagnosticsOf(this.diagnosticCollection, document, constraintName);
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
    private async evaluate(constraint: string, inputs: CliInputs): Promise<EvalResult> {
        const tempFile = writeTempConstraint(constraint);
        try {
            const { stdout, stderr } = await runJava(
                evalArgs(inputs.compilerPath, tempFile, inputs.ecoreFiles, inputs.instanceFiles));

            logStderr(stderr);

            if (!stdout.trim()) {
                const hint = stderr ? `\n\nStderr:\n${stderr.trim()}` : '';
                throw new Error(`Java process produced no output.${hint}`);
            }

            const result = JSON.parse(stdout) as EvalResult;
            const failure = cliFailure(result);
            if (failure) {
                throw new Error(failure);
            }
            return result;
        } finally {
            deleteTempConstraint(tempFile);
        }
    }
}
