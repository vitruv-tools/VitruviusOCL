"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CONSTRAINT_UNITS_METHOD = void 0;
exports.unitDocumentLine = unitDocumentLine;
exports.layoutFromUnits = layoutFromUnits;
exports.layoutFromText = layoutFromText;
exports.headerLineMapper = headerLineMapper;
exports.alignBatchResults = alignBatchResults;
/**
 * Where the constraints of a document stand and what the CLI evaluates for them: from the units
 * the language server splits the document into, or, without a language server, from the units
 * the extension's own scan finds. Pure functions without `vscode`, so that they can be tested with
 * `node --test`.
 */
const units_1 = require("./units");
/** The JSON-RPC method of the language server's units request. */
exports.CONSTRAINT_UNITS_METHOD = 'vitruvocl/constraintUnits';
/** The line in the document of a line of the unit's text (1-based); its keyword line if unknown. */
function unitDocumentLine(unit, line) {
    const documentLine = line >= 1 ? unit.textLines[line - 1] : undefined;
    return (documentLine ?? unit.line) - 1;
}
function namedInvariant(units, name) {
    return units.find(unit => unit.kind === 'INV' && unit.name === name);
}
/** The layout given by the language server's units. */
function layoutFromUnits(units) {
    return {
        headers: units
            .filter(unit => unit.kind === 'INV' && typeof unit.name === 'string')
            .map(unit => ({ line: unit.line - 1, name: unit.name })),
        runAllLine: units.length > 0 ? unitDocumentLine(units[0], 1) : -1,
        textOf: name => namedInvariant(units, name)?.text ?? null,
        documentLine: (name, line) => {
            const unit = namedInvariant(units, name);
            return unit ? unitDocumentLine(unit, line) : Math.max(0, line - 1);
        },
    };
}
/** The layout of the units the extension's own scan finds, for when no language server answers. */
function layoutFromText(text) {
    return layoutFromUnits((0, units_1.unitsFromText)(text));
}
/**
 * Maps the error lines of an `eval-batch` result to the document when its unit is unknown: onto
 * the header of the invariant of that name, or, without one, as document lines.
 */
function headerLineMapper(layout, name) {
    const header = layout.headers.find(h => h.name === name);
    return header ? () => header.line : line => Math.max(0, line - 1);
}
/**
 * The unit of every `eval-batch` result, which lists one result per unit in document order, named
 * by the unit's name or `unknown`.
 *
 * @returns the units by result index, or `undefined` if they do not line up with the results
 */
function alignBatchResults(units, resultNames) {
    if (units.length !== resultNames.length) {
        return undefined;
    }
    const aligned = units.every((unit, i) => (unit.name ?? 'unknown') === resultNames[i]);
    return aligned ? units : undefined;
}
//# sourceMappingURL=constraintLayout.js.map