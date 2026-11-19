/**
 * The `VitruvOCL` output channel and the messages shown after a run.
 */
import * as vscode from 'vscode';
import { ConstraintBatchResult, EvalResult } from './cliRunner';
import { countSkipNotices, evalReport, summaryReport } from './report';

/** Created on the first report; the CLI's standard error is only logged once it exists. */
let outputChannel: vscode.OutputChannel | undefined;

function channel(): vscode.OutputChannel {
    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('VitruvOCL');
    }
    return outputChannel;
}

/** Logs the CLI's standard error, if the output channel exists already. */
export function logStderr(stderr: string): void {
    if (stderr && outputChannel) {
        outputChannel.appendLine('[CLI-STDERR] ' + stderr.trim().split('\n').join('\n[CLI-STDERR] '));
    }
}

/** Clears and shows the channel before the report is built, as a failing build leaves it so. */
function show(report: () => string[]): void {
    const target = channel();
    target.clear();
    target.show(true);
    report().forEach(line => target.appendLine(line));
}

/** Shows the result of one constraint in the channel, including skipped pre/post constraints. */
export function showEvalResult(name: string, result: EvalResult): void {
    show(() => evalReport(name, result));
}

/** Notes that the constraint contains pre/post constraints that were not evaluated. */
export function showSkipNotice(constraintName: string, warnings: string[] | undefined): void {
    if (countSkipNotices(warnings) > 0) {
        vscode.window.showInformationMessage(
            `ℹ VitruvOCL: '${constraintName}' contains pre/post constraint(s) that were not ` +
            `evaluated — this plugin only runs invariants (no transaction context available). ` +
            `See the VitruvOCL output channel for details.`
        );
    }
}

/** Shows the results of all constraints and a message how many passed. */
export function showSummaryResult(passed: number, failed: number, constraints: ConstraintBatchResult[]): void {
    let skipped = 0;
    show(() => {
        const report = summaryReport(passed, failed, constraints);
        skipped = report.skippedCount;
        return report.lines;
    });

    const total = passed + failed;
    if (failed === 0) {
        vscode.window.showInformationMessage(`✅ All ${total} constraints passed!`);
    } else {
        vscode.window.showWarningMessage(`❌ ${failed}/${total} constraints failed`);
    }

    if (skipped > 0) {
        vscode.window.showInformationMessage(
            `ℹ VitruvOCL: ${skipped} pre/post constraint(s) were not evaluated — this plugin ` +
            `only runs invariants (no transaction context available). See the VitruvOCL output ` +
            `channel for details.`
        );
    }
}
