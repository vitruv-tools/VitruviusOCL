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

package tools.vitruv.dsls.vitruvocl.analysis;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorSeverity;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * The result of {@link OclAnalyzer#analyze}: the parse tree and what the symbol table builder (pass
 * 1) and the type checker (pass 2) made of it.
 *
 * @param tree the parse tree; ANTLR's default error recovery always produces one
 * @param tokens the token stream of the tree
 * @param symbolTable the symbol table of pass 1
 * @param scopes the scopes pass 1 annotated the tree with
 * @param nodeTypes the node types of pass 2, or {@code null} if pass 2 did not run
 * @param syntaxErrors the lexer and parser errors, in the order they were reported
 * @param errors the errors and warnings of passes 1 and 2
 * @param crashes the passes that threw ({@link OclAnalyzer.Mode#TOLERANT} only)
 */
public record OclAnalysis(
    VitruvOCLParser.ContextDeclCSContext tree,
    CommonTokenStream tokens,
    SymbolTableImpl symbolTable,
    ScopeAnnotator scopes,
    ParseTreeProperty<Type> nodeTypes,
    List<SyntaxError> syntaxErrors,
    List<CompileError> errors,
    List<PassCrash> crashes) {

  /**
   * A pass that threw.
   *
   * @param pass the pass, e.g. {@code "symbol table construction"}
   * @param exception what it threw
   */
  public record PassCrash(String pass, RuntimeException exception) {}

  /** Copies the lists so that the analysis stays immutable. */
  public OclAnalysis {
    syntaxErrors = List.copyOf(syntaxErrors);
    errors = List.copyOf(errors);
    crashes = List.copyOf(crashes);
  }

  /**
   * Whether the constraint compiled: it has no syntax error and both passes ran without errors;
   * warnings do not count.
   */
  public boolean compiled() {
    return nodeTypes != null
        && syntaxErrors.isEmpty()
        && errors.stream().noneMatch(error -> error.getSeverity() == ErrorSeverity.ERROR)
        && crashes.isEmpty();
  }

  /**
   * The syntax errors as compile errors (source {@code "lexer"} or {@code "parser"}, spanning the
   * offending token or character), followed by the errors and warnings of passes 1 and 2. Trailing
   * input at the position of a pass error is left out: the type checker's error recovery explains
   * it better, e.g. {@code Missing right-hand operand for '>='} instead of {@code unexpected input
   * '>='}.
   *
   * @return all errors in the order they were found
   */
  public List<CompileError> allErrors() {
    List<CompileError> all = new ArrayList<>();
    for (SyntaxError error : syntaxErrors) {
      boolean explained =
          error.kind() == SyntaxError.Kind.TRAILING_INPUT
              && errors.stream()
                  .anyMatch(
                      e ->
                          e.getSeverity() == ErrorSeverity.ERROR
                              && e.getLine() == error.line()
                              && e.getColumn() == error.column());
      if (!explained) {
        all.add(toCompileError(error));
      }
    }
    all.addAll(errors);
    return all;
  }

  private static CompileError toCompileError(SyntaxError error) {
    Token token = error.offendingToken();
    int endColumn;
    if (token == null) {
      endColumn = error.column() + 1; // a lexer error: the character it cannot tokenize
    } else if (token.getType() == Token.EOF) {
      endColumn = error.column();
    } else {
      endColumn = error.column() + token.getText().length();
    }
    return new CompileError(
        error.line(),
        error.column(),
        error.line(),
        endColumn,
        error.message(),
        ErrorSeverity.ERROR,
        error.fromLexer() ? "lexer" : "parser",
        null);
  }
}
