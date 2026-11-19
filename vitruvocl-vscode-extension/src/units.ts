/**
 * Finding the constraints of an `.ocl` document by its text. Pure functions without `vscode`, so
 * that they can be tested with `node --test`.
 */

/** A named invariant whose `context` and `inv` stand on one line: `context X inv name:`. */
const CONSTRAINT_HEADER = /^\s*context\s+(\S+)\s+inv\s+(\w+)\s*:/;

/** A constraint header: the line it stands on and the invariant's name. */
export interface ConstraintHeader {
    line: number;
    name: string;
}

/** The constraint headers of a document, in document order. */
export function findConstraintHeaders(text: string): ConstraintHeader[] {
    const headers: ConstraintHeader[] = [];
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
        const match = lines[i].match(CONSTRAINT_HEADER);
        if (match) {
            headers.push({ line: i, name: match[2] });
        }
    }
    return headers;
}

/** The name of the constraint whose header stands on `line`, or `null`. */
export function constraintNameAtLine(text: string, line: number): string | null {
    const lines = text.split('\n');
    if (line >= 0 && line < lines.length) {
        const match = lines[line].match(CONSTRAINT_HEADER);
        if (match) {
            return match[2];
        }
    }
    return null;
}

/** The first line that starts with `context` (after indentation), or -1. */
export function firstContextLine(text: string): number {
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
        if (lines[i].trim().startsWith('context')) {
            return i;
        }
    }
    return -1;
}

/**
 * Whether `line` is in the annotation zone of an `inv`/`pre`/`post` header: every non-blank line
 * between it and the header above is an `@severity` or `@message` annotation, so no OCL body has
 * begun yet.
 *
 * @param lineAt the text of a line of the document, by its index
 */
export function isInAnnotationZone(lineAt: (line: number) => string, line: number): boolean {
    for (let i = line - 1; i >= 0; i--) {
        const lineText = lineAt(i);
        if (lineText.trim() === '') continue; // blank lines are fine
        if (/\b(inv|pre|post)\s+\w+\s*:/.test(lineText)) {
            return true; // reached the inv/pre/post header — we're in the zone
        }
        if (/^\s*@(severity|message)\b/.test(lineText)) continue; // other annotation — ok
        return false; // anything else means we're in the OCL body
    }
    return false;
}

/**
 * The text of the invariant `constraintName` with the `context` line above it, up to the next
 * `context` line; comment lines are dropped, since the CLI's `eval` command does not strip them.
 *
 * @returns the constraint, or `null` if the document has no `inv constraintName:`
 */
export function extractConstraint(text: string, constraintName: string): string | null {
    const lines = text.split('\n');
    let collecting = false;
    let constraint = '';
    let startLine = -1;

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];

        if (line.match(new RegExp(`inv\\s+${constraintName}\\s*:`))) {
            collecting = true;
            startLine = i;
            for (let j = i; j >= 0; j--) {
                if (lines[j].trim().startsWith('context')) {
                    constraint = lines[j] + '\n';
                    break;
                }
            }
        }

        if (collecting && i > startLine) {
            const trimmed = line.trim();
            // Stop at the next constraint's context declaration
            if (trimmed.startsWith('context ')) {
                break;
            }
            // Skip comment lines — the CLI eval command does not strip them
            if (trimmed.startsWith('--')) {
                continue;
            }
            constraint += line + '\n';
        }
    }

    return constraint.trim() || null;
}
