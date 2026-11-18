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

package tools.vitruv.dsls.vitruvocl.typechecker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.OclLexers;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;

/**
 * Characterization test for the type checker's error-recovery diagnostics.
 *
 * <p>Runs symbol table construction and type checking on the parse tree even if it has syntax
 * errors, as the language server does, and compares every reported {@link CompileError} — position,
 * severity, source, error code, suggestion and message — with the snapshot in {@code
 * typechecker/recovery-diagnostics.txt}.
 */
class RecoveryDiagnosticsTest {

  private static final Path SNAPSHOT =
      Path.of("src/test/resources/typechecker/recovery-diagnostics.txt");
  private static final String CONTEXT_HEADER = "context family::Member inv:\n  ";
  private static final Path[] ECORES = {
    Path.of("src/test/resources/test-metamodels/family.ecore"),
    Path.of("src/test/resources/test-metamodels/persons.ecore"),
    Path.of("src/test/resources/test-metamodels/correspondence.ecore")
  };

  private static MetamodelWrapper metamodels;

  @BeforeAll
  static void loadMetamodels() throws IOException {
    metamodels = new MetamodelWrapper();
    for (Path ecore : ECORES) {
      metamodels.loadMetamodel(ecore);
    }
  }

  static Stream<Arguments> snapshotCases() throws IOException {
    List<Arguments> cases = new ArrayList<>();
    String name = null;
    List<String> body = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    for (String line : Files.readAllLines(SNAPSHOT, StandardCharsets.UTF_8)) {
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
      } else if (name != null && !line.isEmpty()) {
        body.add(line);
      }
    }
    addCase(cases, name, body, expected);
    return cases.stream();
  }

  private static void addCase(
      List<Arguments> cases, String name, List<String> body, List<String> expected) {
    if (name != null) {
      cases.add(Arguments.of(name, String.join("\n", body), expected));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("snapshotCases")
  void diagnosticsMatchSnapshot(String name, String body, List<String> expected) {
    assertEquals(expected, render(typeCheck(CONTEXT_HEADER + body)));
  }

  private static List<CompileError> typeCheck(String source) {
    VitruvOCLLexer lexer = OclLexers.create(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    VitruvOCLParser parser = new VitruvOCLParser(tokens);
    parser.removeErrorListeners();
    VitruvOCLParser.ContextDeclCSContext tree = parser.contextDeclCS();

    ErrorCollector errors = new ErrorCollector();
    SymbolTableImpl symbolTable = new SymbolTableImpl(metamodels);
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(symbolTable, metamodels, errors, scopes).visit(tree);
    TypeCheckVisitor typeChecker = new TypeCheckVisitor(symbolTable, metamodels, errors, scopes);
    typeChecker.setTokenStream(tokens);
    typeChecker.visit(tree);
    return errors.getErrors();
  }

  private static List<String> render(List<CompileError> errors) {
    return errors.stream()
        .map(
            e ->
                e.getLine()
                    + ":"
                    + e.getColumn()
                    + "-"
                    + e.getEndLine()
                    + ":"
                    + e.getEndColumn()
                    + " "
                    + e.getSeverity()
                    + " "
                    + e.getSource()
                    + " code="
                    + e.getErrorCode()
                    + " fix="
                    + e.getSuggestion()
                    + " | "
                    + e.getMessage())
        .toList();
  }
}
