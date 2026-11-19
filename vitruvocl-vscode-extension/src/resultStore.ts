/**
 * The outcome of the constraints run so far, by name. Does not use `vscode`, so that it can be
 * tested with `node --test`.
 */

/** The outcome of a constraint: whether it ran, whether it passed, and its violations. */
export interface ConstraintStatus {
    name: string;
    passed: boolean;
    ran: boolean;
    /** The violation blocks the CLI reported; empty if it passed or reported none. */
    violations: string[];
}

/** How a constraint's header is marked: not run yet, passed, or failed. */
export type ConstraintState = 'notRun' | 'passed' | 'failed';

export class ResultStore {
    private readonly results = new Map<string, ConstraintStatus>();

    /**
     * Records that the constraint ran, whether it passed, and its violations. They are kept until
     * the constraint runs again or the store is cleared, so that they still show while the
     * document is edited.
     */
    record(name: string, passed: boolean, violations: string[] = []): void {
        this.results.set(name, { name, passed, ran: true, violations: passed ? [] : [...violations] });
    }

    get(name: string): ConstraintStatus | undefined {
        return this.results.get(name);
    }

    /** The state of the constraint: `notRun` if it has no result. */
    stateOf(name: string): ConstraintState {
        const status = this.results.get(name);
        if (!status || !status.ran) {
            return 'notRun';
        }
        return status.passed ? 'passed' : 'failed';
    }

    /** Whether the constraint ran and failed. */
    hasFailed(name: string): boolean {
        return this.stateOf(name) === 'failed';
    }

    /** The violations of a failed constraint; empty if it has none or did not fail. */
    violationsOf(name: string): string[] {
        return this.results.get(name)?.violations ?? [];
    }

    clear(): void {
        this.results.clear();
    }
}
