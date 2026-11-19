"use strict";
/**
 * The outcome of the constraints run so far, by name. Does not use `vscode`, so that it can be
 * tested with `node --test`.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.ResultStore = void 0;
class ResultStore {
    constructor() {
        this.results = new Map();
    }
    /**
     * Records that the constraint ran, whether it passed, and its violations. They are kept until
     * the constraint runs again or the store is cleared, so that they still show while the
     * document is edited.
     */
    record(name, passed, violations = []) {
        this.results.set(name, { name, passed, ran: true, violations: passed ? [] : [...violations] });
    }
    get(name) {
        return this.results.get(name);
    }
    /** The state of the constraint: `notRun` if it has no result. */
    stateOf(name) {
        const status = this.results.get(name);
        if (!status || !status.ran) {
            return 'notRun';
        }
        return status.passed ? 'passed' : 'failed';
    }
    /** Whether the constraint ran and failed. */
    hasFailed(name) {
        return this.stateOf(name) === 'failed';
    }
    /** The violations of a failed constraint; empty if it has none or did not fail. */
    violationsOf(name) {
        return this.results.get(name)?.violations ?? [];
    }
    clear() {
        this.results.clear();
    }
}
exports.ResultStore = ResultStore;
//# sourceMappingURL=resultStore.js.map