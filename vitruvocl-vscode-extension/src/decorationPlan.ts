/**
 * Which marks the constraint headers of a document get, computed from its text and the results.
 * Pure functions without `vscode`, so that they can be tested with `node --test`.
 */
import { ResultStore } from './resultStore';
import { findConstraintHeaders } from './units';
import { inlineSummary, parseViolationBlock, styleOf } from './violations';

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
function hoverEntries(constraintName: string, violations: string[]): HoverEntry[] {
    if (violations.length === 0) {
        return [{ symbol: '⚠', severity: 'WARNING', message: `${constraintName} violated` }];
    }
    return violations.map(block => {
        const { severity, message } = parseViolationBlock(block);
        return { symbol: styleOf(severity).symbol, severity, message };
    });
}

/** The marks of every constraint header in the text. */
export function planDecorations(text: string, results: ResultStore): DecorationPlan {
    const lines = text.split('\n');
    const plan: DecorationPlan = { notRun: [], passed: [], failed: [], inline: [] };
    for (const header of findConstraintHeaders(text)) {
        const state = results.stateOf(header.name);
        plan[state].push(header.line);
        if (state !== 'failed') {
            continue;
        }
        const violations = results.violationsOf(header.name);
        const summary = inlineSummary(header.name, violations);
        plan.inline.push({
            line: header.line,
            column: lines[header.line].length,
            text: summary.text,
            color: summary.color,
            hover: hoverEntries(header.name, violations),
        });
    }
    return plan;
}
