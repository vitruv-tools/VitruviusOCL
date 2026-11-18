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

import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Error recovery of the type checker over the raw token stream: finds what ANTLR's own recovery
 * dropped silently (a missing operand or logical operator after a complete specification, an
 * unparsed correspondence call, doubled commas) and reports it at the offending tokens.
 */
final class SyntaxRecoveryReporter {

  /**
   * Tokens that can start a new expression — if one follows a complete spec, an operator is
   * missing.
   */
  private static final Set<String> EXPR_START_KEYWORDS =
      Set.of("self", "true", "false", "not", "if", "let");

  private static final Set<String> COMPARISON_OPS = Set.of("==", "!=", "<", "<=", ">", ">=");

  private static final Set<String> CORR_OPS_SET = Set.copyOf(CorrespondenceTyping.CORR_OPS);

  /** Receives the errors this reporter finds, as a span from a start token to an end position. */
  @FunctionalInterface
  interface SpanErrorSink {

    /**
     * Records an error.
     *
     * @param start token at which the error range starts
     * @param endLine line where the error range ends
     * @param endColumn column (exclusive) where the error range ends
     * @param message human-readable error message
     */
    void report(Token start, int endLine, int endColumn, String message);
  }

  /** The completely filled token stream, or {@code null} if none is available. */
  private final TokenStream tokens;

  private final SpanErrorSink sink;

  /**
   * Creates a reporter over a completely filled token stream.
   *
   * @param tokens the token stream of the parsed document, or {@code null} to report nothing
   * @param sink receives the reported errors
   */
  SyntaxRecoveryReporter(TokenStream tokens, SpanErrorSink sink) {
    this.tokens = tokens;
    this.sink = sink;
  }

  /**
   * Checks whether there are expression-starting tokens immediately after {@code spec.getStop()}
   * that were silently dropped by ANTLR error recovery. This catches patterns like {@code self.a ==
   * "x" persons::Foo.allInstances()...} where the user forgot {@code and}/{@code or}/{@code
   * implies} between two sub-expressions.
   *
   * @param spec the parsed specification context
   * @return {@code true} if a missing-operator error was reported
   */
  boolean reportMissingOperatorBetweenExpressions(ParserRuleContext spec) {
    if (tokens == null || spec.getStop() == null) {
      return false;
    }
    int idx = nextDefault(spec.getStop().getTokenIndex() + 1);
    if (idx < 0) {
      return false;
    }
    Token next = tokens.get(idx);
    // Token types that terminate an invCS (not expression starters)
    String txt = next.getText();
    if (",".equals(txt)
        || ")".equals(txt)
        || "context".equals(txt)
        || "inv".equals(txt)
        || "<EOF>".equals(txt)) {
      return false;
    }
    // Is it an expression-starting token?
    boolean isExprStart =
        EXPR_START_KEYWORDS.contains(txt)
            // ID followed by '::' (qualified name) or '.' (navigation) or '(' (call)
            || (txt.matches("[a-zA-Z_]\\w*") && isFollowedByNavOrScope(idx))
            // string literal
            || (txt.startsWith("\"") || txt.startsWith("'"))
            // numeric literal
            || txt.matches("-?\\d+(\\.\\d+)?");
    if (!isExprStart) {
      return false;
    }
    reportTokenError(
        next,
        "Missing logical operator before this expression — did you mean 'and', 'or', or"
            + " 'implies'?");
    return true;
  }

  private boolean isFollowedByNavOrScope(int idIdx) {
    int next = nextDefault(idIdx + 1);
    if (next < 0) {
      return false;
    }
    String t = tokens.get(next).getText();
    return "::".equals(t) || ".".equals(t) || "(".equals(t);
  }

  /**
   * Checks whether the token immediately after {@code ctx.getStop()} in the stream is a comparison
   * operator that implies a missing right-hand operand. If so, reports the error ON that operator
   * token and returns {@code true}.
   */
  boolean reportMissingOperandAfter(ParserRuleContext ctx) {
    if (tokens == null || ctx.getStop() == null) {
      return false;
    }
    int idx = nextDefault(ctx.getStop().getTokenIndex() + 1);
    if (idx < 0) {
      return false;
    }
    Token opTok = tokens.get(idx);
    if (!COMPARISON_OPS.contains(opTok.getText())) {
      return false;
    }
    reportTokenError(opTok, "Missing right-hand operand for '" + opTok.getText() + "'");
    return true;
  }

