/**
 * The compilation errors of run constraints, as diagnostics.
 */
import * as vscode from 'vscode';
import { ConstraintBatchResult, EvalResult } from './cliRunner';
import { constraintNameAtLine } from './units';

function compilationError(error: { line: number, message: string }): vscode.Diagnostic {
    const line = Math.max(0, error.line - 1);
    const diagnostic = new vscode.Diagnostic(
        new vscode.Range(line, 0, line, 1000),
        `Compilation Error: ${error.message}`,
        vscode.DiagnosticSeverity.Error
    );
    diagnostic.source = 'vitruvocl';
    return diagnostic;
}

/**
 * Replaces the diagnostics of one constraint run with `eval`: those on its header line are
 * dropped, the result's compilation errors are added.
 */
export function updateDiagnosticsForConstraint(
    collection: vscode.DiagnosticCollection,
    document: vscode.TextDocument,
    constraintName: string,
    result: EvalResult
): void {
    const diagnostics: vscode.Diagnostic[] = [];
    if (!result.success) {
        for (const error of result.errors) {
            diagnostics.push(compilationError(error));
        }
    }

    const existingDiagnostics = Array.from(collection.get(document.uri) || []);
    const otherDiagnostics = existingDiagnostics.filter(d =>
        constraintNameAtLine(document.getText(), d.range.start.line) !== constraintName);

    collection.set(document.uri, [...otherDiagnostics, ...diagnostics]);
}

/** Adds the compilation errors of one constraint run with `eval-batch`. */
export function updateDiagnosticsForConstraintBatch(
    collection: vscode.DiagnosticCollection,
    document: vscode.TextDocument,
    constraint: ConstraintBatchResult
): void {
    const diagnostics: vscode.Diagnostic[] = Array.from(collection.get(document.uri) || []);
    if (!constraint.success && constraint.errors) {
        for (const error of constraint.errors) {
            diagnostics.push(compilationError(error));
        }
    }
    collection.set(document.uri, diagnostics);
}
