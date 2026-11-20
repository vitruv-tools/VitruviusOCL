/**
 * The constraint layout of the open documents, from the language server's units if it answers,
 * else by the extension's own scan. Does not use `vscode`, so that it can be tested with `node --test`: the request
 * to the language server is passed in.
 */
import { ConstraintLayout, ConstraintUnitInfo, layoutFromText, layoutFromUnits } from './constraintLayout';

/** What the index needs of a document; `vscode.TextDocument` has it. */
export interface UnitDocument {
    readonly uri: { toString(): string };
    readonly version: number;
    readonly isClosed: boolean;
    getText(): string;
}

/** The language server's units request. */
export interface UnitSource {
    /** Whether the language server runs; until it does, the own scan finds the constraints. */
    ready(): boolean;
    /** Asks for the units of the document as the language server has it. */
    request(document: UnitDocument): Promise<ConstraintUnitInfo[]>;
}

/** The JSON-RPC error code of a method the server does not know, e.g. an older language server. */
export const METHOD_NOT_FOUND = -32601;

/** The units of a document, or why there are none. */
type UnitsOutcome =
    | { kind: 'units'; units: ConstraintUnitInfo[] }
    /** No language server, or it failed: the own scan finds the constraints. */
    | { kind: 'fallback' }
    /** The document changed before the language server answered. */
    | { kind: 'stale' };

interface Versioned<T> {
    version: number;
    value: T;
}

export class ConstraintUnitIndex {
    private readonly cache = new Map<string, Versioned<ConstraintUnitInfo[]>>();
    private readonly inFlight = new Map<string, Versioned<Promise<ConstraintUnitInfo[]>>>();
    /** Set if the language server does not know the request; other failures are not latched. */
    private unsupported = false;

    /**
     * @param source the language server's request, or `undefined` without a language server
     * @param warn where failed requests are reported
     */
    constructor(private readonly source: UnitSource | undefined, private readonly warn: (message: string) => void) {}

    /**
     * The units of the document as it is now, from the language server.
     *
     * @returns the units, or `undefined` if the language server does not provide them or the
     *     document changed before it answered
     */
    async unitsOf(document: UnitDocument): Promise<ConstraintUnitInfo[] | undefined> {
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
    async layoutOf(document: UnitDocument): Promise<ConstraintLayout | undefined> {
        const outcome = await this.outcomeOf(document);
        switch (outcome.kind) {
            case 'units': return layoutFromUnits(outcome.units);
            case 'fallback': return layoutFromText(document.getText());
            case 'stale': return undefined;
        }
    }

    /** Forgets the units of a closed document. */
    forget(document: UnitDocument): void {
        const key = document.uri.toString();
        this.cache.delete(key);
        this.inFlight.delete(key);
    }

    private async outcomeOf(document: UnitDocument): Promise<UnitsOutcome> {
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
        } catch (error: unknown) {
            const code = (error as { code?: unknown } | null)?.code;
            if (code === METHOD_NOT_FOUND) {
                this.unsupported = true;
            }
            const message = error instanceof Error ? error.message : String(error);
            this.warn(`[OCL-LS] constraint units unavailable, finding constraints by the own scan: ${message}`);
            return { kind: 'fallback' };
        }
    }

    /** One request per document version, shared by everyone asking meanwhile. */
    private request(key: string, document: UnitDocument): Promise<ConstraintUnitInfo[]> {
        const pending = this.inFlight.get(key);
        if (pending && pending.version === document.version) {
            return pending.value;
        }
        const version = document.version;
        const value = (this.source as UnitSource).request(document).finally(() => {
            if (this.inFlight.get(key)?.value === value) {
                this.inFlight.delete(key);
            }
        });
        this.inFlight.set(key, { version, value });
        return value;
    }
}
