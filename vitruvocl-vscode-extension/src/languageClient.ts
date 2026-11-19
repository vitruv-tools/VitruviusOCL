/**
 * The client of the VitruvOCL language server (`language-server.jar`).
 */
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    State,
} from 'vscode-languageclient/node';
import { CONSTRAINT_UNITS_METHOD, ConstraintUnitInfo } from './constraintLayout';
import { findLanguageServerJar } from './discovery';
import { logLine } from './output';
import { UnitSource } from './unitIndex';

/**
 * Starts the language server, if its jar is found.
 *
 * @returns the client, or `undefined` if the jar is missing
 */
export function startLanguageClient(context: vscode.ExtensionContext): LanguageClient | undefined {
    const serverJar = findLanguageServerJar(context);
    if (!serverJar) {
        logLine('[OCL-LS] language-server.jar not found — LSP features disabled.');
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

/** The units request of a running language client. */
export function unitSourceOf(client: LanguageClient): UnitSource {
    return {
        ready: () => client.state === State.Running,
        request: document => client.sendRequest<ConstraintUnitInfo[]>(
            CONSTRAINT_UNITS_METHOD,
            { uri: client.code2ProtocolConverter.asUri(document.uri as vscode.Uri) }),
    };
}
