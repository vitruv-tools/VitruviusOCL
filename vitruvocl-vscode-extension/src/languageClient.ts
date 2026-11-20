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

/** The JSON-RPC method of the language server's notification that adds metamodels. */
const ADD_METAMODELS_METHOD = 'vitruvocl/addMetamodels';

/**
 * Passes metamodels to the language server, each once; without a language server, or with an
 * older one that does not know the notification, nothing happens.
 */
export function metamodelNotifier(client: LanguageClient | undefined): (paths: string[]) => Promise<void> {
    const sent = new Set<string>();
    // a restarted language server knows none of them
    client?.onDidChangeState(event => {
        if (event.newState === State.Running) sent.clear();
    });
    return async paths => {
        const fresh = paths.filter(p => !sent.has(p));
        if (!client || fresh.length === 0) return;
        fresh.forEach(p => sent.add(p));
        try {
            await client.sendNotification(ADD_METAMODELS_METHOD, { paths: fresh });
        } catch (error: unknown) {
            fresh.forEach(p => sent.delete(p));
            logLine(`[OCL-LS] could not pass metamodels: ${error instanceof Error ? error.message : String(error)}`);
        }
    };
}
