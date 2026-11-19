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
exports.findPom = findPom;
exports.mavenCommand = mavenCommand;
exports.mavenCommandLine = mavenCommandLine;
exports.stopMaven = stopMaven;
exports.parseClasspath = parseClasspath;
exports.classpathCacheFile = classpathCacheFile;
exports.resolveClasspath = resolveClasspath;
/**
 * The classpath of a Maven project, as `mvn dependency:build-classpath` resolves it, cached until
 * its `pom.xml` changes. Does not use `vscode`, so that it can be tested with `node --test`.
 */
const child_process = __importStar(require("child_process"));
const crypto = __importStar(require("crypto"));
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
/** How long resolving a classpath may take; an online resolution downloads artifacts. */
const RESOLVE_TIMEOUT_MS = 5 * 60 * 1000;
/**
 * The nearest `pom.xml` from the folder upwards, not above `root`.
 *
 * @returns the path, or `undefined` if there is none
 */
function findPom(fromFolder, root) {
    const top = path.resolve(root);
    let folder = path.resolve(fromFolder);
    for (;;) {
        const pom = path.join(folder, 'pom.xml');
        if (fs.existsSync(pom))
            return pom;
        const parent = path.dirname(folder);
        if (folder === top || parent === folder || !folder.startsWith(top))
            return undefined;
        folder = parent;
    }
}
/** The Maven command: the wrapper of the workspace if it has one, else `mvn` from the path. */
function mavenCommand(workspaceRoot, platform = process.platform) {
    const wrapper = path.join(workspaceRoot, platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
    if (fs.existsSync(wrapper))
        return wrapper;
    return platform === 'win32' ? 'mvn.cmd' : 'mvn';
}
/**
 * The shell command line that runs Maven to write the classpath of the pom to the output file.
 * The paths are not part of it: the shell takes them from the environment variables `VOCL_POM`
 * and `VOCL_OUT`, so that no character in a path, not even `%` for `cmd.exe`, is interpreted.
 */
function mavenCommandLine(command, mode, platform = process.platform) {
    const variable = (name) => (platform === 'win32' ? `"%${name}%"` : `"$${name}"`);
    const executable = platform === 'win32' ? variable('VOCL_MVN') : `sh ${variable('VOCL_MVN')}`;
    return [
        command.endsWith('mvnw') || command.endsWith('mvnw.cmd') ? executable : variable('VOCL_MVN'),
        ...(mode === 'offline' ? ['-o'] : []),
        '-q', '-B', 'dependency:build-classpath',
        `"-Dmdep.outputFile=${platform === 'win32' ? '%VOCL_OUT%' : '$VOCL_OUT'}"`,
        '-f', variable('VOCL_POM'),
    ].join(' ');
}
/** The Maven processes running, so that they can be stopped when the extension is. */
const running = new Set();
/** Ends a process and every process it started (Maven runs Java under a shell). */
function killTree(child) {
    if (child.pid === undefined || child.exitCode !== null)
        return;
    if (process.platform === 'win32') {
        child_process.spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
    }
    else {
        try {
            process.kill(-child.pid, 'SIGTERM'); // the group of the detached shell
        }
        catch {
            child.kill('SIGTERM');
        }
    }
}
/** Stops every Maven process still running. */
function stopMaven() {
    running.forEach(killTree);
}
/** Runs Maven and resolves with its exit code; the output is passed to `log`. */
function runMaven(commandLine, env, cwd, log) {
    return new Promise(resolve => {
        // a .cmd file only runs through a shell; on POSIX the shell gets a process group of its own
        const child = child_process.spawn(commandLine, {
            cwd, env, shell: true, windowsHide: true, detached: process.platform !== 'win32',
        });
        running.add(child);
        const timer = setTimeout(() => {
            log(`[Maven] stopped after ${RESOLVE_TIMEOUT_MS / 1000} s`);
            killTree(child);
        }, RESOLVE_TIMEOUT_MS);
        const forward = (chunk) => chunk.toString().split(/\r?\n/).filter(Boolean).forEach(line => log(`[Maven] ${line}`));
        child.stdout?.on('data', forward);
        child.stderr?.on('data', forward);
        const done = (code) => {
            clearTimeout(timer);
            running.delete(child);
            resolve(code);
        };
        child.on('error', error => {
            log(`[Maven] ${error.message}`);
            done(-1);
        });
        child.on('close', code => done(code ?? -1));
    });
}
/** The jars of a classpath written by `dependency:build-classpath`. */
function parseClasspath(text) {
    return text.split(path.delimiter).map(entry => entry.trim()).filter(entry => entry.endsWith('.jar'));
}
/** Where the classpath of the pom is cached. */
function classpathCacheFile(pom, cacheFolder) {
    const key = crypto.createHash('sha256').update(path.resolve(pom)).digest('hex').slice(0, 16);
    return path.join(cacheFolder, `classpath-${key}.txt`);
}
/**
 * The jars on the classpath of the project of `pom`, resolved by Maven: offline from the local
 * repository, and, in `online` mode, with downloads if that fails. The result is kept in
 * `cacheFolder` and reused until the `pom.xml` changes.
 *
 * @returns the jars, or `undefined` if Maven could not resolve them
 */
async function resolveClasspath(pom, workspaceRoot, cacheFolder, mode, log) {
    const cached = classpathCacheFile(pom, cacheFolder);
    if (fs.existsSync(cached) && fs.statSync(cached).mtimeMs >= fs.statSync(pom).mtimeMs) {
        return parseClasspath(fs.readFileSync(cached, 'utf-8'));
    }
    fs.mkdirSync(cacheFolder, { recursive: true });
    const command = mavenCommand(workspaceRoot);
    const env = { ...process.env, VOCL_MVN: command, VOCL_POM: pom, VOCL_OUT: cached };
    for (const attempt of mode === 'online' ? ['offline', 'online'] : ['offline']) {
        log(`[Maven] resolving the dependencies of ${pom}${attempt === 'offline' ? ' (offline)' : ''}`);
        const code = await runMaven(mavenCommandLine(command, attempt), env, path.dirname(pom), log);
        if (code === 0 && fs.existsSync(cached)) {
            return parseClasspath(fs.readFileSync(cached, 'utf-8'));
        }
        fs.rmSync(cached, { force: true });
    }
    return undefined;
}
//# sourceMappingURL=mavenClasspath.js.map