  /**
   * Looks in the token stream immediately after {@code parsedExpr.getStop()} for an unparsed
   * navigation step of the form {@code .reject(~…)/select(~…)/exists(~…)} that ANTLR failed to
   * match (typically due to extra commas inside the correspondence filter).
   *
   * @return [opTokenIndex, closingParenTokenIndex] if found, or empty array if not found
   */
  private int[] findUnparsedCorrCallAfter(ParserRuleContext parsedExpr) {
    if (tokens == null || parsedExpr.getStop() == null) {
      return new int[0];
    }
    int idx = parsedExpr.getStop().getTokenIndex() + 1;
    idx = nextDefault(idx);
    if (idx < 0) {
      return new int[0];
    }
    // expect '.'
    if (!".".equals(tokens.get(idx).getText())) {
      return new int[0];
    }
    idx = nextDefault(idx + 1);
    if (idx < 0) {
      return new int[0];
    }
    // expect reject / select / exists
    Token opTok = tokens.get(idx);
    if (!CORR_OPS_SET.contains(opTok.getText())) {
      return new int[0];
    }
    final int opIdx = idx;
    idx = nextDefault(idx + 1);
    if (idx < 0) {
      return new int[0];
    }
    // expect '('
    if (!"(".equals(tokens.get(idx).getText())) {
      return new int[0];
    }
    // find matching ')'
    int depth = 0;
    for (int i = idx; i < tokens.size(); i++) {
      String txt = tokens.get(i).getText();
      if ("(".equals(txt)) {
        depth++;
      } else if (")".equals(txt)) {
        depth--;
        if (depth == 0) {
          return new int[] {opIdx, i};
        }
      }
    }
    return new int[0];
  }

  /** Returns the index of the next token on the default channel at or after {@code from}, or -1. */
  private int nextDefault(int from) {
    for (int i = from; i < tokens.size(); i++) {
      if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
        return i;
      }
    }
    return -1;
  }

  private boolean hasExtraCommasInRange(ParserRuleContext ctx) {
    if (tokens == null || ctx.getStart() == null) {
      return false;
    }
    int from = ctx.getStart().getTokenIndex();
    int to = ctx.getStop() != null ? ctx.getStop().getTokenIndex() : from;
    boolean prevWasComma = false;
    for (int i = from; i <= to; i++) {
      Token t = tokens.get(i);
      if (t.getChannel() != Token.DEFAULT_CHANNEL) {
        continue;
      }
      if (",".equals(t.getText())) {
        if (prevWasComma) {
          return true; // two consecutive commas
        }
        prevWasComma = true;
      } else {
        prevWasComma = false;
      }
    }
    return false;
  }

  /** Recursively checks whether any node in the subtree is an ANTLR {@link ErrorNode}. */
  static boolean hasErrorNode(ParseTree tree) {
    if (tree instanceof ErrorNode) {
      return true;
    }
    if (tree instanceof ParserRuleContext prc && prc.exception != null) {
      return true;
    }
    for (int i = 0; i < tree.getChildCount(); i++) {
      if (hasErrorNode(tree.getChild(i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code ctx} contains a parse error: an error node, or two consecutive commas that ANTLR
   * recovered from silently.
   *
   * @param ctx the parsed context
   * @return {@code true} if a parse error is found
   */
  boolean hasParseErrors(ParserRuleContext ctx) {
    return hasErrorNode(ctx) || hasExtraCommasInRange(ctx);
  }

  /**
   * Reports an unparsed {@code .reject(~...)}/{@code select(~...)}/{@code exists(~...)} call right
   * after {@code parsedExpr}, spanning the operation name to the closing parenthesis.
   *
   * @param parsedExpr the last parsed expression
   * @return {@code true} if such a call was found and reported
   */
  boolean reportUnparsedCorrCallAfter(ParserRuleContext parsedExpr) {
    int[] badCallSpan = findUnparsedCorrCallAfter(parsedExpr);
    if (badCallSpan.length == 0) {
      return false;
    }
    Token opTok = tokens.get(badCallSpan[0]);
    Token endTok = tokens.get(badCallSpan[1]);
    sink.report(
        opTok,
        endTok.getLine(),
        endTok.getCharPositionInLine() + endTok.getText().length(),
        "'"
            + opTok.getText()
            + "(~, ...)' has invalid arguments — check for syntax errors inside the"
            + " parentheses");
    return true;
  }

  private void reportTokenError(Token token, String message) {
    sink.report(
        token, token.getLine(), token.getCharPositionInLine() + token.getText().length(), message);
  }
}
