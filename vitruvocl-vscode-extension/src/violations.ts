/**
 * Reading the warnings the CLI reports for a constraint: violation blocks and pre/post skip
 * notices. Pure functions without `vscode`, so that they can be tested with `node --test`.
 */

/** How a violation of a severity is shown. */
export interface ViolationStyle {
    symbol: string;
    color: string;
}

export const SEVERITY_STYLE: Record<string, ViolationStyle> = {
    CRITICAL: { symbol: '✖', color: '#e05252' },
    WARNING:  { symbol: '⚠', color: '#e0b84a' },
    MAJOR:    { symbol: '⚠', color: '#d4773a' },
    MINOR:    { symbol: '⚠', color: '#c8b84a' },
    INFO:     { symbol: 'ℹ', color: '#5b9bd5' },
};

/** The style of a severity; unknown severities are shown as warnings. */
export function styleOf(severity: string): ViolationStyle {
    return SEVERITY_STYLE[severity] ?? SEVERITY_STYLE['WARNING'];
}

/** The note for a failed constraint whose violations are not known. */
export function genericViolation(constraintName: string): string {
    return `${constraintName} violated`;
}

/** The severity and message of a violation block the CLI reports as a warning. */
export function parseViolationBlock(block: string): { severity: string; message: string } {
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
export function inlineSummary(constraintName: string, details: readonly string[]): { text: string; color: string } {
    if (details.length > 0) {
        const { severity, message } = parseViolationBlock(details[0]);
        const style = styleOf(severity);
        const suffix = details.length > 1 ? ` (+${details.length - 1} more)` : '';
        return { text: ` ◀ ${style.symbol} ${severity}: ${message}${suffix}`, color: style.color };
    }
    return { text: ` ◀ ⚠ WARNING: ${genericViolation(constraintName)}`, color: SEVERITY_STYLE['WARNING'].color };
}

/**
 * Stable prefix identifying a pre/post-skip notice among a constraint's plain-string warnings —
 * must match {@code ConstraintEvaluations.PRE_POST_SKIPPED_PREFIX} on the Java side exactly. Pre/post
 * constraints (anything using `pre`/`post`/`@pre`/`OCLisNew`/`OCLisModified`/`OCLisDeleted`) cannot
 * be evaluated by this plugin: it has no notion of a transaction (that only exists once a real
 * Reactions integration supplies one), so the CLI it shells out to always uses the no-transaction
 * evaluation path, which skips pre/post outright rather than silently mis-evaluating them.
 */
export const PRE_POST_SKIPPED_PREFIX = 'PRE/POST SKIPPED:';

/** Whether a warning is a pre/post skip notice rather than a violation. */
export function isSkipNotice(warning: string): boolean {
    return warning.startsWith(PRE_POST_SKIPPED_PREFIX);
}

/** The violations among a constraint's warnings: all but the pre/post skip notices. */
export function violationsOf(warnings: string[] | undefined): string[] {
    return (warnings ?? []).filter(w => !isSkipNotice(w));
}
