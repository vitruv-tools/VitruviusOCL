/**
 * The compilation errors of run constraints, as diagnostics on the document lines they belong to.
 */
import * as vscode from 'vscode';
import { ConstraintBatchResult, EvalResult } from './cliRunner';
import { ConstraintLayout } from './constraintLayout';

/** An error of the constraint `name`, which its `code` names so that a new run can replace it. */
function compilationError(name: string, line: number, message: string): vscode.Diagnostic {
    const diagnostic = new vscode.Diagnostic(
        new vscode.Range(line, 0, line, 1000),
        `Compilation Error: ${message}`,
        vscode.DiagnosticSeverity.Error
    );
    diagnostic.source = 'vitruvocl';
    diagnostic.code = name;
    return diagnostic;
}

/**
 * Replaces the diagnostics of one invariant run with `eval`: its earlier errors are dropped, the
 * result's compilation errors are added.
 *
 * @param layout the layout the invariant's text was taken from
 */
export function updateDiagnosticsForConstraint(
    collection: vscode.DiagnosticCollection,
    document: vscode.TextDocument,
    layout: ConstraintLayout,
    constraintName: string,
    result: EvalResult
): void {
    const diagnostics: vscode.Diagnostic[] = [];
    if (!result.success) {
        for (const error of result.errors ?? []) {
            diagnostics.push(compilationError(constraintName, layout.documentLine(constraintName, error.line), error.message));
        }
    }

    const otherDiagnostics = Array.from(collection.get(document.uri) || [])
        .filter(d => d.code !== constraintName);

    collection.set(document.uri, [...otherDiagnostics, ...diagnostics]);
}

/** Drops the diagnostics of a constraint, e.g. when it could not be evaluated. */
export function clearDiagnosticsOf(
    collection: vscode.DiagnosticCollection,
    document: vscode.TextDocument,
    constraintName: string
): void {
    collection.set(document.uri, Array.from(collection.get(document.uri) || []).filter(d => d.code !== constraintName));
}

/**
 * Adds the compilation errors of one constraint run with `eval-batch`.
 *
 * @param documentLine the document line of a line of the constraint's errors, starting at 1
 */
export function updateDiagnosticsForConstraintBatch(
    collection: vscode.DiagnosticCollection,
    document: vscode.TextDocument,
    constraint: ConstraintBatchResult,
    documentLine: (line: number) => number
): void {
    const diagnostics: vscode.Diagnostic[] = Array.from(collection.get(document.uri) || []);
    if (!constraint.success && constraint.errors) {
        for (const error of constraint.errors) {
            diagnostics.push(compilationError(constraint.name, documentLine(error.line), error.message));
        }
    }
    collection.set(document.uri, diagnostics);
}
