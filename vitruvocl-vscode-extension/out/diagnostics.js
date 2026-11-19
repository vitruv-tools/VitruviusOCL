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
exports.updateDiagnosticsForConstraint = updateDiagnosticsForConstraint;
exports.clearDiagnosticsOf = clearDiagnosticsOf;
exports.updateDiagnosticsForConstraintBatch = updateDiagnosticsForConstraintBatch;
/**
 * The compilation errors of run constraints, as diagnostics on the document lines they belong to.
 */
const vscode = __importStar(require("vscode"));
/** An error of the constraint `name`, which its `code` names so that a new run can replace it. */
function compilationError(name, line, message) {
    const diagnostic = new vscode.Diagnostic(new vscode.Range(line, 0, line, 1000), `Compilation Error: ${message}`, vscode.DiagnosticSeverity.Error);
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
function updateDiagnosticsForConstraint(collection, document, layout, constraintName, result) {
    const diagnostics = [];
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
function clearDiagnosticsOf(collection, document, constraintName) {
    collection.set(document.uri, Array.from(collection.get(document.uri) || []).filter(d => d.code !== constraintName));
}
/**
 * Adds the compilation errors of one constraint run with `eval-batch`.
 *
 * @param documentLine the document line of a line of the constraint's errors, starting at 1
 */
function updateDiagnosticsForConstraintBatch(collection, document, constraint, documentLine) {
    const diagnostics = Array.from(collection.get(document.uri) || []);
    if (!constraint.success && constraint.errors) {
        for (const error of constraint.errors) {
            diagnostics.push(compilationError(constraint.name, documentLine(error.line), error.message));
        }
    }
    collection.set(document.uri, diagnostics);
}
//# sourceMappingURL=diagnostics.js.map