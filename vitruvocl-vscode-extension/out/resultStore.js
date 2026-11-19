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
    /** Records that the constraint ran, and whether it passed. */
    record(name, passed) {
        this.results.set(name, { name, passed, ran: true });
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
    clear() {
        this.results.clear();
    }
}
exports.ResultStore = ResultStore;
//# sourceMappingURL=resultStore.js.map