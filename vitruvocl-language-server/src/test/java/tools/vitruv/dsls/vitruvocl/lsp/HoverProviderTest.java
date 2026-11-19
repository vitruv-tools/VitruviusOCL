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
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Unit tests for {@link HoverProvider}.
 *
 * <p>Real documents are analysed against the spaceMission metamodel; the EClass card details that
 * the metamodel cannot exercise (inheritance, GenModel docs, abstract/interface flags) use a
 * synthetic EClass attached to the parse tree root.
 */
class HoverProviderTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");
  private static final String GENMODEL = "http://www.eclipse.org/emf/2002/GenModel";

  private DocumentAnalyzer analyzer;
  private HoverProvider provider;

  @BeforeEach
  void setUp() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    analyzer = new DocumentAnalyzer(wrapper);
    provider = new HoverProvider();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String hoverAt(String text, String token, int offsetInToken) {
    DocumentAnalysis analysis = analyzer.analyze(text);
    return markdown(provider.getHover(positionOf(text, token, offsetInToken), analysis));
  }

  private static Position positionOf(String text, String token, int offsetInToken) {
    int idx = text.indexOf(token);
    assertThat(idx).as("token '%s' in document", token).isNotNegative();
    int line = 0;
    int lineStart = 0;
    for (int i = 0; i < idx; i++) {
      if (text.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    return new Position(line, idx - lineStart + offsetInToken);
  }

  private static String markdown(Hover hover) {
    if (hover == null) {
      return null;
    }
    assertThat(hover.getContents().getRight().getKind()).isEqualTo(MarkupKind.MARKDOWN);
    return hover.getContents().getRight().getValue();
  }

  /** Re-wraps {@code analysis} with only the root annotated with {@code type}, no diagnostics. */
  private static DocumentAnalysis withRootType(DocumentAnalysis analysis, Type type) {
    ParseTreeProperty<Type> types = new ParseTreeProperty<>();
    types.put(analysis.getTree(), type);
    return new DocumentAnalysis(analysis.getTree(), types, List.of());
  }

  // ── Guards ────────────────────────────────────────────────────────────────

  @Test
  void nullAnalysis_returnsNull() {
    assertThat(provider.getHover(new Position(0, 0), null)).isNull();
  }

  @Test
  void analysisWithoutTree_returnsNull() {
    DocumentAnalysis empty = new DocumentAnalysis(null, null, List.of());

    assertThat(provider.getHover(new Position(0, 0), empty)).isNull();
  }

  @Test
  void cursorBeyondDocument_returnsNull() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";

    assertThat(provider.getHover(new Position(5, 0), analyzer.analyze(text))).isNull();
  }

  @Test
  void missingNodeTypes_onNonKeywordToken_returnsNull() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";
    DocumentAnalysis analysis = analyzer.analyze(text);
    DocumentAnalysis untyped = new DocumentAnalysis(analysis.getTree(), null, List.of());

    assertThat(provider.getHover(positionOf(text, "mass", 1), untyped)).isNull();
  }

  @Test
  void noTypeAnnotationOnPath_returnsNull() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";
    DocumentAnalysis analysis = analyzer.analyze(text);
    DocumentAnalysis untyped =
        new DocumentAnalysis(analysis.getTree(), new ParseTreeProperty<>(), List.of());

    assertThat(provider.getHover(positionOf(text, "mass", 1), untyped)).isNull();
  }

  // ── Diagnostics ───────────────────────────────────────────────────────────

  @Test
  void cursorInsideTypeError_showsErrorMessage() {
    String text = "context spaceMission::Spacecraft inv bad: self.mass = \"not-a-number\"";
    DocumentAnalysis analysis = analyzer.analyze(text);
    Diagnostic error =
        analysis.getDiagnostics().stream()
            .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
            .findFirst()
            .orElseThrow();

    String md = markdown(provider.getHover(error.getRange().getStart(), analysis));

    assertThat(md).startsWith("$(error) **").contains(error.getMessage().getLeft());
  }

  @Test
  void overlappingDiagnostics_areAllListedWithSeverityIcons() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";
    DocumentAnalysis analysis = analyzer.analyze(text);
    Range range = new Range(new Position(0, 0), new Position(0, 10));
    List<Diagnostic> diags =
        List.of(
            new Diagnostic(range, "warn-msg", DiagnosticSeverity.Warning, "test"),
            new Diagnostic(range, "info-msg", DiagnosticSeverity.Information, "test"));
    DocumentAnalysis withDiags =
        new DocumentAnalysis(analysis.getTree(), analysis.getNodeTypes(), diags);

    String md = markdown(provider.getHover(new Position(0, 3), withDiags));

    assertThat(md).contains("$(warning) **warn-msg**").contains("$(info) **info-msg**");
  }

  @Test
  void diagnosticEndIsExclusive_andOtherLinesDoNotMatch() {
    String text = "context spaceMission::Spacecraft inv a:\n  self.mass > 0";
    DocumentAnalysis analysis = analyzer.analyze(text);
    Range range = new Range(new Position(1, 2), new Position(1, 6));
    Diagnostic diag = new Diagnostic(range, "only-self", DiagnosticSeverity.Error, "test");
    Diagnostic noRange = new Diagnostic();
    noRange.setMessage("no-range");
    noRange.setSeverity(DiagnosticSeverity.Error);
    DocumentAnalysis withDiag =
        new DocumentAnalysis(analysis.getTree(), analysis.getNodeTypes(), List.of(diag, noRange));

    assertThat(markdown(provider.getHover(new Position(1, 2), withDiag))).contains("only-self");
    assertThat(markdown(provider.getHover(new Position(1, 6), withDiag)))
        .doesNotContain("only-self");
    assertThat(markdown(provider.getHover(new Position(1, 1), withDiag)))
        // whitespace before 'self' lies outside the squiggle: falls back to the enclosing type
        .isEqualTo("**Type:** `Boolean`");
    assertThat(markdown(provider.getHover(new Position(0, 3), withDiag)))
        .doesNotContain("only-self");
  }

  // ── Annotation keywords ───────────────────────────────────────────────────

  @Test
  void severityAnnotation_showsAnnotationCard() {
    String text = "context spaceMission::Spacecraft inv a: @severity CRITICAL self.mass > 0";

    String md = hoverAt(text, "@severity", 2);

    assertThat(md).startsWith("### `@severity`").contains("CRITICAL").contains("```ocl");
  }

  @Test
  void messageAnnotation_showsAnnotationCard() {
    String text = "context spaceMission::Spacecraft inv a: @message \"bad {self}\" self.mass > 0";

    String md = hoverAt(text, "@message", 2);

    assertThat(md).startsWith("### `@message`").contains("{self.attr}");
  }

  // ── Operations ────────────────────────────────────────────────────────────

  @Test
  void iteratorOperation_showsOperationDocWithParams() {
    String text =
        "context spaceMission::Spacecraft inv a: self.payloads.select(p | p.powerConsumption > 0)"
            + ".size() > 0";

    String md = hoverAt(text, "select", 2);

    assertThat(md)
        .startsWith("### `")
        .contains(OclOperationDocs.lookup("select").orElseThrow().signature())
        .contains("**@param**")
        .contains("**@return**");
  }

  @Test
  void logicalOperator_showsOperationDoc() {
    String text = "context spaceMission::Spacecraft inv a: self.operational implies self.mass > 0";

    String md = hoverAt(text, "implies", 1);

    assertThat(md).contains("Logical implication").contains("`right`");
  }

  // ── Types ─────────────────────────────────────────────────────────────────

  @Test
  void self_showsEClassCardFromMetamodel() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";

    String md = hoverAt(text, "self", 1);

    assertThat(md)
        .startsWith("### `spaceMission::Spacecraft`")
        .contains("EClass · package **spaceMission**")
        .contains("**Own Features:**")
        .contains("- `mass : EInt [1]` *(attr)*")
        .contains("- `name : EString [0..1]` *(attr)*")
        .contains("- `payloads : Payload [0..*]` *(ref)*")
        .doesNotContain("Superclasses")
        .doesNotContain("Inherited");
  }

  @Test
  void integerProperty_showsPlainTypeCard() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";

    String md = hoverAt(text, "mass", 1);

    assertThat(md).startsWith("**Type:** `").contains("Integer").doesNotContain("Collection");
  }

  @Test
  void collectionProperty_showsCollectionHint() {
    String text = "context spaceMission::Spacecraft inv a: self.payloads.size() > 0";

    String md = hoverAt(text, "payloads", 1);

    assertThat(md).startsWith("**Type:** `").contains("*Collection — use `.select`");
  }

  @Test
  void optionalType_showsOptionalHint() {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";
    DocumentAnalysis analysis = withRootType(analyzer.analyze(text), Type.optional(Type.INTEGER));

    String md = markdown(provider.getHover(positionOf(text, "mass", 1), analysis));

    assertThat(md).contains("*Optional value — may be absent*");
  }

  // ── Synthetic EClass cards ────────────────────────────────────────────────

  @Test
  void eclassCard_showsAbstractFlagSuperclassesDocsAndTruncatedInheritance() {
    EcoreFactory f = EcoreFactory.eINSTANCE;
    EPackage pkg = f.createEPackage();
    pkg.setName("demo");

    EClass base = f.createEClass();
    base.setName("Base");
    for (int i = 0; i < 10; i++) {
      base.getEStructuralFeatures().add(attribute("a" + i, 0, 1));
    }
    EClass sub = f.createEClass();
    sub.setName("Sub");
    sub.setAbstract(true);
    sub.getESuperTypes().add(base);
    sub.getEAnnotations().add(genModelDoc("Line one.\nLine two."));
    EAttribute documented = attribute("weight", 2, 5);
    documented.getEAnnotations().add(genModelDoc("Mass in kg. Must be positive."));
    sub.getEStructuralFeatures().add(documented);
    sub.getEStructuralFeatures().add(attribute("tags", 1, -1));
    EAttribute shortDoc = attribute("code", 3, -1);
    shortDoc.getEAnnotations().add(genModelDoc("no full stop"));
    sub.getEStructuralFeatures().add(shortDoc);
    EReference noType = f.createEReference();
    noType.setName("untyped");
    sub.getEStructuralFeatures().add(noType);
    pkg.getEClassifiers().add(base);
    pkg.getEClassifiers().add(sub);

    String md = hoverOnRootType(Type.metaclassType(sub));

    assertThat(md)
        .startsWith("### `demo::Sub` *abstract*")
        .contains("> Line one.\n> Line two.")
        .contains("**Superclasses:** `Base`")
        .contains("- `weight : EInt [2..5]` *(attr)* — Mass in kg.")
        .contains("- `tags : EInt [1..*]` *(attr)*")
        .contains("- `code : EInt [3..*]` *(attr)* — no full stop")
        .contains("- `untyped : ? [0..1]` *(ref)*")
        .contains("**Inherited Features:**")
        .contains("- `a7 : EInt [0..1]`")
        .doesNotContain("`a8 ")
        .contains("- *… and 2 more*");
  }

  @Test
  void eclassCard_interfaceWithoutPackage_usesPlaceholderAndIgnoresForeignAnnotations() {
    EClass iface = EcoreFactory.eINSTANCE.createEClass();
    iface.setName("Marker");
    iface.setInterface(true);
    iface.setAbstract(true);
    iface.getEAnnotations().add(annotation("http://other/source", "documentation", "hidden"));
    iface.getEAnnotations().add(annotation(GENMODEL, "documentation", "   "));

    String md = hoverOnRootType(Type.metaclassType(iface));

    assertThat(md)
        .startsWith("### `?::Marker` *interface*")
        .doesNotContain("*abstract*")
        .doesNotContain("hidden")
        .doesNotContain("> ")
        .doesNotContain("Own Features");
  }

  private String hoverOnRootType(Type type) {
    String text = "context spaceMission::Spacecraft inv a: self.mass > 0";
    DocumentAnalysis analysis = withRootType(analyzer.analyze(text), type);
    return markdown(provider.getHover(positionOf(text, "mass", 1), analysis));
  }

  private static EAttribute attribute(String name, int lower, int upper) {
    EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
    attr.setName(name);
    attr.setEType(EcorePackage.Literals.EINT);
    attr.setLowerBound(lower);
    attr.setUpperBound(upper);
    return attr;
  }

  private static EAnnotation genModelDoc(String doc) {
    return annotation(GENMODEL, "documentation", doc);
  }

  private static EAnnotation annotation(String source, String key, String value) {
    EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
    ann.setSource(source);
    ann.getDetails().put(key, value);
    return ann;
  }
}
