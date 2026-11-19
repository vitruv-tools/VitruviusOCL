/**
 * The classpath of a Maven project, as `mvn dependency:build-classpath` resolves it, cached until
 * its `pom.xml` changes. Does not use `vscode`, so that it can be tested with `node --test`.
 */
import * as child_process from 'child_process';
import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';

/** How long resolving a classpath may take; an online resolution downloads artifacts. */
const RESOLVE_TIMEOUT_MS = 5 * 60 * 1000;

/** Whether Maven may download artifacts, or only use the local repository. */
export type MavenMode = 'offline' | 'online';

/**
 * The nearest `pom.xml` from the folder upwards, not above `root`.
 *
 * @returns the path, or `undefined` if there is none
 */
export function findPom(fromFolder: string, root: string): string | undefined {
    const top = path.resolve(root);
    let folder = path.resolve(fromFolder);
    for (;;) {
        const pom = path.join(folder, 'pom.xml');
        if (fs.existsSync(pom)) return pom;
        const parent = path.dirname(folder);
        if (folder === top || parent === folder || !folder.startsWith(top)) return undefined;
        folder = parent;
    }
}

/** The Maven command: the wrapper of the workspace if it has one, else `mvn` from the path. */
export function mavenCommand(workspaceRoot: string, platform: NodeJS.Platform = process.platform): string {
    const wrapper = path.join(workspaceRoot, platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
    if (fs.existsSync(wrapper)) return wrapper;
    return platform === 'win32' ? 'mvn.cmd' : 'mvn';
}

/**
 * The shell command line that runs Maven to write the classpath of the pom to the output file.
 * The paths are not part of it: the shell takes them from the environment variables `VOCL_POM`
 * and `VOCL_OUT`, so that no character in a path, not even `%` for `cmd.exe`, is interpreted.
 */
export function mavenCommandLine(command: string, mode: MavenMode, platform: NodeJS.Platform = process.platform): string {
    const variable = (name: string) => (platform === 'win32' ? `"%${name}%"` : `"$${name}"`);
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
const running = new Set<child_process.ChildProcess>();

/** Ends a process and every process it started (Maven runs Java under a shell). */
function killTree(child: child_process.ChildProcess): void {
    if (child.pid === undefined || child.exitCode !== null) return;
    if (process.platform === 'win32') {
        child_process.spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
    } else {
        try {
            process.kill(-child.pid, 'SIGTERM'); // the group of the detached shell
        } catch {
            child.kill('SIGTERM');
        }
    }
}

/** Stops every Maven process still running. */
export function stopMaven(): void {
    running.forEach(killTree);
}

/** Runs Maven and resolves with its exit code; the output is passed to `log`. */
function runMaven(commandLine: string, env: NodeJS.ProcessEnv, cwd: string, log: (line: string) => void): Promise<number> {
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
        const forward = (chunk: Buffer) => chunk.toString().split(/\r?\n/).filter(Boolean).forEach(line => log(`[Maven] ${line}`));
        child.stdout?.on('data', forward);
        child.stderr?.on('data', forward);
        const done = (code: number) => {
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
export function parseClasspath(text: string): string[] {
    return text.split(path.delimiter).map(entry => entry.trim()).filter(entry => entry.endsWith('.jar'));
}

/** Where the classpath of the pom is cached. */
export function classpathCacheFile(pom: string, cacheFolder: string): string {
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
export async function resolveClasspath(
    pom: string,
    workspaceRoot: string,
    cacheFolder: string,
    mode: MavenMode,
    log: (line: string) => void
): Promise<string[] | undefined> {
    const cached = classpathCacheFile(pom, cacheFolder);
    if (fs.existsSync(cached) && fs.statSync(cached).mtimeMs >= fs.statSync(pom).mtimeMs) {
        return parseClasspath(fs.readFileSync(cached, 'utf-8'));
    }
    fs.mkdirSync(cacheFolder, { recursive: true });
    const command = mavenCommand(workspaceRoot);
    const env = { ...process.env, VOCL_MVN: command, VOCL_POM: pom, VOCL_OUT: cached };
    for (const attempt of mode === 'online' ? ['offline', 'online'] as const : ['offline'] as const) {
        log(`[Maven] resolving the dependencies of ${pom}${attempt === 'offline' ? ' (offline)' : ''}`);
        const code = await runMaven(mavenCommandLine(command, attempt), env, path.dirname(pom), log);
        if (code === 0 && fs.existsSync(cached)) {
            return parseClasspath(fs.readFileSync(cached, 'utf-8'));
        }
        fs.rmSync(cached, { force: true });
    }
    return undefined;
}
