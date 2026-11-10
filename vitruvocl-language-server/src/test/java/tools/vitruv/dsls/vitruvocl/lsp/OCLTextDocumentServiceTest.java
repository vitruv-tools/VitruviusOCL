/* ******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Max Oesterle - initial API and implementation
 *******************************************************************************/
package tools.vitruv.dsls.vitruvocl.lsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.vitruv.dsls.vitruvocl.pipeline.MetamodelWrapper;

class OCLTextDocumentServiceTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");
  private static final String URI = "file:///tmp/closed.ocl";
  private static final String MARKER_URI = "file:///tmp/marker.ocl";
  private static final String INVALID_OCL = "kontext spaceMission::Spacecraft inv bad: true";

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

  @Test
  void closingDocumentCancelsPendingAnalysis() {
    open(URI);
    service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(URI)));
    awaitAnalysisQueueDrained();

    ArgumentCaptor<PublishDiagnosticsParams> published =
        ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(published.capture());
    List<PublishDiagnosticsParams> forClosed =
        published.getAllValues().stream().filter(p -> URI.equals(p.getUri())).toList();
    assertThat(forClosed)
        .as("no diagnostics may be published for a document after it was closed")
        .isNotEmpty()
        .allSatisfy(params -> assertThat(params.getDiagnostics()).isEmpty());
  }

  @Test
  void openDocumentStillGetsDiagnostics() {
    open(URI);
    verify(client, timeout(10_000))
        .publishDiagnostics(
            argThat(p -> URI.equals(p.getUri()) && !p.getDiagnostics().isEmpty()));
  }

  private void open(String uri) {
    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(uri, "vitruvocl", 1, INVALID_OCL)));
  }

  /**
   * Analyses run on one debounce thread in scheduling order, so once a document opened afterwards
   * has been published, every earlier scheduled analysis has run (or was cancelled).
   */
  private void awaitAnalysisQueueDrained() {
    open(MARKER_URI);
    verify(client, timeout(10_000))
        .publishDiagnostics(argThat(p -> MARKER_URI.equals(p.getUri())));
  }
}
