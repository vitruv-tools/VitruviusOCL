/**
 * Where the constraints of a document stand and what the CLI evaluates for them: from the units
 * the language server splits the document into, or, without a language server, from the units
 * the extension's own scan finds. Pure functions without `vscode`, so that they can be tested with
 * `node --test`.
 */
import { unitsFromText } from './units';

/** A header of a named invariant: the line of its `inv` and its name. Lines start at 0. */
export interface ConstraintHeader {
    line: number;
    name: string;
}

/** A unit as the language server's request `vitruvocl/constraintUnits` returns it. */
export interface ConstraintUnitInfo {
    kind: 'INV' | 'PRE' | 'POST' | 'EMPTY_CONTEXT';
    /** The block's name; absent (the server leaves `null` out) if unnamed. */
    name?: string | null;
    /** The context type as written; absent if the declaration is malformed. */
    contextType?: string | null;
    /** The compilable constraint the CLI evaluates for this unit. */
    text: string;
    /** The document line of every line of `text`, starting at 1. */
    textLines: number[];
    /** The line of the block's keyword (`context` for an empty context), starting at 1. */
    line: number;
    /** The column of that keyword, starting at 0, in Unicode code points (not UTF-16 units). */
    column: number;
}

/** The JSON-RPC method of the language server's units request. */
export const CONSTRAINT_UNITS_METHOD = 'vitruvocl/constraintUnits';

/** The constraints of a document. Lines start at 0, like those of `vscode`. */
export interface ConstraintLayout {
    /** The header line of every named invariant, which gets a run button and the marks. */
    headers: ConstraintHeader[];
    /** The line of the "Run All" button, or -1 if the document has no constraint. */
    runAllLine: number;
    /** The text the CLI's `eval` runs for the invariant, or `null` if there is none. */
    textOf(name: string): string | null;
    /**
     * The document line of a line of an error the CLI reported for the invariant.
     *
     * @param line the line of the error in the evaluated text, starting at 1; 0 if unknown
     */
    documentLine(name: string, line: number): number;
}

/** The line in the document of a line of the unit's text (1-based); its keyword line if unknown. */
export function unitDocumentLine(unit: ConstraintUnitInfo, line: number): number {
    const documentLine = line >= 1 ? unit.textLines[line - 1] : undefined;
    return (documentLine ?? unit.line) - 1;
}

function namedInvariant(units: ConstraintUnitInfo[], name: string): ConstraintUnitInfo | undefined {
    return units.find(unit => unit.kind === 'INV' && unit.name === name);
}

/** The layout given by the language server's units. */
export function layoutFromUnits(units: ConstraintUnitInfo[]): ConstraintLayout {
    return {
        headers: units
            .filter(unit => unit.kind === 'INV' && typeof unit.name === 'string')
            .map(unit => ({ line: unit.line - 1, name: unit.name as string })),
        runAllLine: units.length > 0 ? unitDocumentLine(units[0], 1) : -1,
        textOf: name => namedInvariant(units, name)?.text ?? null,
        documentLine: (name, line) => {
            const unit = namedInvariant(units, name);
            return unit ? unitDocumentLine(unit, line) : Math.max(0, line - 1);
        },
    };
}

/** The layout of the units the extension's own scan finds, for when no language server answers. */
export function layoutFromText(text: string): ConstraintLayout {
    return layoutFromUnits(unitsFromText(text));
}

/**
 * Maps the error lines of an `eval-batch` result to the document when its unit is unknown: onto
 * the header of the invariant of that name, or, without one, as document lines.
 */
export function headerLineMapper(layout: ConstraintLayout, name: string): (line: number) => number {
    const header = layout.headers.find(h => h.name === name);
    return header ? () => header.line : line => Math.max(0, line - 1);
}

/**
 * The unit of every `eval-batch` result, which lists one result per unit in document order, named
 * by the unit's name or `unknown`.
 *
 * @returns the units by result index, or `undefined` if they do not line up with the results
 */
export function alignBatchResults(units: ConstraintUnitInfo[], resultNames: string[]): ConstraintUnitInfo[] | undefined {
    if (units.length !== resultNames.length) {
        return undefined;
    }
    const aligned = units.every((unit, i) => (unit.name ?? 'unknown') === resultNames[i]);
    return aligned ? units : undefined;
}
