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
exports.DependencyEcores = void 0;
/**
 * The `.ecore` files a project gets from its Maven dependencies, extracted from their jars into a
 * cache folder so that the CLI and the language server can load them like the workspace's own.
 * Does not use `vscode`, so that it can be tested with `node --test`.
 */
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const dependencyMetamodels_1 = require("./dependencyMetamodels");
const mavenClasspath_1 = require("./mavenClasspath");
const vsumFiles_1 = require("./vsumFiles");
const zipEntries_1 = require("./zipEntries");
class DependencyEcores {
    constructor(cacheFolder, log, resolver = (pom, root, cache, mode) => (0, mavenClasspath_1.resolveClasspath)(pom, root, cache, mode, log)) {
        this.cacheFolder = cacheFolder;
        this.log = log;
        this.resolver = resolver;
        /** The `.ecore` files of each jar read so far, by jar path and modification time. */
        this.jarIndex = new Map();
        /** One classpath resolution per `pom.xml` and mode at a time. */
        this.resolving = new Map();
        /** The `pom.xml` files Maven could not resolve in a mode, with their modification time then. */
        this.failed = new Map();
    }
    /**
     * The `.ecore` files from the project's dependencies that the models and constraints need and
     * the workspace does not provide, extracted into the cache folder.
     *
     * @param mode whether Maven may download what the local repository lacks
     * @returns their paths; empty if the project has no `pom.xml` or Maven cannot resolve it
     */
    async ecoresFor(needs, mode) {
        const pom = (0, mavenClasspath_1.findPom)(path.dirname(needs.constraintFile), needs.workspaceRoot);
        if (!pom)
            return [];
        const jars = await this.classpathOf(pom, needs.workspaceRoot, mode);
        if (!jars)
            return [];
        const provided = (0, dependencyMetamodels_1.providedBy)(needs.workspaceEcores.flatMap(file => {
            try {
                return [(0, dependencyMetamodels_1.parseEcore)(file, file, fs.readFileSync(file, 'utf-8'))];
            }
            catch {
                return [];
            }
        }));
        const selected = (0, dependencyMetamodels_1.selectMetamodels)(jars.flatMap(jar => this.ecoresIn(jar)), (0, dependencyMetamodels_1.nsUrisOfModels)(needs.modelFiles), (0, vsumFiles_1.metamodelNamesIn)(needs.constraintsText), provided);
        return selected
            .map(info => this.extract(info))
            .filter((file) => file !== undefined);
    }
    /**
     * Forgets the resolved classpaths and failures, so that the next run resolves the
     * dependencies with Maven again, e.g. after `mvn install`.
     */
    forget() {
        this.failed.clear();
        this.jarIndex.clear();
        if (!fs.existsSync(this.cacheFolder))
            return;
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
    classpathOf(pom, workspaceRoot, mode) {
        const key = `${path.resolve(pom)}|${mode}`;
        const pending = this.resolving.get(key);
        if (pending)
            return pending;
        const modified = fs.statSync(pom).mtimeMs;
        if (this.failed.get(key) === modified)
            return Promise.resolve(undefined);
        const resolution = this.resolver(pom, workspaceRoot, this.cacheFolder, mode)
            .then(jars => {
            if (jars) {
                this.failed.delete(key);
            }
            else {
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
    ecoresIn(jar) {
        let mtimeMs;
        try {
            mtimeMs = fs.statSync(jar).mtimeMs;
        }
        catch {
            return [];
        }
        const known = this.jarIndex.get(jar);
        if (known && known.mtimeMs === mtimeMs)
            return known.infos;
        let infos = [];
        try {
            const entries = (0, zipEntries_1.readZipEntries)(jar, name => name.toLowerCase().endsWith('.ecore') || name === 'META-INF/MANIFEST.MF');
            const manifest = entries.find(entry => entry.name === 'META-INF/MANIFEST.MF');
            const bundle = (0, dependencyMetamodels_1.bundleNameOf)(jar, manifest?.data.toString('utf-8'));
            infos = entries
                .filter(entry => entry !== manifest)
                .map(entry => (0, dependencyMetamodels_1.parseEcore)(jar, entry.name, entry.data.toString('utf-8'), bundle));
        }
        catch (error) {
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
    extract(info) {
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
        }
        catch (error) {
            this.log(`[Maven] cannot extract ${info.entry} of ${info.origin}: ${error instanceof Error ? error.message : String(error)}`);
            return undefined;
        }
    }
}
exports.DependencyEcores = DependencyEcores;
//# sourceMappingURL=dependencyEcores.js.map