/**
 * The client of the VitruvOCL language server (`language-server.jar`).
 */
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
} from 'vscode-languageclient/node';
import { findLanguageServerJar } from './discovery';

/**
 * Starts the language server, if its jar is found.
 *
 * @returns the client, or `undefined` if the jar is missing
 */
export function startLanguageClient(context: vscode.ExtensionContext): LanguageClient | undefined {
    const serverJar = findLanguageServerJar(context);
    if (!serverJar) {
        console.warn('[OCL-LS] language-server.jar not found — LSP features disabled.');
        return undefined;
    }

    const serverOptions: ServerOptions = {
        command: 'java',
        args: ['-jar', serverJar],
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ language: 'vitruvocl' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.ecore'),
        },
    };

    const languageClient = new LanguageClient(
        'vitruvocl-ls',
        'VitruvOCL Language Server',
        serverOptions,
        clientOptions
    );

    languageClient.start();
    context.subscriptions.push(languageClient);
    return languageClient;
}
