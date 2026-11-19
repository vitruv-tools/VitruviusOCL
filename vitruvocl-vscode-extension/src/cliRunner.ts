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
    errors: EvalError[];
    warnings: string[];
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
    const tempFile = path.join(os.tmpdir(), `vitruvocl-${Date.now()}.ocl`);
    fs.writeFileSync(tempFile, constraint, 'utf-8');
    return tempFile;
}
