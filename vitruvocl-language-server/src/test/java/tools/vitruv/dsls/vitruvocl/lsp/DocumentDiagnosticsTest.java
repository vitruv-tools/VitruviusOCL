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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;

/**
 * Characterization test for the diagnostics of the language server: for every document in {@code
 * lsp/document-diagnostics.txt} it records whether the analysis produced a parse tree and node
 * types, and every diagnostic with its range, severity, source, message and quick-fix data.
 *
 * <p>A case name containing {@code [crlf]} uses CRLF line breaks, {@code [noeol]} omits the final
 * line break.
 */
class DocumentDiagnosticsTest {

  private static final String SNAPSHOT = "/lsp/document-diagnostics.txt";
  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");

  private static DocumentAnalyzer analyzer;

  @BeforeAll
  static void createAnalyzer() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    analyzer = new DocumentAnalyzer(wrapper);
  }

  static Stream<Arguments> snapshotCases() throws IOException {
    List<Arguments> cases = new ArrayList<>();
    String name = null;
    List<String> body = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    for (String line : snapshotLines()) {
      if (line.startsWith("# ")) {
        continue;
      }
      if (line.startsWith("### ")) {
        addCase(cases, name, body, expected);
        name = line.substring(4);
        body = new ArrayList<>();
        expected = new ArrayList<>();
      } else if (name != null && line.startsWith("=> ")) {
        expected.add(line.substring(3));
      } else if (name != null) {
        body.add(line);
      }
    }
    addCase(cases, name, body, expected);
    return cases.stream();
  }

  private static List<String> snapshotLines() throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            DocumentDiagnosticsTest.class.getResourceAsStream(SNAPSHOT), SNAPSHOT)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  private static void addCase(
      List<Arguments> cases, String name, List<String> body, List<String> expected) {
    if (name == null) {
      return;
    }
    List<String> lines = new ArrayList<>(body);
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1); // the blank separator line before the next case
    }
    String lineBreak = name.contains("[crlf]") ? "\r\n" : "\n";
    String content = String.join(lineBreak, lines) + (name.contains("[noeol]") ? "" : lineBreak);
    cases.add(Arguments.of(name, content, expected));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("snapshotCases")
  void diagnosticsMatchSnapshot(String name, String document, List<String> expected)
      throws IOException {
    DocumentAnalysis analysis = analyzer.analyze(document);
    List<String> actual = new ArrayList<>();
    actual.add(
        "tree=" + (analysis.getTree() != null) + " nodeTypes=" + (analysis.getNodeTypes() != null));
    for (Diagnostic d : analysis.getDiagnostics()) {
      actual.add(render(d));
    }
    assertEquals(expected, actual);
  }

  private static String render(Diagnostic d) {
    return d.getRange().getStart().getLine()
        + ":"
        + d.getRange().getStart().getCharacter()
        + "-"
        + d.getRange().getEnd().getLine()
        + ":"
        + d.getRange().getEnd().getCharacter()
        + " "
        + d.getSeverity()
        + " "
        + d.getSource()
        + " data="
        + d.getData()
        + " | "
        + d.getMessage().getLeft().replace("\n", "\\n");
  }
}
