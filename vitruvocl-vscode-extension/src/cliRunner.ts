/**
 * Running the VitruvOCL CLI (`vitruvOCL.jar`) and the JSON it prints. Does not use `vscode`, so
 * that it can be tested with `node --test`.
 */
import * as child_process from 'child_process';
import * as util from 'util';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

const execFile = util.promisify(child_process.execFile);

/** One error of an `eval` result. */
export interface EvalError {
    line: number;
    column: number;
    message: string;
    severity: string;
}

/** What `eval` prints. */
export interface EvalResult {
    success: boolean;
    satisfied: boolean;
    /** The compilation errors; absent if the CLI itself failed (see `error`). */
    errors?: EvalError[];
    warnings?: string[];
    /** Why the CLI failed, e.g. an exception or invalid arguments; only then present. */
    error?: string;
}

/** One constraint of an `eval-batch` result. */
export interface ConstraintBatchResult {
    name: string;
    success: boolean;
    satisfied: boolean;
    errors?: Array<{ line: number, column: number, message: string }>;
    warnings?: string[];
}

/** What `eval-batch` prints. */
export interface BatchEvalResult {
    success: boolean;
    constraints: ConstraintBatchResult[];
    /** Why the CLI failed; only present if it did. */
    error?: string;
}

/**
 * Why the CLI failed as a whole, as its `{"success":false,"error":...}` says, rather than the
 * constraint failing to compile; `undefined` if it did not.
 */
export function cliFailure(result: { success: boolean; error?: unknown }): string | undefined {
    if (result.success) {
        return undefined;
    }
    return typeof result.error === 'string' ? result.error : undefined;
}

/** What the Java process printed. */
export interface ProcessOutput {
    stdout: string;
    stderr: string;
}

/** The `java` arguments of `eval-batch` for a whole constraint file. */
export function evalBatchArgs(compilerPath: string, constraintFile: string, ecoreFiles: string[], instanceFiles: string[]): string[] {
    return [
        '-jar', compilerPath,
        'eval-batch',
        constraintFile,
        '--ecore', ecoreFiles.join(','),
        '--xmi', instanceFiles.join(',')
    ];
}

/** The `java` arguments of `eval` for a file holding one constraint. */
export function evalArgs(compilerPath: string, constraintFile: string, ecoreFiles: string[], instanceFiles: string[]): string[] {
    return [
        '-jar', compilerPath, 'eval', constraintFile,
        '--ecore', ecoreFiles.join(','),
        '--xmi', instanceFiles.join(',')
    ];
}

/**
 * Runs `java` with the arguments. A non-zero exit status is not an error: the CLI then still
 * prints its JSON, so the output is returned as it is; if the process could not run at all, its
 * error message stands in for standard error.
 */
export async function runJava(args: string[]): Promise<ProcessOutput> {
    return execFile('java', args).catch(err => ({
        stdout: err.stdout || '',
        stderr: err.stderr || err.message || ''
    }));
}

/** Writes the constraint to a new temporary `.ocl` file and returns its path. */
export function writeTempConstraint(constraint: string): string {
    // a directory of its own, so that runs started at the same time do not share the file
    const tempFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'vitruvocl-')), 'constraint.ocl');
    fs.writeFileSync(tempFile, constraint, 'utf-8');
    return tempFile;
}

/** Deletes a file written by `writeTempConstraint`, with its directory. */
export function deleteTempConstraint(tempFile: string): void {
    fs.rmSync(path.dirname(tempFile), { recursive: true, force: true });
}
