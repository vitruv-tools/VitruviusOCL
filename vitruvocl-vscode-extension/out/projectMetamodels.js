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
exports.ProjectMetamodels = void 0;
/**
 * The metamodels of a project's Maven dependencies, for the CLI's runs and for the language
 * server, which gets every one it does not know yet.
 */
const vscode = __importStar(require("vscode"));
const discovery_1 = require("./discovery");
function mavenSetting() {
    const value = vscode.workspace.getConfiguration('vitruvocl').get('mavenDependencies');
    return value === 'offline' || value === 'off' ? value : 'online';
}
class ProjectMetamodels {
    constructor(dependencies, 
    /** Passes metamodels to the language server. */
    addToLanguageServer, log) {
        this.dependencies = dependencies;
        this.addToLanguageServer = addToLanguageServer;
        this.log = log;
    }
    /**
     * The metamodels from the dependencies that a run of the document's constraints on the model
     * files needs; the language server gets them too. Maven may download what the local
     * repository lacks unless `vitruvocl.mavenDependencies` says otherwise, which can take a
     * while the first time; the status bar shows it.
     */
    async forRun(document, workspaceEcores, modelFiles) {
        const setting = mavenSetting();
        return setting === 'off' ? [] : this.resolve(document, workspaceEcores, modelFiles, setting);
    }
    /**
     * Gives the language server the metamodels from the dependencies that the document's
     * constraints refer to, so that it knows their types. Only the local Maven repository is used:
     * opening a document never downloads anything.
     */
    async forEditor(document) {
        if (document.languageId !== 'vitruvocl' || document.uri.scheme !== 'file' || mavenSetting() === 'off')
            return;
        await this.resolve(document, await (0, discovery_1.findEcoreFiles)(document.uri), [], 'offline');
    }
    /** Resolves the dependencies again on the next run, e.g. after `mvn install`. */
    forget() {
        this.dependencies.forget();
    }
    async resolve(document, workspaceEcores, modelFiles, mode) {
        const workspaceRoot = (0, discovery_1.workspaceRootOf)(document.uri);
        if (!workspaceRoot)
            return [];
        const ecores = await vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'VitruvOCL: metamodels of the Maven dependencies' }, () => this.dependencies.ecoresFor({
            constraintFile: document.uri.fsPath,
            constraintsText: document.getText(),
            workspaceRoot,
            workspaceEcores,
            modelFiles,
        }, mode));
        if (ecores.length > 0) {
            this.log(`[Maven] metamodels from the dependencies: ${ecores.map(file => vscode.workspace.asRelativePath(file)).join(', ')}`);
        }
        await this.addToLanguageServer(ecores);
        return ecores;
    }
}
exports.ProjectMetamodels = ProjectMetamodels;
//# sourceMappingURL=projectMetamodels.js.map