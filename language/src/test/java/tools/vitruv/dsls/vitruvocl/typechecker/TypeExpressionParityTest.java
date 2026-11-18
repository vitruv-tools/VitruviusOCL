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
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.OclLexers;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Characterization test for the resolution of type expressions in both compiler passes.
 *
 * <p>For every type expression in {@code typechecker/type-expression-parity.txt} it records the
 * type the symbol table builder gives an operation parameter declared with it, and the type, the
 * recorded node types and the errors of the type checker for the same expression. The builder
 * resolves silently to {@code OclAny} where the type checker reports an error.
 */
class TypeExpressionParityTest {

  private static final Path SNAPSHOT =
      Path.of("src/test/resources/typechecker/type-expression-parity.txt");
  private static final Path[] ECORES = {
    Path.of("src/test/resources/test-metamodels/family.ecore"),
    Path.of("src/test/resources/test-metamodels/persons.ecore")
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
    String typeText = null;
    List<String> expected = new ArrayList<>();
    for (String line : Files.readAllLines(SNAPSHOT, StandardCharsets.UTF_8)) {
      if (line.startsWith("# ") || line.isEmpty()) {
        continue;
      }
      if (line.startsWith("=> ")) {
        expected.add(line.substring(3));
      } else {
        if (typeText != null) {
          cases.add(Arguments.of(typeText, expected));
        }
        typeText = line;
        expected = new ArrayList<>();
      }
    }
    if (typeText != null) {
      cases.add(Arguments.of(typeText, expected));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("snapshotCases")
  void resolutionMatchesSnapshot(String typeText, List<String> expected) {
    List<String> actual = new ArrayList<>();
    actual.add("builder " + builderType(typeText));
    actual.addAll(checkerResolution(typeText));
    assertEquals(expected, actual);
  }

  /** The type the symbol table builder gives {@code p} in {@code op(p : typeText)}. */
  private static Type builderType(String typeText) {
    VitruvOCLParser parser = parser("context family::Member::op(p : " + typeText + ") pre: true");
    VitruvOCLParser.ContextDeclCSContext tree = parser.contextDeclCS();
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(
            new SymbolTableImpl(metamodels), metamodels, new ErrorCollector(), scopes)
        .visit(tree);
    return scopes.getScope(tree.classifierContextCS(0)).resolveVariable("p").getType();
  }

  /** The type checker's type, node types and errors for {@code typeText} on its own. */
  private static List<String> checkerResolution(String typeText) {
    VitruvOCLParser parser = parser(typeText);
    VitruvOCLParser.TypeExpCSContext typeExp = parser.typeExpCS();
    assertEquals(Token.EOF, parser.getCurrentToken().getType(), "parsed only part of " + typeText);
    ErrorCollector errors = new ErrorCollector();
    TypeCheckVisitor checker =
        new TypeCheckVisitor(new SymbolTableImpl(metamodels), metamodels, errors, null);
    List<String> lines = new ArrayList<>();
    lines.add("checker " + checker.visit(typeExp));
    collectNodeTypes(typeExp, checker.getNodeTypes(), lines);
    errors
        .getErrors()
        .forEach(
            e ->
                lines.add(
                    "error "
                        + e.getColumn()
                        + "-"
                        + e.getEndColumn()
                        + " "
                        + e.getSeverity()
                        + " "
                        + e.getSource()
                        + " | "
                        + e.getMessage()));
    return lines;
  }

  private static void collectNodeTypes(
      ParseTree node, ParseTreeProperty<Type> nodeTypes, List<String> lines) {
    Type type = nodeTypes.get(node);
    if (type != null && node instanceof ParserRuleContext ctx) {
      lines.add(
          "node "
              + ctx.getClass().getSimpleName().replace("Context", "")
              + " '"
              + ctx.getText()
              + "' "
              + type);
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      collectNodeTypes(node.getChild(i), nodeTypes, lines);
    }
  }

  private static VitruvOCLParser parser(String source) {
    VitruvOCLLexer lexer = OclLexers.create(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    VitruvOCLParser parser = new VitruvOCLParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    return parser;
  }
}
