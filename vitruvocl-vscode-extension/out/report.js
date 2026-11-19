"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.evalReport = evalReport;
exports.summaryReport = summaryReport;
exports.countSkipNotices = countSkipNotices;
const violations_1 = require("./violations");
/** The warnings split into pre/post skip notices and the others (the violations). */
function splitWarnings(warnings) {
    const all = warnings || [];
    return { skipNotices: all.filter(violations_1.isSkipNotice), others: all.filter(w => !(0, violations_1.isSkipNotice)(w)) };
}
/** The lines shown for one constraint run with `eval`. */
function evalReport(name, result) {
    const lines = [`=== ${name} ===\n`];
    const { skipNotices, others } = splitWarnings(result.warnings);
    if (!result.success) {
        lines.push('❌ COMPILATION ERRORS:');
        result.errors.forEach(e => lines.push(`  Line ${e.line}: ${e.message}`));
    }
    else if (result.satisfied) {
        lines.push('✅ CONSTRAINT SATISFIED\nAll instances pass.');
    }
    else {
        lines.push('❌ CONSTRAINT VIOLATED');
        others.forEach(w => lines.push(w));
    }
    // Shown regardless of pass/fail — a skipped pre/post is not a violation, but the user must
    // still be told it was not checked at all.
    if (skipNotices.length > 0) {
        lines.push('');
        lines.push('ℹ NOTE: this only ran invariants — the following were not evaluated:');
        skipNotices.forEach(w => lines.push('  ' + w));
    }
    lines.push('');
    return lines;
}
/** The lines shown for all constraints run with `eval-batch`, and how many pre/post were skipped. */
function summaryReport(passed, failed, constraints) {
    const total = passed + failed;
    const lines = [`=== All Constraints (${total} total) ===\n`];
    if (failed === 0) {
        lines.push(`✅ ALL PASSED (${passed}/${total})\n`);
    }
    else {
        lines.push(`❌ SOME FAILED (${failed}/${total})\n`);
        lines.push(`Passed: ${passed}`);
        lines.push(`Failed: ${failed}\n`);
    }
    lines.push('Individual Results:');
    let skippedCount = 0;
    for (const constraint of constraints) {
        const icon = constraint.satisfied ? '✅' : '❌';
        lines.push(`  ${icon} ${constraint.name}`);
        const { skipNotices, others } = splitWarnings(constraint.warnings);
        if (!constraint.satisfied) {
            others.forEach(w => lines.push(`     ${w}`));
        }
        // Shown regardless of pass/fail — a skipped pre/post is not a violation, but the user
        // must still be told it was not checked at all.
        skipNotices.forEach(w => lines.push(`     ℹ ${w}`));
        skippedCount += skipNotices.length;
    }
    lines.push('');
    return { lines, skippedCount };
}
/** The number of pre/post skip notices among the warnings. */
function countSkipNotices(warnings) {
    return splitWarnings(warnings).skipNotices.length;
}
//# sourceMappingURL=report.js.map