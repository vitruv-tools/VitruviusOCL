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
exports.startLanguageClient = startLanguageClient;
/**
 * The client of the VitruvOCL language server (`language-server.jar`).
 */
const vscode = __importStar(require("vscode"));
const node_1 = require("vscode-languageclient/node");
const discovery_1 = require("./discovery");
/**
 * Starts the language server, if its jar is found.
 *
 * @returns the client, or `undefined` if the jar is missing
 */
function startLanguageClient(context) {
    const serverJar = (0, discovery_1.findLanguageServerJar)(context);
    if (!serverJar) {
        console.warn('[OCL-LS] language-server.jar not found — LSP features disabled.');
        return undefined;
    }
    const serverOptions = {
        command: 'java',
        args: ['-jar', serverJar],
    };
    const clientOptions = {
        documentSelector: [{ language: 'vitruvocl' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.ecore'),
        },
    };
    const languageClient = new node_1.LanguageClient('vitruvocl-ls', 'VitruvOCL Language Server', serverOptions, clientOptions);
    languageClient.start();
    context.subscriptions.push(languageClient);
    return languageClient;
}
//# sourceMappingURL=languageClient.js.map