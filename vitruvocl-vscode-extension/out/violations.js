"use strict";
/**
 * Reading the warnings the CLI reports for a constraint: violation blocks and pre/post skip
 * notices. Pure functions without `vscode`, so that they can be tested with `node --test`.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.PRE_POST_SKIPPED_PREFIX = exports.SEVERITY_STYLE = void 0;
exports.styleOf = styleOf;
exports.parseViolationBlock = parseViolationBlock;
exports.inlineSummary = inlineSummary;
exports.isSkipNotice = isSkipNotice;
exports.SEVERITY_STYLE = {
    CRITICAL: { symbol: '✖', color: '#e05252' },
    WARNING: { symbol: '⚠', color: '#e0b84a' },
    MAJOR: { symbol: '⚠', color: '#d4773a' },
    MINOR: { symbol: '⚠', color: '#c8b84a' },
    INFO: { symbol: 'ℹ', color: '#5b9bd5' },
};
/** The style of a severity; unknown severities are shown as warnings. */
function styleOf(severity) {
    return exports.SEVERITY_STYLE[severity] ?? exports.SEVERITY_STYLE['WARNING'];
}
/** The severity and message of a violation block the CLI reports as a warning. */
function parseViolationBlock(block) {
    // Extract severity from line: [SEVERITY] constraintName
    const sevMatch = block.match(/\[([A-Z]+)\]/);
    const severity = sevMatch ? sevMatch[1] : 'WARNING';
    // Extract message from "Message : ..." line
    const msgMatch = block.match(/^Message\s*:\s*(.+)$/m);
    const message = msgMatch ? msgMatch[1].trim() : block.split('\n').find(l => l.trim() && !l.startsWith('-') && !l.startsWith('[')) ?? block;
    return { severity, message };
}
/**
 * The text shown at the end of a violated constraint's header line and its color: the first
 * violation and how many more there are, or a generic note if no details are known.
 */
function inlineSummary(constraintName, details) {
    if (details && details.length > 0) {
        const { severity, message } = parseViolationBlock(details[0]);
        const style = styleOf(severity);
        const suffix = details.length > 1 ? ` (+${details.length - 1} more)` : '';
        return { text: ` ◀ ${style.symbol} ${severity}: ${message}${suffix}`, color: style.color };
    }
    return { text: ` ◀ ⚠ WARNING: ${constraintName} violated`, color: exports.SEVERITY_STYLE['WARNING'].color };
}
/**
 * Stable prefix identifying a pre/post-skip notice among a constraint's plain-string warnings —
 * must match {@code ConstraintEvaluations.PRE_POST_SKIPPED_PREFIX} on the Java side exactly. Pre/post
 * constraints (anything using `pre`/`post`/`@pre`/`OCLisNew`/`OCLisModified`/`OCLisDeleted`) cannot
 * be evaluated by this plugin: it has no notion of a transaction (that only exists once a real
 * Reactions integration supplies one), so the CLI it shells out to always uses the no-transaction
 * evaluation path, which skips pre/post outright rather than silently mis-evaluating them.
 */
exports.PRE_POST_SKIPPED_PREFIX = 'PRE/POST SKIPPED:';
/** Whether a warning is a pre/post skip notice rather than a violation. */
function isSkipNotice(warning) {
    return warning.startsWith(exports.PRE_POST_SKIPPED_PREFIX);
}
//# sourceMappingURL=violations.js.map