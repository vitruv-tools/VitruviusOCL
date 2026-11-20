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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.OclLexers;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;

/**
 * Characterization test for the type checker's comparability rules: every comparison operator
 * applied to every pair of operand kinds, compared with the snapshot in {@code
 * typechecker/comparability-matrix.txt}. Each snapshot line reads {@code <left> <op> <right> => ok}
 * or {@code => <message> @<line>:<column>-<endLine>:<endColumn> [| ...]} with the type checker's
 * diagnostics.
 */
class ComparabilityMatrixTest {

  private static final Path SNAPSHOT =
      Path.of("src/test/resources/typechecker/comparability-matrix.txt");

  private static final List<String> OPERATORS = List.of("==", "!=", "<", "<=", ">", ">=");

  /** Operand kinds in the context of {@code edge::SubA}, label to expression. */
  private static final Map<String, String> OPERANDS =
      orderedMap(
          "Integer", "1",
          "Real", "1.5",
          "String", "\"a\"",
          "Boolean", "true",
          "IntegerAttribute", "self.value",
          "StringAttribute", "self.name",
          "SubA", "self",
          "OptionalItem", "self.next",
          "Other", "edge::Other.allInstances().first()",
          "OclAny", "self.oclAsType(OclAny)",
          "SetOfInteger", "Set{1}",
          "SequenceOfString", "Sequence{\"a\"}",
          "BagOfInteger", "Bag{1}");

  private static final ModelAccess MODEL = EdgeFixture.create().model().wrapper();

  private static Map<String, String> orderedMap(String... labelsAndExpressions) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < labelsAndExpressions.length; i += 2) {
      map.put(labelsAndExpressions[i], labelsAndExpressions[i + 1]);
    }
    return map;
  }

  static Stream<String> operators() {
    return OPERATORS.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("operators")
  void comparabilityMatchesSnapshot(String operator) throws IOException {
    List<String> expected =
        Files.readAllLines(SNAPSHOT, StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank() && line.split(" ", 3)[1].equals(operator))
            .toList();
    assertEquals(expected, matrix(operator));
  }

  private static List<String> matrix(String operator) {
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, String> left : OPERANDS.entrySet()) {
      for (Map.Entry<String, String> right : OPERANDS.entrySet()) {
        String expression = left.getValue() + " " + operator + " " + right.getValue();
        List<CompileError> errors = typeCheck("context edge::SubA inv: " + expression);
        String outcome =
            errors.isEmpty()
                ? "ok"
                : errors.stream()
                    .map(ComparabilityMatrixTest::describe)
                    .collect(Collectors.joining(" | "));
        lines.add(left.getKey() + " " + operator + " " + right.getKey() + " => " + outcome);
      }
    }
    return lines;
  }

  private static String describe(CompileError error) {
    return error.getMessage()
        + " @"
        + error.getLine()
        + ":"
        + error.getColumn()
        + "-"
        + error.getEndLine()
        + ":"
        + error.getEndColumn();
  }

  private static List<CompileError> typeCheck(String source) {
    VitruvOCLLexer lexer = OclLexers.create(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    VitruvOCLParser parser = new VitruvOCLParser(tokens);
    parser.removeErrorListeners();
    VitruvOCLParser.ContextDeclCSContext tree = parser.contextDeclCS();
    assertEquals(0, parser.getNumberOfSyntaxErrors(), () -> "syntax error in " + source);

    ErrorCollector errors = new ErrorCollector();
    SymbolTableImpl symbolTable = new SymbolTableImpl(MODEL);
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(symbolTable, MODEL, errors, scopes).visit(tree);
    TypeCheckVisitor typeChecker = new TypeCheckVisitor(symbolTable, MODEL, errors, scopes);
    typeChecker.setTokenStream(tokens);
    typeChecker.visit(tree);
    return errors.getErrors();
  }
}
