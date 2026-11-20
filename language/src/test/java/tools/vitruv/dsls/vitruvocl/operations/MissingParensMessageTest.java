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

package tools.vitruv.dsls.vitruvocl.operations;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.vitruv.dsls.vitruvocl.DummyTestSpecification;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;
import tools.vitruv.dsls.vitruvocl.typechecker.TypeCheckVisitor;

/**
 * An operation written without parentheses gets a quick-fix message whose suggestion depends on
 * whether the catalog says the operation takes arguments.
 */
class MissingParensMessageTest extends DummyTestSpecification {

  @ParameterizedTest
  @CsvSource({
    "Sequence{1}.size, size()",
    "Sequence{1}.sum, sum()",
    "Sequence{1}.flatten, flatten()",
    "\"abc\".toUpper, toUpper()",
    "Sequence{1}.including, including(...)",
    "\"abc\".concat, concat(...)",
    "Sequence{1}.select, select(...)"
  })
  void suggestionMatchesArgumentShape(String expression, String expectedSuggestion) {
    String errors = typeErrors(expression.replace('\'', '"'));
    assertTrue(errors.contains("'" + expectedSuggestion + "'"), errors);
  }

  private String typeErrors(String input) {
    ParseTree tree = parse(input);
    ErrorCollector errors = new ErrorCollector();
    var spec = buildDummySpec();
    SymbolTable symbolTable = new SymbolTableImpl(spec);
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(symbolTable, spec, errors, scopes).visit(tree);
    TypeCheckVisitor checker = new TypeCheckVisitor(symbolTable, spec, errors, scopes);
    checker.visit(tree);
    return checker.getErrorCollector().getErrors().stream()
        .map(CompileError::getMessage)
        .collect(Collectors.joining(" | "));
  }
}
