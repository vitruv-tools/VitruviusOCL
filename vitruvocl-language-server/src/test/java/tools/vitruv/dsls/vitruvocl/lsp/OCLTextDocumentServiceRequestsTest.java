/* ******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package tools.vitruv.dsls.vitruvocl.lsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;

/**
 * Tests for {@link OCLTextDocumentService}: document lifecycle, debounced analysis, request
 * delegation to the providers and quick-fix code actions.
 */
class OCLTextDocumentServiceRequestsTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");
  private static final String URI = "file:///test.ocl";
  private static final String VALID = "context spaceMission::Spacecraft inv a: self.mass > 0";
  private static final String TYPE_ERROR =
      "context spaceMission::Spacecraft inv bad: self.mass = \"x\"";
  private static final long WAIT_MS = 5000;

  private OCLTextDocumentService service;
  private LanguageClient client;

  @BeforeEach
  void setUp() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    service = new OCLTextDocumentService(new DocumentAnalyzer(wrapper), wrapper);
    client = mock(LanguageClient.class);
    service.setClient(client);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static TextDocumentIdentifier doc() {
    return new TextDocumentIdentifier(URI);
  }

  private void open(String text) {
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI, "ocl", 1, text)));
  }

  /** Waits until the debounced analysis for {@link #URI} has been published {@code n} times. */
  private PublishDiagnosticsParams awaitPublished(int n) {
    ArgumentCaptor<PublishDiagnosticsParams> captor =
        ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, timeout(WAIT_MS).times(n)).publishDiagnostics(captor.capture());
    return captor.getValue();
  }

  private static Position at(String text, String token) {
    return new Position(0, text.indexOf(token) + 1);
  }

  // ── Lifecycle & analysis ──────────────────────────────────────────────────

  @Test
  void didOpen_publishesDiagnosticsAfterDebounce() {
    open(TYPE_ERROR);

    PublishDiagnosticsParams published = awaitPublished(1);

    assertThat(published.getUri()).isEqualTo(URI);
    assertThat(published.getDiagnostics())
        .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
  }

  @Test
  void didChange_reanalysesWithNewText_andIgnoresEmptyChanges() {
    open(TYPE_ERROR);
    awaitPublished(1);

    VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier(URI, 2);
    service.didChange(new DidChangeTextDocumentParams(id, List.of()));
    service.didChange(
        new DidChangeTextDocumentParams(id, List.of(new TextDocumentContentChangeEvent(VALID))));

    assertThat(awaitPublished(2).getDiagnostics()).isEmpty();
  }

  @Test
  void didClose_clearsDiagnosticsAndForgetsAnalysis() throws Exception {
    open(VALID);
    awaitPublished(1);

    service.didClose(new DidCloseTextDocumentParams(doc()));

    assertThat(awaitPublished(2).getDiagnostics()).isEmpty();
    assertThat(service.hover(new HoverParams(doc(), at(VALID, "self"))).get()).isNull();
  }

  @Test
  void withoutClient_analysisAndCloseStillWork() throws Exception {
    service.setClient(null);
    open(VALID);

    long deadline = System.currentTimeMillis() + WAIT_MS;
    while (service.hover(new HoverParams(doc(), at(VALID, "self"))).get() == null
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }

    assertThat(service.hover(new HoverParams(doc(), at(VALID, "self"))).get()).isNotNull();
    service.didClose(new DidCloseTextDocumentParams(doc()));
    verify(client, never()).publishDiagnostics(any());
  }

  @Test
  void didSave_doesNotTriggerAnalysis() {
    service.didSave(new DidSaveTextDocumentParams(doc()));

    verify(client, after(400).never()).publishDiagnostics(any());
  }

  @Test
  void reanalyzeAll_republishesEveryOpenDocument() {
    open(VALID);
    awaitPublished(1);

    service.reanalyzeAll();

    awaitPublished(2);
  }

  @Test
  void analyzerFailure_isSwallowedAndPublishesNothing() throws Exception {
    DocumentAnalyzer failing = mock(DocumentAnalyzer.class);
    when(failing.analyze(anyString())).thenThrow(new IllegalStateException("boom"));
    OCLTextDocumentService broken = new OCLTextDocumentService(failing, new MetamodelWrapper());
    broken.setClient(client);
    try {
      broken.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI, "ocl", 1, VALID)));

      verify(failing, timeout(WAIT_MS)).analyze(VALID);
      verify(client, after(200).never()).publishDiagnostics(any());
      assertThat(broken.hover(new HoverParams(doc(), new Position(0, 0))).get()).isNull();
    } finally {
      broken.shutdown();
    }
  }

  // ── Request delegation ────────────────────────────────────────────────────

  @Test
  void requestsBeforeAnalysis_returnEmptyResults() throws Exception {
    Position pos = new Position(0, 0);

    assertThat(service.hover(new HoverParams(doc(), pos)).get()).isNull();
    assertThat(service.definition(new DefinitionParams(doc(), pos)).get().getLeft()).isEmpty();
    assertThat(service.documentSymbol(new DocumentSymbolParams(doc())).get()).isEmpty();
    assertThat(
            service.inlayHint(new InlayHintParams(doc(), new Range(pos, new Position(1, 0)))).get())
        .isEmpty();
    assertThat(service.signatureHelp(new SignatureHelpParams(doc(), pos)).get()).isNull();
    assertThat(service.completion(new CompletionParams(doc(), pos)).get().isLeft()).isTrue();
  }

  @Test
  void requestsAfterAnalysis_delegateToProviders() throws Exception {
    String text = "context spaceMission::Spacecraft inv a: let ps = self.payloads in ps.size() > 0";
    open(text);
    awaitPublished(1);

    assertThat(service.hover(new HoverParams(doc(), at(text, "self"))).get().getContents())
        .satisfies(c -> assertThat(c.getRight().getValue()).contains("spaceMission::Spacecraft"));
    assertThat(service.definition(new DefinitionParams(doc(), at(text, "self"))).get().getLeft())
        .singleElement()
        .satisfies(loc -> assertThat(loc.getUri()).endsWith("spaceMission.ecore"));
    assertThat(service.definition(new DefinitionParams(doc(), at(text, "size"))).get().getLeft())
        .isEmpty();
    assertThat(service.documentSymbol(new DocumentSymbolParams(doc())).get()).isNotEmpty();
    assertThat(
            service
                .inlayHint(
                    new InlayHintParams(
                        doc(), new Range(new Position(0, 0), new Position(0, text.length()))))
                .get())
        .isNotEmpty();
    assertThat(service.signatureHelp(new SignatureHelpParams(doc(), at(text, "size()"))).get())
        .isNull();
    assertThat(
            service
                .completion(new CompletionParams(doc(), new Position(0, text.indexOf("mass"))))
                .get()
                .getLeft())
        .isNotNull();
  }

  // ── Code actions ──────────────────────────────────────────────────────────

  private List<Either<Command, CodeAction>> codeActions(Diagnostic... diags) throws Exception {
    CodeActionParams params =
        new CodeActionParams(
            doc(),
            new Range(new Position(0, 0), new Position(0, 5)),
            new CodeActionContext(List.of(diags)));
    return service.codeAction(params).get();
  }

  private static Diagnostic diagWithData(Object data) {
    Diagnostic d =
        new Diagnostic(
            new Range(new Position(0, 3), new Position(0, 8)), "unknown op", null, "ocl");
    d.setData(data);
    return d;
  }

  @Test
  void codeAction_withStringSuggestion_buildsPreferredQuickFix() throws Exception {
    Diagnostic diag = diagWithData("size");

    List<Either<Command, CodeAction>> actions = codeActions(diag);

    assertThat(actions).singleElement().satisfies(a -> assertThat(a.isRight()).isTrue());
    CodeAction action = actions.get(0).getRight();
    assertThat(action.getTitle()).isEqualTo("Replace with 'size'");
    assertThat(action.getKind()).isEqualTo(CodeActionKind.QuickFix);
    assertThat(action.getIsPreferred()).isTrue();
    assertThat(action.getDiagnostics()).containsExactly(diag);
    assertThat(action.getEdit().getChanges().get(URI))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getNewText()).isEqualTo("size");
              assertThat(e.getRange()).isEqualTo(diag.getRange());
            });
  }

  @Test
  void codeAction_withJsonQuotedSuggestion_stripsQuotes() throws Exception {
    List<Either<Command, CodeAction>> actions = codeActions(diagWithData(new JsonPrimitive("max")));

    assertThat(actions.get(0).getRight().getTitle()).isEqualTo("Replace with 'max'");
  }

  @Test
  void codeAction_withoutUsableSuggestion_producesNothing() throws Exception {
    assertThat(
            codeActions(
                diagWithData(null), diagWithData(""), diagWithData("\"\""), diagWithData("  ")))
        .isEmpty();
  }

  @Test
  void codeAction_singleQuoteCharacter_isKeptVerbatim() throws Exception {
    List<Either<Command, CodeAction>> actions = codeActions(diagWithData("\""));

    assertThat(actions.get(0).getRight().getTitle()).isEqualTo("Replace with '\"'");
  }

  @Test
  void codeAction_mixedDiagnostics_onlySuggestionsBecomeActions() throws Exception {
    assertThat(codeActions(diagWithData("a"), diagWithData(null), diagWithData("b"))).hasSize(2);
    verify(client, times(0)).publishDiagnostics(any());
  }
}
