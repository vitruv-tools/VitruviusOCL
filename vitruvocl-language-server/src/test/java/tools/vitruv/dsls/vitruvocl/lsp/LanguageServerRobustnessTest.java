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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.dsls.vitruvocl.pipeline.MetamodelWrapper;

/**
 * Robustness of the language server against broken metamodels and pathological documents: a
 * malformed {@code .ecore} must neither stop the server from initializing nor drop the previously
 * loaded version on hot-reload, and a document too deeply nested to analyze still gets a
 * diagnostic. A crash inside an analysis pass is reported instead of silently dropped, and
 * reloading or unloading a metamodel also replaces or removes its nested packages.
 */
class LanguageServerRobustnessTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");
  private static final String BROKEN_ECORE = "<?xml version=\"1.0\"?>\n<ecore:EPackage name=";
  private static final String VALID_OCL = "context spaceMission::Spacecraft inv ok: true";

  @TempDir Path workspace;

  private OCLTextDocumentService service;
  private OCLLanguageServer server;

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.shutdown();
    }
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void brokenEcoreDoesNotStopInitialization() throws Exception {
    Files.copy(SPACEMISSION_ECORE, workspace.resolve("spaceMission.ecore"));
    Files.writeString(workspace.resolve("broken.ecore"), BROKEN_ECORE);

    server = new OCLLanguageServer();
    LanguageClient client = mock(LanguageClient.class);
    server.connect(client);
    InitializeParams params = new InitializeParams();
    params.setWorkspaceFolders(List.of(new WorkspaceFolder(workspace.toUri().toString(), "ws")));

    InitializeResult result = server.initialize(params).get();
    assertThat(result.getCapabilities()).isNotNull();

    // the valid metamodel next to the broken one is still loaded
    String uri = workspace.resolve("ok.ocl").toUri().toString();
    server
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "vitruvocl", 1, VALID_OCL)));
    verify(client, timeout(10_000))
        .publishDiagnostics(argThat(p -> uri.equals(p.getUri()) && p.getDiagnostics().isEmpty()));
  }

  @Test
  void loadingBrokenEcoreReportsIoException() throws IOException {
    Path broken = workspace.resolve("broken.ecore");
    Files.writeString(broken, BROKEN_ECORE);

    assertThatThrownBy(() -> new MetamodelWrapper().loadMetamodel(broken))
        .isInstanceOf(IOException.class);
  }

  @Test
  void failedHotReloadKeepsPreviousMetamodel() throws IOException {
    Path ecore = workspace.resolve("spaceMission.ecore");
    Files.copy(SPACEMISSION_ECORE, ecore);
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(ecore);
    service = new OCLTextDocumentService(new DocumentAnalyzer(wrapper), wrapper);
    OCLWorkspaceService workspaceService = new OCLWorkspaceService(wrapper, service);

    Files.writeString(ecore, BROKEN_ECORE);
    workspaceService.didChangeWatchedFiles(
        new DidChangeWatchedFilesParams(
            List.of(new FileEvent(ecore.toUri().toString(), FileChangeType.Changed))));

    assertThat(wrapper.resolveEClass("spaceMission", "Spacecraft"))
        .as("a broken file on disk must not unload the metamodel that was loaded before")
        .isNotNull();
  }

  @Test
  void deeplyNestedDocumentStillGetsDiagnostic() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    service = new OCLTextDocumentService(new DocumentAnalyzer(wrapper), wrapper);
    LanguageClient client = mock(LanguageClient.class);
    service.setClient(client);

    int depth = 20_000;
    String text =
        "context spaceMission::Spacecraft inv deep: "
            + "(".repeat(depth)
            + "true"
            + ")".repeat(depth);
    String uri = "file:///tmp/deep.ocl";
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "vitruvocl", 1, text)));

    verify(client, timeout(10_000))
        .publishDiagnostics(
            argThat(
                p ->
                    uri.equals(p.getUri())
                        && p.getDiagnostics().stream()
                            .anyMatch(d -> d.getMessage().getLeft().contains("nested too deeply"))));
  }

  @Test
  void brokenEcoreIsNotCachedAfterFailedLoad() throws IOException {
    Path ecore = workspace.resolve("spaceMission.ecore");
    Files.writeString(ecore, BROKEN_ECORE);
    MetamodelWrapper wrapper = new MetamodelWrapper();
    assertThatThrownBy(() -> wrapper.loadMetamodel(ecore)).isInstanceOf(IOException.class);

    // once the file is fixed, the same wrapper loads it instead of returning the failed resource
    Files.copy(SPACEMISSION_ECORE, ecore, StandardCopyOption.REPLACE_EXISTING);
    wrapper.loadMetamodel(ecore);
    assertThat(wrapper.resolveEClass("spaceMission", "Spacecraft")).isNotNull();
  }

  @Test
  void validReloadAfterFailedReloadPicksUpChanges() throws IOException {
    Path ecore = workspace.resolve("spaceMission.ecore");
    Files.copy(SPACEMISSION_ECORE, ecore);
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(ecore);

    Files.writeString(ecore, BROKEN_ECORE);
    assertThatThrownBy(() -> wrapper.reloadMetamodel(ecore)).isInstanceOf(IOException.class);

    String withNewClass =
        Files.readString(SPACEMISSION_ECORE)
            .replace(
                "</ecore:EPackage>",
                "  <eClassifiers xsi:type=\"ecore:EClass\" name=\"Probe\"/>\n</ecore:EPackage>");
    Files.writeString(ecore, withNewClass);
    wrapper.reloadMetamodel(ecore);

    assertThat(wrapper.resolveEClass("spaceMission", "Probe")).isNotNull();
    assertThat(wrapper.resolveEClass("spaceMission", "Spacecraft")).isNotNull();
  }

  @Test
  void crashInAnalysisPassIsReported() throws IOException {
    MetamodelWrapper crashing =
        new MetamodelWrapper() {
          @Override
          public EClass resolveEClass(String metamodelName, String className) {
            throw new IllegalStateException("resolver exploded");
          }

          @Override
          public EClass resolveEClassByShortName(String shortName) {
            throw new IllegalStateException("resolver exploded");
          }
        };
    crashing.loadMetamodel(SPACEMISSION_ECORE);

    DocumentAnalysis analysis = new DocumentAnalyzer(crashing).analyze(VALID_OCL);

    assertThat(analysis.getDiagnostics())
        .as("a crashed analysis pass must surface as a diagnostic")
        .anySatisfy(d -> assertThat(d.getMessage().getLeft()).contains("resolver exploded"));
  }

  private static String nestedEcore(String innerClass) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
            name="robustOuter" nsURI="http://test/robust/outer" nsPrefix="robustOuter">
          <eSubpackages name="robustInner" nsURI="http://test/robust/inner" nsPrefix="robustInner">
            <eClassifiers xsi:type="ecore:EClass" name="%s"/>
          </eSubpackages>
        </ecore:EPackage>
        """
        .formatted(innerClass);
  }

  @Test
  void reloadReplacesNestedPackages() throws IOException {
    Path ecore = workspace.resolve("nested.ecore");
    Files.writeString(ecore, nestedEcore("Before"));
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(ecore);
    assertThat(wrapper.resolveEClass("robustInner", "Before")).isNotNull();

    Files.writeString(ecore, nestedEcore("After"));
    wrapper.reloadMetamodel(ecore);

    assertThat(wrapper.resolveEClass("robustInner", "After")).isNotNull();
    assertThat(wrapper.resolveEClass("robustInner", "Before")).isNull();
    wrapper.unloadMetamodel(ecore);
  }

  @Test
  void unloadRemovesNestedPackages() throws IOException {
    Path ecore = workspace.resolve("nested.ecore");
    Files.writeString(ecore, nestedEcore("Gone"));
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(ecore);

    wrapper.unloadMetamodel(ecore);

    assertThat(wrapper.getAvailableMetamodels()).doesNotContain("robustOuter", "robustInner");
    assertThat(wrapper.resolveEClass("robustInner", "Gone")).isNull();
  }

  @Test
  void unloadRevealsSamePackageNameFromAnotherFile() throws IOException {
    String template =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
            name="%1$s" nsURI="http://test/robust/%1$s" nsPrefix="%1$s">
          <eSubpackages name="robustShared" nsURI="http://test/robust/%1$s/shared"
              nsPrefix="robustShared">
            <eClassifiers xsi:type="ecore:EClass" name="%2$s"/>
          </eSubpackages>
        </ecore:EPackage>
        """;
    Path first = workspace.resolve("first.ecore");
    Path second = workspace.resolve("second.ecore");
    Files.writeString(first, template.formatted("robustFirst", "FromFirst"));
    Files.writeString(second, template.formatted("robustSecond", "FromSecond"));
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(first);
    wrapper.loadMetamodel(second);

    wrapper.unloadMetamodel(first);

    assertThat(wrapper.resolveEClass("robustShared", "FromSecond"))
        .as("the second file still provides 'robustShared' after the first one is unloaded")
        .isNotNull();
    wrapper.unloadMetamodel(second);
  }
}
