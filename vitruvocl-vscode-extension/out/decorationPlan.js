"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.planDecorations = planDecorations;
const violations_1 = require("./violations");
/** The hover of a failed constraint: all violations, or a generic note if none are known. */
function hoverEntries(constraintName, violations) {
    if (violations.length === 0) {
        return [{ symbol: (0, violations_1.styleOf)('WARNING').symbol, severity: 'WARNING', message: (0, violations_1.genericViolation)(constraintName) }];
    }
    return violations.map(block => {
        const { severity, message } = (0, violations_1.parseViolationBlock)(block);
        return { symbol: (0, violations_1.styleOf)(severity).symbol, severity, message };
    });
}
/**
 * The marks of the constraint headers of a document.
 *
 * @param headers the header lines, from the document's constraint layout
 * @param lines the lines of the document, without line breaks
 */
function planDecorations(headers, lines, results) {
    const plan = { notRun: [], passed: [], failed: [], inline: [] };
    for (const header of headers) {
        const state = results.stateOf(header.name);
        plan[state].push(header.line); // the states name the gutter lists of the plan
        if (state !== 'failed') {
            continue;
        }
        const violations = results.violationsOf(header.name);
        const summary = (0, violations_1.inlineSummary)(header.name, violations);
        plan.inline.push({
            line: header.line,
            column: lines[header.line]?.length ?? 0,
            text: summary.text,
            color: summary.color,
            hover: hoverEntries(header.name, violations),
        });
    }
    return plan;
}
//# sourceMappingURL=decorationPlan.js.map