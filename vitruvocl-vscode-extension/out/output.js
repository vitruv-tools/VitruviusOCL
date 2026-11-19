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
exports.logStderr = logStderr;
exports.showEvalResult = showEvalResult;
exports.showSkipNotice = showSkipNotice;
exports.showSummaryResult = showSummaryResult;
/**
 * The `VitruvOCL` output channel and the messages shown after a run.
 */
const vscode = __importStar(require("vscode"));
const report_1 = require("./report");
/** Created on the first report; the CLI's standard error is only logged once it exists. */
let outputChannel;
function channel() {
    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('VitruvOCL');
    }
    return outputChannel;
}
/** Logs the CLI's standard error, if the output channel exists already. */
function logStderr(stderr) {
    if (stderr && outputChannel) {
        outputChannel.appendLine('[CLI-STDERR] ' + stderr.trim().split('\n').join('\n[CLI-STDERR] '));
    }
}
/** Clears and shows the channel before the report is built, as a failing build leaves it so. */
function show(report) {
    const target = channel();
    target.clear();
    target.show(true);
    report().forEach(line => target.appendLine(line));
}
/** Shows the result of one constraint in the channel, including skipped pre/post constraints. */
function showEvalResult(name, result) {
    show(() => (0, report_1.evalReport)(name, result));
}
/** Notes that the constraint contains pre/post constraints that were not evaluated. */
function showSkipNotice(constraintName, warnings) {
    if ((0, report_1.countSkipNotices)(warnings) > 0) {
        vscode.window.showInformationMessage(`ℹ VitruvOCL: '${constraintName}' contains pre/post constraint(s) that were not ` +
            `evaluated — this plugin only runs invariants (no transaction context available). ` +
            `See the VitruvOCL output channel for details.`);
    }
}
/** Shows the results of all constraints and a message how many passed. */
function showSummaryResult(passed, failed, constraints) {
    let skipped = 0;
    show(() => {
        const report = (0, report_1.summaryReport)(passed, failed, constraints);
        skipped = report.skippedCount;
        return report.lines;
    });
    const total = passed + failed;
    if (failed === 0) {
        vscode.window.showInformationMessage(`✅ All ${total} constraints passed!`);
    }
    else {
        vscode.window.showWarningMessage(`❌ ${failed}/${total} constraints failed`);
    }
    if (skipped > 0) {
        vscode.window.showInformationMessage(`ℹ VitruvOCL: ${skipped} pre/post constraint(s) were not evaluated — this plugin ` +
            `only runs invariants (no transaction context available). See the VitruvOCL output ` +
            `channel for details.`);
    }
}
//# sourceMappingURL=output.js.map