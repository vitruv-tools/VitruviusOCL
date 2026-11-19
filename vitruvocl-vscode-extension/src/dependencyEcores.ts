/**
 * The `.ecore` files a project gets from its Maven dependencies, extracted from their jars into a
 * cache folder so that the CLI and the language server can load them like the workspace's own.
 * Does not use `vscode`, so that it can be tested with `node --test`.
 */
import * as fs from 'fs';
import * as path from 'path';
import {
    bundleNameOf,
    EcoreInfo,
    nsUrisOfModels,
    parseEcore,
    providedBy,
    selectMetamodels,
} from './dependencyMetamodels';
import { findPom, MavenMode, resolveClasspath } from './mavenClasspath';
import { metamodelNamesIn } from './vsumFiles';
import { readZipEntries } from './zipEntries';

/** What the metamodels of the dependencies are chosen for. */
export interface MetamodelNeeds {
    /** The constraint file; the `pom.xml` nearest to it is the project's. */
    constraintFile: string;
    /** Its text, whose package names (`c1::...`) need metamodels. */
    constraintsText: string;
    workspaceRoot: string;
    /** The workspace's own `.ecore` files, which are not replaced. */
    workspaceEcores: string[];
    /** The model files, whose namespace URIs need metamodels; empty for the language server. */
    modelFiles: string[];
}

/** Resolves classpaths with Maven; replaced in tests. */
export type ClasspathResolver =
    (pom: string, workspaceRoot: string, cacheFolder: string, mode: MavenMode) => Promise<string[] | undefined>;

export class DependencyEcores {
    /** The `.ecore` files of each jar read so far, by jar path and modification time. */
    private readonly jarIndex = new Map<string, { mtimeMs: number; infos: EcoreInfo[] }>();
    /** One classpath resolution per `pom.xml` and mode at a time. */
    private readonly resolving = new Map<string, Promise<string[] | undefined>>();
    /** The `pom.xml` files Maven could not resolve in a mode, with their modification time then. */
    private readonly failed = new Map<string, number>();

    constructor(
        private readonly cacheFolder: string,
        private readonly log: (line: string) => void,
        private readonly resolver: ClasspathResolver =
            (pom, root, cache, mode) => resolveClasspath(pom, root, cache, mode, log)
    ) {}

    /**
     * The `.ecore` files from the project's dependencies that the models and constraints need and
     * the workspace does not provide, extracted into the cache folder.
     *
     * @param mode whether Maven may download what the local repository lacks
     * @returns their paths; empty if the project has no `pom.xml` or Maven cannot resolve it
     */
    async ecoresFor(needs: MetamodelNeeds, mode: MavenMode): Promise<string[]> {
        const pom = findPom(path.dirname(needs.constraintFile), needs.workspaceRoot);
        if (!pom) return [];
        const jars = await this.classpathOf(pom, needs.workspaceRoot, mode);
        if (!jars) return [];
        const provided = providedBy(needs.workspaceEcores.flatMap(file => {
            try {
                return [parseEcore(file, file, fs.readFileSync(file, 'utf-8'))];
            } catch {
                return [];
            }
        }));
        const selected = selectMetamodels(
            jars.flatMap(jar => this.ecoresIn(jar)),
            nsUrisOfModels(needs.modelFiles),
            metamodelNamesIn(needs.constraintsText),
            provided);
        return selected
            .map(info => this.extract(info))
            .filter((file): file is string => file !== undefined);
    }

    /**
     * Forgets the resolved classpaths and failures, so that the next run resolves the
     * dependencies with Maven again, e.g. after `mvn install`.
     */
    forget(): void {
        this.failed.clear();
        this.jarIndex.clear();
        if (!fs.existsSync(this.cacheFolder)) return;
        for (const file of fs.readdirSync(this.cacheFolder)) {
            if (/^classpath-.*\.txt$/.test(file)) {
                fs.rmSync(path.join(this.cacheFolder, file), { force: true });
            }
        }
    }

    /**
     * The classpath of the project, resolved once at a time; a project Maven could not resolve in
     * a mode is not tried again in it until its `pom.xml` changes or `forget` is called, so that
     * opening documents does not run Maven over and over.
     */
    private classpathOf(pom: string, workspaceRoot: string, mode: MavenMode): Promise<string[] | undefined> {
        const key = `${path.resolve(pom)}|${mode}`;
        const pending = this.resolving.get(key);
        if (pending) return pending;
        const modified = fs.statSync(pom).mtimeMs;
        if (this.failed.get(key) === modified) return Promise.resolve(undefined);
        const resolution = this.resolver(pom, workspaceRoot, this.cacheFolder, mode)
            .then(jars => {
                if (jars) {
                    this.failed.delete(key);
                } else {
                    this.failed.set(key, modified);
                    this.log(`[Maven] could not resolve the dependencies of ${pom}; their metamodels are not loaded`);
                }
                return jars;
            })
            .finally(() => this.resolving.delete(key));
        this.resolving.set(key, resolution);
        return resolution;
    }

    /** The `.ecore` files in the jar; an unreadable jar has none. */
    private ecoresIn(jar: string): EcoreInfo[] {
        let mtimeMs: number;
        try {
            mtimeMs = fs.statSync(jar).mtimeMs;
        } catch {
            return [];
        }
        const known = this.jarIndex.get(jar);
        if (known && known.mtimeMs === mtimeMs) return known.infos;
        let infos: EcoreInfo[] = [];
        try {
            const entries = readZipEntries(jar, name =>
                name.toLowerCase().endsWith('.ecore') || name === 'META-INF/MANIFEST.MF');
            const manifest = entries.find(entry => entry.name === 'META-INF/MANIFEST.MF');
            const bundle = bundleNameOf(jar, manifest?.data.toString('utf-8'));
            infos = entries
                .filter(entry => entry !== manifest)
                .map(entry => parseEcore(jar, entry.name, entry.data.toString('utf-8'), bundle));
        } catch (error: unknown) {
            this.log(`[Maven] cannot read ${jar}: ${error instanceof Error ? error.message : String(error)}`);
        }
        this.jarIndex.set(jar, { mtimeMs, infos });
        return infos;
    }

    /**
     * Writes the metamodel to the cache folder where it is in an Eclipse installation,
     * `plugins/<bundle>/<path in the jar>`, so that other metamodels' relative references
     * (`../../<bundle>/model/x.ecore`) and `platform:/plugin/<bundle>/...` references find it.
     */
    private extract(info: EcoreInfo): string | undefined {
        const plugins = path.resolve(this.cacheFolder, 'plugins');
        const target = path.resolve(plugins, info.bundle, ...info.entry.split('/'));
        // names from a jar must not lead out of the cache folder (`../`, absolute paths)
        if (!info.bundle || !target.startsWith(plugins + path.sep)) {
            this.log(`[Maven] skipping ${info.entry} of ${info.origin}: its path leaves the cache folder`);
            return undefined;
        }
        try {
            if (!fs.existsSync(target) || fs.readFileSync(target, 'utf-8') !== info.content) {
                fs.mkdirSync(path.dirname(target), { recursive: true });
                fs.writeFileSync(target, info.content, 'utf-8');
            }
            return target;
        } catch (error: unknown) {
            this.log(`[Maven] cannot extract ${info.entry} of ${info.origin}: ${error instanceof Error ? error.message : String(error)}`);
            return undefined;
        }
    }
}
