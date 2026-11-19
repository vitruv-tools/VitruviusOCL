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
exports.updateDiagnosticsForConstraintBatch = updateDiagnosticsForConstraintBatch;
/**
 * The compilation errors of run constraints, as diagnostics.
 */
const vscode = __importStar(require("vscode"));
const units_1 = require("./units");
function compilationError(error) {
    const line = Math.max(0, error.line - 1);
    const diagnostic = new vscode.Diagnostic(new vscode.Range(line, 0, line, 1000), `Compilation Error: ${error.message}`, vscode.DiagnosticSeverity.Error);
    diagnostic.source = 'vitruvocl';
    return diagnostic;
}
/**
 * Replaces the diagnostics of one constraint run with `eval`: those on its header line are
 * dropped, the result's compilation errors are added.
 */
function updateDiagnosticsForConstraint(collection, document, constraintName, result) {
    const diagnostics = [];
    if (!result.success) {
        for (const error of result.errors) {
            diagnostics.push(compilationError(error));
        }
    }
    const existingDiagnostics = Array.from(collection.get(document.uri) || []);
    const otherDiagnostics = existingDiagnostics.filter(d => (0, units_1.constraintNameAtLine)(document.getText(), d.range.start.line) !== constraintName);
    collection.set(document.uri, [...otherDiagnostics, ...diagnostics]);
}
/** Adds the compilation errors of one constraint run with `eval-batch`. */
function updateDiagnosticsForConstraintBatch(collection, document, constraint) {
    const diagnostics = Array.from(collection.get(document.uri) || []);
    if (!constraint.success && constraint.errors) {
        for (const error of constraint.errors) {
            diagnostics.push(compilationError(error));
        }
    }
    collection.set(document.uri, diagnostics);
}
//# sourceMappingURL=diagnostics.js.map