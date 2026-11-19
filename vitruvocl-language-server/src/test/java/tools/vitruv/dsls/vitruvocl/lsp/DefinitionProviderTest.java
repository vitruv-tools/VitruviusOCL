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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Unit tests for {@link DefinitionProvider}.
 *
 * <p>Go-to-definition resolves metaclass references to the {@code name="…"} attribute of the
 * declaring {@code eClassifiers} element in the loaded {@code .ecore} file.
 */
class DefinitionProviderTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");

  /** 0-based line of {@code name="Spacecraft"} in spaceMission.ecore. */
  private static final int SPACECRAFT_LINE = 17;

  private static final String DOC = "context spaceMission::Spacecraft inv a: self.mass > 0";

  private DocumentAnalyzer analyzer;
  private DefinitionProvider provider;

  @BeforeEach
  void setUp() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    analyzer = new DocumentAnalyzer(wrapper);
    provider = new DefinitionProvider();
  }

  private static Position at(String token, int offset) {
    return new Position(0, DOC.indexOf(token) + offset);
  }

  /** Analysis of {@link #DOC} whose root alone carries a metaclass type for {@code eClass}. */
  private DocumentAnalysis rootTypedAs(EClass eClass) {
    DocumentAnalysis analysis = analyzer.analyze(DOC);
    ParseTreeProperty<Type> types = new ParseTreeProperty<>();
    types.put(analysis.getTree(), Type.metaclassType(eClass));
    return new DocumentAnalysis(analysis.getTree(), types, List.of());
  }

  private static EClass eClassIn(String name, URI resourceUri) {
    EClass eClass = EcoreFactory.eINSTANCE.createEClass();
    eClass.setName(name);
    if (resourceUri != null) {
      Resource resource = new ResourceImpl(resourceUri);
      resource.getContents().add(eClass);
    }
    return eClass;
  }

  // ── Guards ────────────────────────────────────────────────────────────────

  @Test
  void nullAnalysis_returnsNull() {
    assertThat(provider.getDefinition(new Position(0, 0), null)).isNull();
  }

  @Test
  void analysisWithoutTreeOrTypes_returnsNull() {
    DocumentAnalysis analysis = analyzer.analyze(DOC);

    assertThat(
            provider.getDefinition(new Position(0, 0), new DocumentAnalysis(null, null, List.of())))
        .isNull();
    assertThat(
            provider.getDefinition(
                at("self", 1), new DocumentAnalysis(analysis.getTree(), null, List.of())))
        .isNull();
  }

  @Test
  void cursorBeyondDocument_returnsNull() {
    assertThat(provider.getDefinition(new Position(3, 0), analyzer.analyze(DOC))).isNull();
  }

  @Test
  void noAnnotationOnPath_returnsNull() {
    DocumentAnalysis analysis = analyzer.analyze(DOC);
    DocumentAnalysis untyped =
        new DocumentAnalysis(analysis.getTree(), new ParseTreeProperty<>(), List.of());

    assertThat(provider.getDefinition(at("self", 1), untyped)).isNull();
  }

  // ── Real metamodel ────────────────────────────────────────────────────────

  @Test
  void self_jumpsToEClassDeclarationInEcoreFile() {
    Location location = provider.getDefinition(at("self", 1), analyzer.analyze(DOC));

    assertThat(location).isNotNull();
    assertThat(location.getUri()).startsWith("file:").endsWith("spaceMission.ecore");
    int col = "  <eClassifiers xsi:type=\"ecore:EClass\" name=\"".length();
    assertThat(location.getRange())
        .isEqualTo(
            new Range(
                new Position(SPACECRAFT_LINE, col),
                new Position(SPACECRAFT_LINE, col + "Spacecraft".length())));
  }

  @Test
  void primitiveProperty_returnsNull() {
    assertThat(provider.getDefinition(at("mass", 1), analyzer.analyze(DOC))).isNull();
  }

  @Test
  void documentWithSyntaxError_doesNotThrow() {
    String text = "context spaceMission::Spacecraft inv a: self.";

    DocumentAnalysis analysis = analyzer.analyze(text);

    assertThat(provider.getDefinition(new Position(0, text.length() - 1), analysis))
        .satisfiesAnyOf(
            loc -> assertThat(loc).isNull(),
            loc -> assertThat(loc.getUri()).endsWith("spaceMission.ecore"));
  }

  // ── Synthetic EClasses (resource edge cases) ──────────────────────────────

  @Test
  void eClassWithoutResource_returnsNull() {
    assertThat(provider.getDefinition(at("self", 1), rootTypedAs(eClassIn("Free", null)))).isNull();
  }

  @Test
  void eClassInNonFileResource_returnsNull() {
    EClass eClass = eClassIn("Remote", URI.createURI("http://example.org/remote.ecore"));

    assertThat(provider.getDefinition(at("self", 1), rootTypedAs(eClass))).isNull();
  }

  @Test
  void eClassInResourceWithoutUri_returnsNull() {
    EClass eClass = EcoreFactory.eINSTANCE.createEClass();
    eClass.setName("NoUri");
    new ResourceImpl().getContents().add(eClass);

    assertThat(provider.getDefinition(at("self", 1), rootTypedAs(eClass))).isNull();
  }

  @Test
  void missingEcoreFile_fallsBackToZeroRange(@TempDir Path tmp) {
    Path missing = tmp.resolve("missing.ecore");
    EClass eClass = eClassIn("Ghost", URI.createFileURI(missing.toString()));

    Location location = provider.getDefinition(at("self", 1), rootTypedAs(eClass));

    assertThat(location).isNotNull();
    assertThat(location.getUri()).isEqualTo(missing.toUri().toString());
    assertThat(location.getRange()).isEqualTo(new Range(new Position(0, 0), new Position(0, 0)));
  }

  @Test
  void classNameNotInFile_fallsBackToZeroRange(@TempDir Path tmp) throws IOException {
    Path ecore = Files.writeString(tmp.resolve("other.ecore"), "<x name=\"Other\"/>\n");
    EClass eClass = eClassIn("Absent", URI.createFileURI(ecore.toString()));

    Location location = provider.getDefinition(at("self", 1), rootTypedAs(eClass));

    assertThat(location.getRange().getStart()).isEqualTo(new Position(0, 0));
  }

  @Test
  void unnamedEClass_fallsBackToZeroRange(@TempDir Path tmp) throws IOException {
    Path ecore = Files.writeString(tmp.resolve("anon.ecore"), "<x name=\"\"/>\n");
    EClass eClass = eClassIn(null, URI.createFileURI(ecore.toString()));

    Location location = provider.getDefinition(at("self", 1), rootTypedAs(eClass));

    assertThat(location.getRange().getEnd()).isEqualTo(new Position(0, 0));
  }

  @Test
  void classFoundOnLaterLine_rangeCoversOnlyName(@TempDir Path tmp) throws IOException {
    Path ecore =
        Files.writeString(tmp.resolve("two.ecore"), "<root>\n  <c name=\"Target\"/>\n</root>\n");
    EClass eClass = eClassIn("Target", URI.createFileURI(ecore.toString()));

    Location location = provider.getDefinition(at("self", 1), rootTypedAs(eClass));

    assertThat(location.getRange()).isEqualTo(new Range(new Position(1, 11), new Position(1, 17)));
  }
}
