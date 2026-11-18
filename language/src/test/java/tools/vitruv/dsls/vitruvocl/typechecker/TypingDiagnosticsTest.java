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
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
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
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Characterization test for the typing of correspondence operations, object-lifecycle predicates
 * ({@code @pre}, {@code OCLisNew}, ...) and metamodel operations.
 *
 * <p>Type checks every complete document in {@code typechecker/typing-diagnostics.txt} as the
 * language server does and compares the type recorded for each constraint body, every recorded node
 * type and every reported {@link CompileError} with the snapshot.
 */
class TypingDiagnosticsTest {

  private static final Path SNAPSHOT =
      Path.of("src/test/resources/typechecker/typing-diagnostics.txt");
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
  void typingMatchesSnapshot(String name, String source, List<String> expected) {
    assertEquals(expected, typeCheck(source));
  }

  private static List<String> typeCheck(String source) {
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

    List<String> lines = new ArrayList<>();
    ParseTreeProperty<Type> nodeTypes = typeChecker.getNodeTypes();
    for (VitruvOCLParser.ClassifierContextCSContext context : tree.classifierContextCS()) {
      context.invCS().forEach(c -> lines.add(bodyType("inv", c, c.specificationCS(), nodeTypes)));
      context.preCS().forEach(c -> lines.add(bodyType("pre", c, c.specificationCS(), nodeTypes)));
      context.postCS().forEach(c -> lines.add(bodyType("post", c, c.specificationCS(), nodeTypes)));
    }
    collectNodeTypes(tree, nodeTypes, lines);
    errors.getErrors().forEach(e -> lines.add(render(e)));
    return lines;
  }

  /** Every node with a recorded type, in tree order: {@code node <rule> <line>:<col> <type>}. */
  private static void collectNodeTypes(
      ParseTree node, ParseTreeProperty<Type> nodeTypes, List<String> lines) {
    Type type = nodeTypes.get(node);
    if (type != null && node instanceof ParserRuleContext ctx) {
      lines.add(
          "node "
              + VitruvOCLParser.ruleNames[ctx.getRuleIndex()]
              + " "
              + ctx.getStart().getLine()
              + ":"
              + ctx.getStart().getCharPositionInLine()
              + " "
              + type);
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      collectNodeTypes(node.getChild(i), nodeTypes, lines);
    }
  }

  /** The type recorded for the body (the last specification) of a constraint. */
  private static String bodyType(
      String kind,
      ParserRuleContext constraint,
      List<VitruvOCLParser.SpecificationCSContext> specifications,
      ParseTreeProperty<Type> nodeTypes) {
    Type type =
        specifications.isEmpty()
            ? null
            : nodeTypes.get(specifications.get(specifications.size() - 1));
    return kind + " " + constraint.getStart().getLine() + " " + type;
  }

  private static String render(CompileError e) {
    return e.getLine()
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
        + " fix="
        + e.getSuggestion()
        + " | "
        + e.getMessage();
  }
}
