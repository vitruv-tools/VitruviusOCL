"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConstraintUnitIndex = exports.METHOD_NOT_FOUND = void 0;
/**
 * The constraint layout of the open documents, from the language server's units if it answers,
 * else by the extension's own scan. Does not use `vscode`, so that it can be tested with `node --test`: the request
 * to the language server is passed in.
 */
const constraintLayout_1 = require("./constraintLayout");
/** The JSON-RPC error code of a method the server does not know, e.g. an older language server. */
exports.METHOD_NOT_FOUND = -32601;
class ConstraintUnitIndex {
    /**
     * @param source the language server's request, or `undefined` without a language server
     * @param warn where failed requests are reported
     */
    constructor(source, warn) {
        this.source = source;
        this.warn = warn;
        this.cache = new Map();
        this.inFlight = new Map();
        /** Set if the language server does not know the request; other failures are not latched. */
        this.unsupported = false;
    }
    /**
     * The units of the document as it is now, from the language server.
     *
     * @returns the units, or `undefined` if the language server does not provide them or the
     *     document changed before it answered
     */
    async unitsOf(document) {
        const outcome = await this.outcomeOf(document);
        return outcome.kind === 'units' ? outcome.units : undefined;
    }
    /**
     * The layout of the document: from the language server's units, or by the own scan if it does not
     * provide them.
     *
     * @returns the layout, or `undefined` if the document changed before the language server
     *     answered; the change brings an update of its own
     */
    async layoutOf(document) {
        const outcome = await this.outcomeOf(document);
        switch (outcome.kind) {
            case 'units': return (0, constraintLayout_1.layoutFromUnits)(outcome.units);
            case 'fallback': return (0, constraintLayout_1.layoutFromText)(document.getText());
            case 'stale': return undefined;
        }
    }
    /** Forgets the units of a closed document. */
    forget(document) {
        const key = document.uri.toString();
        this.cache.delete(key);
        this.inFlight.delete(key);
    }
    async outcomeOf(document) {
        if (!this.source || this.unsupported || !this.source.ready()) {
            return { kind: 'fallback' };
        }
        const key = document.uri.toString();
        const version = document.version;
        const cached = this.cache.get(key);
        if (cached && cached.version === version) {
            return { kind: 'units', units: cached.value };
        }
        try {
            const units = await this.request(key, document);
            if (document.version !== version) {
                return { kind: 'stale' };
            }
            if (!document.isClosed) {
                this.cache.set(key, { version, value: units });
            }
            return { kind: 'units', units };
        }
        catch (error) {
            const code = error?.code;
            if (code === exports.METHOD_NOT_FOUND) {
                this.unsupported = true;
            }
            const message = error instanceof Error ? error.message : String(error);
            this.warn(`[OCL-LS] constraint units unavailable, finding constraints by the own scan: ${message}`);
            return { kind: 'fallback' };
        }
    }
    /** One request per document version, shared by everyone asking meanwhile. */
    request(key, document) {
        const pending = this.inFlight.get(key);
        if (pending && pending.version === document.version) {
            return pending.value;
        }
        const version = document.version;
        const value = this.source.request(document).finally(() => {
            if (this.inFlight.get(key)?.value === value) {
                this.inFlight.delete(key);
            }
        });
        this.inFlight.set(key, { version, value });
        return value;
    }
}
exports.ConstraintUnitIndex = ConstraintUnitIndex;
//# sourceMappingURL=unitIndex.js.map