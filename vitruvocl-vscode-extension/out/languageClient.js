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
exports.unitSourceOf = unitSourceOf;
exports.metamodelNotifier = metamodelNotifier;
/**
 * The client of the VitruvOCL language server (`language-server.jar`).
 */
const vscode = __importStar(require("vscode"));
const node_1 = require("vscode-languageclient/node");
const constraintLayout_1 = require("./constraintLayout");
const discovery_1 = require("./discovery");
const output_1 = require("./output");
/**
 * Starts the language server, if its jar is found.
 *
 * @returns the client, or `undefined` if the jar is missing
 */
function startLanguageClient(context) {
    const serverJar = (0, discovery_1.findLanguageServerJar)(context);
    if (!serverJar) {
        (0, output_1.logLine)('[OCL-LS] language-server.jar not found — LSP features disabled.');
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
/** The units request of a running language client. */
function unitSourceOf(client) {
    return {
        ready: () => client.state === node_1.State.Running,
        request: document => client.sendRequest(constraintLayout_1.CONSTRAINT_UNITS_METHOD, { uri: client.code2ProtocolConverter.asUri(document.uri) }),
    };
}
/** The JSON-RPC method of the language server's notification that adds metamodels. */
const ADD_METAMODELS_METHOD = 'vitruvocl/addMetamodels';
/**
 * Passes metamodels to the language server, each once; without a language server, or with an
 * older one that does not know the notification, nothing happens.
 */
function metamodelNotifier(client) {
    const sent = new Set();
    // a restarted language server knows none of them
    client?.onDidChangeState(event => {
        if (event.newState === node_1.State.Running)
            sent.clear();
    });
    return async (paths) => {
        const fresh = paths.filter(p => !sent.has(p));
        if (!client || fresh.length === 0)
            return;
        fresh.forEach(p => sent.add(p));
        try {
            await client.sendNotification(ADD_METAMODELS_METHOD, { paths: fresh });
        }
        catch (error) {
            fresh.forEach(p => sent.delete(p));
            (0, output_1.logLine)(`[OCL-LS] could not pass metamodels: ${error instanceof Error ? error.message : String(error)}`);
        }
    };
}
//# sourceMappingURL=languageClient.js.map