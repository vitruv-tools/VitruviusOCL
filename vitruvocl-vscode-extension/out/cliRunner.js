"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.evalBatchArgs = evalBatchArgs;
exports.evalArgs = evalArgs;
exports.runJava = runJava;
exports.writeTempConstraint = writeTempConstraint;
/**
 * Running the VitruvOCL CLI (`vitruvOCL.jar`) and the JSON it prints. Does not use `vscode`, so
 * that it can be tested with `node --test`.
 */
const child_process = __importStar(require("child_process"));
const util = __importStar(require("util"));
const fs = __importStar(require("fs"));
const os = __importStar(require("os"));
const path = __importStar(require("path"));
const execFile = util.promisify(child_process.execFile);
/** The `java` arguments of `eval-batch` for a whole constraint file. */
function evalBatchArgs(compilerPath, constraintFile, ecoreFiles, instanceFiles) {
    return [
        '-jar', compilerPath,
        'eval-batch',
        constraintFile,
        '--ecore', ecoreFiles.join(','),
        '--xmi', instanceFiles.join(',')
    ];
}
/** The `java` arguments of `eval` for a file holding one constraint. */
function evalArgs(compilerPath, constraintFile, ecoreFiles, instanceFiles) {
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
async function runJava(args) {
    return execFile('java', args).catch(err => ({
        stdout: err.stdout || '',
        stderr: err.stderr || err.message || ''
    }));
}
/** Writes the constraint to a new temporary `.ocl` file and returns its path. */
function writeTempConstraint(constraint) {
    const tempFile = path.join(os.tmpdir(), `vitruvocl-${Date.now()}.ocl`);
    fs.writeFileSync(tempFile, constraint, 'utf-8');
    return tempFile;
}
//# sourceMappingURL=cliRunner.js.map