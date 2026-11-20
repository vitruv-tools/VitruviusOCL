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
import java.util.Set;
import org.antlr.v4.runtime.ANTLRErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.OclLexers;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;
import tools.vitruv.dsls.vitruvocl.typechecker.TypeCheckVisitor;

/**
 * Lexes, parses and type-checks a constraint: the shared front end of the compiler, of prepared
 * constraints and of the Vitruv checker.
 *
 * <p>Syntax errors (of the lexer and the parser, and input after the last context the parser could
 * not continue with) are collected with their positions instead of being printed. In {@link
 * Mode#STRICT} the analysis stops at the first stage that fails (pass 1, pass 2), as needed before
 * evaluating; after syntax errors both passes still run, but only for their diagnostics, which
 * often explain a syntax error better. In {@link Mode#TOLERANT} both passes always run, so that an
 * editor gets type information and errors for a document with syntax errors, and a pass that throws
 * is recorded instead of propagated.
 */
public final class OclAnalyzer {

  /** How far the analysis goes after an error. */
  public enum Mode {
    /** Stop at the first pass with errors; nothing compiles after syntax errors. */
    STRICT,
    /** Run both passes regardless of errors; record exceptions of a pass and continue. */
    TOLERANT
  }

  private static final Set<String> LOGICAL_OPERATORS = Set.of("and", "or", "xor", "implies");

  private OclAnalyzer() {}

  /**
   * Analyzes a constraint with ANTLR's default error recovery.
   *
   * @param source the constraint text, without import declarations
   * @param model the metamodels to resolve types against
   * @param mode how far to go after an error
   * @return the analysis
   */
  public static OclAnalysis analyze(String source, ModelAccess model, Mode mode) {
    return analyze(source, model, mode, new DefaultErrorStrategy());
  }

  /**
   * Analyzes a constraint with the given error recovery of the parser.
   *
   * @param source the constraint text, without import declarations
   * @param model the metamodels to resolve types against
   * @param mode how far to go after an error
   * @param errorStrategy the parser's error recovery; an exception it throws (e.g. from a {@link
   *     org.antlr.v4.runtime.BailErrorStrategy}) propagates in both modes
   * @return the analysis
   */
  public static OclAnalysis analyze(
      String source, ModelAccess model, Mode mode, ANTLRErrorStrategy errorStrategy) {
    List<SyntaxError> syntaxErrors = new ArrayList<>();
    VitruvOCLLexer lexer = OclLexers.create(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    lexer.addErrorListener(collector(syntaxErrors, SyntaxError.Kind.LEXER));
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    VitruvOCLParser parser = new VitruvOCLParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(collector(syntaxErrors, SyntaxError.Kind.PARSER));
    parser.setErrorHandler(errorStrategy);
    VitruvOCLParser.ContextDeclCSContext tree = parser.contextDeclCS();
    // the grammar does not require EOF, so the parser silently stops before unparsable input
    Token next = tokens.LT(1);
    if (next.getType() != Token.EOF
        && syntaxErrors.stream().noneMatch(error -> error.offendingToken() == next)) {
      syntaxErrors.add(
          new SyntaxError(
              next.getLine(),
              next.getCharPositionInLine(),
              trailingInputMessage(next, tokens.LT(2)),
              next,
              SyntaxError.Kind.TRAILING_INPUT));
    }

    if (mode == Mode.STRICT && !syntaxErrors.isEmpty()) {
      // the passes' error recovery explains many syntax errors better (e.g. a missing operand), so
      // they still run for their diagnostics, stopping after pass 1 errors and recording crashes;
      // the tree is not compiled
      OclAnalysis passes = passes(tree, tokens, model, null, syntaxErrors);
      return new OclAnalysis(
          tree,
          tokens,
          passes.symbolTable(),
          passes.scopes(),
          null,
          syntaxErrors,
          passes.errors(),
          List.of());
    }
    return passes(tree, tokens, model, mode, syntaxErrors);
  }

  /**
   * The message for input after the point where the parser stopped: a logical operator at the end
   * misses its right-hand operand (the type checker explains this for the other operators).
   */
  private static String trailingInputMessage(Token next, Token afterNext) {
    if (afterNext.getType() == Token.EOF && LOGICAL_OPERATORS.contains(next.getText())) {
      return "Missing right-hand operand for '" + next.getText() + "'";
    }
    return "unexpected input '" + next.getText() + "'";
  }

  /**
   * Runs pass 1 and pass 2 on a parsed tree. A {@code null} mode runs them for diagnostics after
   * syntax errors: pass 2 only without pass 1 errors, and a crash is recorded, not propagated.
   */
  private static OclAnalysis passes(
      VitruvOCLParser.ContextDeclCSContext tree,
      CommonTokenStream tokens,
      ModelAccess model,
      Mode mode,
      List<SyntaxError> syntaxErrors) {
    List<OclAnalysis.PassCrash> crashes = new ArrayList<>();
    ErrorCollector errors = new ErrorCollector();
    SymbolTableImpl symbolTable = new SymbolTableImpl(model);
    ScopeAnnotator scopes = new ScopeAnnotator();
    run(
        mode,
        crashes,
        "symbol table construction",
        () -> new SymbolTableBuilder(symbolTable, model, errors, scopes).visit(tree));
    if (mode != Mode.TOLERANT && errors.hasErrors()) {
      return new OclAnalysis(
          tree, tokens, symbolTable, scopes, null, syntaxErrors, errors.getErrors(), crashes);
    }

    TypeCheckVisitor typeChecker = new TypeCheckVisitor(symbolTable, model, errors, scopes);
    typeChecker.setTokenStream(tokens);
    boolean typeChecked = run(mode, crashes, "type checking", () -> typeChecker.visit(tree));
    return new OclAnalysis(
        tree,
        tokens,
        symbolTable,
        scopes,
        typeChecked ? typeChecker.getNodeTypes() : null,
        syntaxErrors,
        errors.getErrors(),
        crashes);
  }

  /**
   * Runs a pass; in tolerant mode, an exception is recorded instead of propagated.
   *
   * @return whether the pass completed
   */
  private static boolean run(
      Mode mode, List<OclAnalysis.PassCrash> crashes, String pass, Runnable body) {
    if (mode == Mode.STRICT) {
      body.run();
      return true;
    }
    // tolerant, or diagnostics after syntax errors
    try {
      body.run();
      return true;
    } catch (RuntimeException e) {
      crashes.add(new OclAnalysis.PassCrash(pass, e));
      return false;
    }
  }

  private static BaseErrorListener collector(
      List<SyntaxError> syntaxErrors, SyntaxError.Kind kind) {
    return new BaseErrorListener() {
      @Override
      public void syntaxError(
          Recognizer<?, ?> recognizer,
          Object offendingSymbol,
          int line,
          int column,
          String message,
          RecognitionException exception) {
        Token token = offendingSymbol instanceof Token t ? t : null;
        syntaxErrors.add(new SyntaxError(line, column, message, token, kind));
      }
    };
  }
}
