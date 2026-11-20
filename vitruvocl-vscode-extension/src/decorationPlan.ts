/**
 * Which marks the constraint headers of a document get, computed from its text and the results.
 * Pure functions without `vscode`, so that they can be tested with `node --test`.
 */
import { ResultStore } from './resultStore';
import { ConstraintHeader } from './constraintLayout';
import { genericViolation, inlineSummary, parseViolationBlock, styleOf } from './violations';

/** One entry of a failed constraint's hover: a violation, or a generic note without any. */
export interface HoverEntry {
    symbol: string;
    severity: string;
    message: string;
}

/** The note at the end of a failed constraint's header line. */
export interface InlineMark {
    line: number;
    /** The end of the header line, where the note is shown. */
    column: number;
    text: string;
    color: string;
    hover: HoverEntry[];
}

/** The header lines by gutter icon, and the notes of the failed constraints. */
export interface DecorationPlan {
    notRun: number[];
    passed: number[];
    failed: number[];
    inline: InlineMark[];
}

/** The hover of a failed constraint: all violations, or a generic note if none are known. */
function hoverEntries(constraintName: string, violations: readonly string[]): HoverEntry[] {
    if (violations.length === 0) {
        return [{ symbol: styleOf('WARNING').symbol, severity: 'WARNING', message: genericViolation(constraintName) }];
    }
    return violations.map(block => {
        const { severity, message } = parseViolationBlock(block);
        return { symbol: styleOf(severity).symbol, severity, message };
    });
}

/**
 * The marks of the constraint headers of a document.
 *
 * @param headers the header lines, from the document's constraint layout
 * @param lines the lines of the document, without line breaks
 */
export function planDecorations(headers: ConstraintHeader[], lines: string[], results: ResultStore): DecorationPlan {
    const plan: DecorationPlan = { notRun: [], passed: [], failed: [], inline: [] };
    for (const header of headers) {
        const state = results.stateOf(header.name);
        plan[state].push(header.line); // the states name the gutter lists of the plan
        if (state !== 'failed') {
            continue;
        }
        const violations = results.violationsOf(header.name);
        const summary = inlineSummary(header.name, violations);
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
