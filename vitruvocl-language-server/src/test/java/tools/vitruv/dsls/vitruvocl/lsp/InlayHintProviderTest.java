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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;

/** Unit tests for {@link InlayHintProvider}, analysed against the spaceMission metamodel. */
class InlayHintProviderTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");

  private DocumentAnalyzer analyzer;
  private InlayHintProvider provider;

  @BeforeEach
  void setUp() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    analyzer = new DocumentAnalyzer(wrapper);
    provider = new InlayHintProvider();
  }

  private List<InlayHint> hints(String text) {
    return provider.getHints(analyzer.analyze(text), null);
  }

  /** Returns the hint placed directly after the first occurrence of {@code token}. */
  private static InlayHint hintAfter(List<InlayHint> hints, String text, String token) {
    int col = text.indexOf(token) + token.length();
    return hints.stream()
        .filter(h -> h.getPosition().getLine() == 0 && h.getPosition().getCharacter() == col)
        .findFirst()
        .orElse(null);
  }

  private static String label(InlayHint hint) {
    return hint.getLabel().getLeft();
  }

  // ── Guards ────────────────────────────────────────────────────────────────

  @Test
  void nullAnalysis_returnsEmpty() {
    assertThat(provider.getHints(null, null)).isEmpty();
  }

  @Test
  void analysisWithoutTreeOrTypes_returnsEmpty() {
    String text = "context spaceMission::Spacecraft inv a: self.payloads.size() > 0";
    DocumentAnalysis analysis = analyzer.analyze(text);

    assertThat(provider.getHints(new DocumentAnalysis(null, null, List.of()), null)).isEmpty();
    assertThat(provider.getHints(new DocumentAnalysis(analysis.getTree(), null, List.of()), null))
        .isEmpty();
  }

  @Test
  void typesMissing_producesNoHints() {
    String text = "context spaceMission::Spacecraft inv a: let x = self.payloads in x.size() > 0";
    DocumentAnalysis analysis = analyzer.analyze(text);
    DocumentAnalysis untyped =
        new DocumentAnalysis(analysis.getTree(), new ParseTreeProperty<>(), List.of());

    assertThat(provider.getHints(untyped, null)).isEmpty();
  }

  // ── Let variables ─────────────────────────────────────────────────────────

  @Test
  void letWithoutExplicitType_hintsInferredType() {
    String text = "context spaceMission::Spacecraft inv a: let m = self.mass in m > 0";

    InlayHint hint = hintAfter(hints(text), text, "let m");

    assertThat(hint).isNotNull();
    assertThat(label(hint)).startsWith(": ").contains("Integer");
    assertThat(hint.getKind()).isEqualTo(InlayHintKind.Type);
    assertThat(hint.getPaddingLeft()).isFalse();
  }

  @Test
  void letWithBinaryInit_hintsResultType() {
    String text = "context spaceMission::Spacecraft inv a: let m = self.mass + 1 in m > 0";

    InlayHint hint = hintAfter(hints(text), text, "let m");

    assertThat(hint).isNotNull();
    assertThat(label(hint)).contains("Integer");
  }

  @Test
  void letWithExplicitType_producesNoHintForVariable() {
    String text = "context spaceMission::Spacecraft inv a: let m : Integer = self.mass in m > 0";

    assertThat(hintAfter(hints(text), text, "let m")).isNull();
  }

  @Test
  void letWithCollectionInit_hintsCollectionTypeOnVariableAndProperty() {
    String text = "context spaceMission::Spacecraft inv a: let ps = self.payloads in ps.size() > 0";
    List<InlayHint> hints = hints(text);

    InlayHint varHint = hintAfter(hints, text, "let ps");
    InlayHint navHint = hintAfter(hints, text, "self.payloads");

    assertThat(varHint).isNotNull();
    assertThat(label(varHint)).contains("Payload");
    assertThat(navHint).isNotNull();
    assertThat(label(navHint)).isEqualTo(label(varHint));
  }

  // ── Iterator variables ────────────────────────────────────────────────────

  @ParameterizedTest
  @ValueSource(strings = {"select", "reject", "collect", "forAll", "exists"})
  void iteratorVariable_hintsElementTypeFromFirstUsage(String op) {
    String text =
        "context spaceMission::Spacecraft inv a: self.payloads."
            + op
            + "(p | p.powerConsumption > 0).size() > 0";

    InlayHint hint = hintAfter(hints(text), text, op + "(p");

    assertThat(hint).as("hint after iterator var of %s", op).isNotNull();
    assertThat(label(hint)).contains("Payload");
  }

  @Test
  void iteratorVariableUnusedInBody_producesNoHint() {
    String text =
        "context spaceMission::Spacecraft inv a: self.payloads.select(p | true).size() > 0";

    assertThat(hintAfter(hints(text), text, "select(p")).isNull();
  }

  // ── Property navigation ───────────────────────────────────────────────────

  @Test
  void primitiveProperty_isNotHinted() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";

    assertThat(hints(text)).isEmpty();
  }

  @Test
  void metaclassReference_isHinted() {
    String text = "context spaceMission::Astronaut inv a: self.assignedSpacecraft.mass.size() >= 0";

    InlayHint hint = hintAfter(hints(text), text, "self.assignedSpacecraft");

    assertThat(hint).isNotNull();
    assertThat(label(hint)).contains("Spacecraft");
  }

  // ── Range filter ──────────────────────────────────────────────────────────

  @Test
  void hintsOutsideRequestedRange_areFiltered() {
    String text = "context spaceMission::Spacecraft inv a: let m = self.mass in m > 0";
    DocumentAnalysis analysis = analyzer.analyze(text);
    int col = text.indexOf("let m") + "let m".length();

    List<InlayHint> inside =
        provider.getHints(analysis, new Range(new Position(0, col), new Position(0, col)));
    List<InlayHint> before =
        provider.getHints(analysis, new Range(new Position(0, 0), new Position(0, col - 1)));
    List<InlayHint> after =
        provider.getHints(analysis, new Range(new Position(0, col + 1), new Position(1, 0)));
    List<InlayHint> otherLines =
        provider.getHints(analysis, new Range(new Position(1, 0), new Position(2, 0)));

    assertThat(inside).hasSize(1);
    assertThat(before).isEmpty();
    assertThat(after).isEmpty();
    assertThat(otherLines).isEmpty();
  }

  @Test
  void multiLineDocument_placesHintOnZeroBasedLine() {
    String text = "context spaceMission::Spacecraft inv a:\n  let m = self.mass in m > 0";

    List<InlayHint> hints = hints(text);

    assertThat(hints).hasSize(1);
    assertThat(hints.get(0).getPosition()).isEqualTo(new Position(1, "  let m".length()));
  }

  // ── Syntax errors ─────────────────────────────────────────────────────────

  @Test
  void documentWithSyntaxError_doesNotThrow() {
    String text = "context spaceMission::Spacecraft inv a: let m = in self.payloads.select(p | ";

    assertThat(hints(text)).isNotNull();
  }
}
