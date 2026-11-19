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

package tools.vitruv.dsls.vitruvocl.source;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.Interval;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;

/**
 * Splits an {@code .ocl} document into its constraint blocks ({@code inv}, {@code pre}, {@code
 * post}), each with the header of its context, and collects the document's syntax errors.
 *
 * <p>The split works on the lexer's tokens, so keywords inside string literals and comments never
 * start a block, and it does not depend on the document being free of syntax errors: a broken block
 * only breaks itself. Import declarations are blanked first, keeping the line count, so that all
 * positions refer to the original document.
 */
public final class OclSourceSplitter {

  private static final int INV = literalType("'inv'");
  private static final int PRE = literalType("'pre'");
  private static final int POST = literalType("'post'");
  private static final int COLON = literalType("':'");
  private static final int LPAREN = literalType("'('");
  private static final int RPAREN = literalType("')'");

  /**
   * The constraint blocks and syntax errors of a document.
   *
   * @param units the blocks in document order, including contexts without blocks
   * @param syntaxErrors the lexer and parser errors in the order they were found; the parser stops
   *     at the first input it cannot continue with, which is reported as {@code unexpected input}
   */
  public record SplitResult(List<ConstraintUnit> units, List<SourceDiagnostic> syntaxErrors) {

    /** Copies the lists so that the result stays immutable. */
    public SplitResult {
      units = List.copyOf(units);
      syntaxErrors = List.copyOf(syntaxErrors);
    }
  }

  private OclSourceSplitter() {}

  /**
   * Splits a document.
   *
   * @param content the content of an {@code .ocl} file
   * @return its constraint blocks and syntax errors
   */
  public static SplitResult split(String content) {
    CharStream input = CharStreams.fromString(withoutImports(content));
    List<SourceDiagnostic> syntaxErrors = new ArrayList<>();
    BaseErrorListener collector =
        new BaseErrorListener() {
          @Override
          public void syntaxError(
              Recognizer<?, ?> recognizer,
              Object offendingSymbol,
              int line,
              int column,
              String message,
              RecognitionException exception) {
            syntaxErrors.add(new SourceDiagnostic(new SourcePosition(line, column), message));
          }
        };
    // the end of every stretch of text the lexer cannot tokenize; it stays part of its block
    List<Integer> unlexable = new ArrayList<>();
    BaseErrorListener unlexableCollector =
        new BaseErrorListener() {
          @Override
          public void syntaxError(
              Recognizer<?, ?> recognizer,
              Object offendingSymbol,
              int line,
              int column,
              String message,
              RecognitionException exception) {
            unlexable.add(((Lexer) recognizer).getInputStream().index());
          }
        };
    VitruvOCLLexer lexer = new VitruvOCLLexer(input);
    lexer.removeErrorListeners();
    lexer.addErrorListener(collector);
    lexer.addErrorListener(unlexableCollector);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    if (tokens.LT(1).getType() != Token.EOF || !syntaxErrors.isEmpty()) {
      collectParseErrors(tokens, collector, syntaxErrors);
    }
    // the rest of the document, after where the parser stopped, is tokenized without reporting
    lexer.removeErrorListener(collector);
    tokens.fill();
    return new SplitResult(units(input, tokens.getTokens(), unlexable), syntaxErrors);
  }

  /**
   * Removes import declarations, keeping the line count so that positions stay valid; line breaks
   * ({@code \r\n}, {@code \r}, {@code \n}) become {@code \n}, other Unicode line separators stay
   * part of their line, as for the lexer.
   *
   * @param content the content of an {@code .ocl} file
   * @return the content with every import line emptied
   */
  public static String withoutImports(String content) {
    StringBuilder result = new StringBuilder();
    for (String line : content.split("\r\n|\r|\n", -1)) {
      result.append(line.strip().startsWith("import ") ? "" : line).append('\n');
    }
    return result.toString();
  }

  private static void collectParseErrors(
      CommonTokenStream tokens, BaseErrorListener collector, List<SourceDiagnostic> errors) {
    VitruvOCLParser parser = new VitruvOCLParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(collector);
    parser.contextDeclCS();
    // the grammar does not require EOF, so the parser silently stops before unparsable input
    Token next = tokens.LT(1);
    if (next.getType() != Token.EOF) {
      errors.add(
          new SourceDiagnostic(
              new SourcePosition(next.getLine(), next.getCharPositionInLine()),
              "unexpected input '" + next.getText() + "'"));
    }
  }

  /** The units of the contexts in {@code tokens}; tokens before the first context are ignored. */
  private static List<ConstraintUnit> units(
      CharStream input, List<Token> tokens, List<Integer> unlexable) {
    List<ConstraintUnit> units = new ArrayList<>();
    int i = 0;
    while (!isEnd(tokens, i) && tokens.get(i).getType() != VitruvOCLLexer.CONTEXT) {
      i++;
    }
    while (!isEnd(tokens, i)) {
      Token context = tokens.get(i);
      String contextType = contextType(tokens, i);
      int block = nextBoundary(tokens, i + 1);
      if (isEnd(tokens, block) || !isKeyword(tokens.get(block))) {
        String header = raw(input, context, tokens.get(block - 1), tokens.get(block), unlexable);
        Lines text = Lines.of(header, context.getLine());
        units.add(
            new ConstraintUnit(
                ConstraintUnit.Kind.EMPTY_CONTEXT,
                null,
                contextType,
                header.strip(),
                "",
                text.text(),
                text.lines(),
                null,
                null,
                position(context)));
        i = block;
        continue;
      }
      String header = input.getText(Interval.of(context.getStartIndex(), start(tokens.get(block))));
      Lines headerLines = Lines.of(header, context.getLine());
      while (!isEnd(tokens, block) && isKeyword(tokens.get(block))) {
        int next = nextBoundary(tokens, block + 1);
        String body =
            raw(input, tokens.get(block), tokens.get(next - 1), tokens.get(next), unlexable);
        units.add(unit(tokens, block, next, header, headerLines, body, contextType));
        block = next;
      }
      i = block;
    }
    return units;
  }

  private static ConstraintUnit unit(
      List<Token> tokens,
      int first,
      int end,
      String header,
      Lines headerLines,
      String body,
      String contextType) {
    Token keyword = tokens.get(first);
    Lines bodyLines = Lines.of(body, keyword.getLine());
    List<Integer> textLines = new ArrayList<>(headerLines.lines());
    textLines.addAll(bodyLines.lines());

    int afterName = first + 1;
    String name = null;
    if (afterName + 1 < end
        && tokens.get(afterName).getType() == VitruvOCLLexer.ID
        && (tokens.get(afterName + 1).getType() == COLON
            || tokens.get(afterName + 1).getType() == LPAREN)) {
      name = tokens.get(afterName).getText();
      afterName++;
    }
    int severity = severityAnnotation(tokens, afterName, end);
    return new ConstraintUnit(
        kind(keyword),
        name,
        contextType,
        header.strip(),
        body,
        headerLines.text() + "\n" + bodyLines.text(),
        textLines,
        severity >= 0 ? tokens.get(severity + 1).getText() : null,
        severity >= 0 ? position(tokens.get(severity)) : null,
        position(keyword));
  }

  /**
   * The index of the first {@code @severity} annotation of a block whose name (if any) ends before
   * {@code from}, or -1: skips the parameter specification in parentheses and the colon, then reads
   * the annotations.
   */
  private static int severityAnnotation(List<Token> tokens, int from, int end) {
    int i = from;
    if (i < end && tokens.get(i).getType() == LPAREN) {
      int depth = 0;
      do {
        int type = tokens.get(i).getType();
        depth += type == LPAREN ? 1 : type == RPAREN ? -1 : 0;
        i++;
      } while (i < end && depth > 0);
    }
    if (i >= end || tokens.get(i).getType() != COLON) {
      return -1;
    }
    i++;
    while (i + 1 < end) {
      int type = tokens.get(i).getType();
      if (type == VitruvOCLLexer.AT_SEVERITY && tokens.get(i + 1).getType() == VitruvOCLLexer.ID) {
        return i;
      }
      if (type != VitruvOCLLexer.AT_SEVERITY && type != VitruvOCLLexer.AT_MESSAGE) {
        return -1;
      }
      i += 2;
    }
    return -1;
  }

  /**
   * The context type of the context declaration starting at {@code context}: {@code mm::Class} or
   * {@code Class} (also for an operation {@code Class::op(...)}), after an optional {@code name :}
   * prefix; {@code null} if malformed.
   */
  private static String contextType(List<Token> tokens, int context) {
    int i = context + 1;
    if (isType(tokens, i, VitruvOCLLexer.ID) && isType(tokens, i + 1, COLON)) {
      i += 2;
    }
    if (!isType(tokens, i, VitruvOCLLexer.ID)) {
      return null;
    }
    // "A::b(" is the operation b of the unqualified class A, as the grammar parses it
    if (isType(tokens, i + 1, VitruvOCLLexer.COLONCOLON)
        && isType(tokens, i + 2, VitruvOCLLexer.ID)
        && !isType(tokens, i + 3, LPAREN)) {
      return tokens.get(i).getText() + "::" + tokens.get(i + 2).getText();
    }
    return tokens.get(i).getText();
  }

  /** The index of the next {@code context}, block keyword or end of input from {@code i} on. */
  private static int nextBoundary(List<Token> tokens, int i) {
    int j = i;
    while (!isEnd(tokens, j)
        && tokens.get(j).getType() != VitruvOCLLexer.CONTEXT
        && !isKeyword(tokens.get(j))) {
      j++;
    }
    return j;
  }

  private static boolean isEnd(List<Token> tokens, int i) {
    return i >= tokens.size() || tokens.get(i).getType() == Token.EOF;
  }

  private static boolean isType(List<Token> tokens, int i, int type) {
    return !isEnd(tokens, i) && tokens.get(i).getType() == type;
  }

  private static boolean isKeyword(Token token) {
    int type = token.getType();
    return type == INV || type == PRE || type == POST;
  }

  private static ConstraintUnit.Kind kind(Token keyword) {
    int type = keyword.getType();
    if (type == INV) {
      return ConstraintUnit.Kind.INV;
    }
    return type == PRE ? ConstraintUnit.Kind.PRE : ConstraintUnit.Kind.POST;
  }

  /**
   * The text from the start of {@code first} to the end of {@code last}, extended over text the
   * lexer could not tokenize before {@code boundary}, so that it stays visible to the compiler.
   */
  private static String raw(
      CharStream input, Token first, Token last, Token boundary, List<Integer> unlexable) {
    int end = last.getStopIndex();
    for (int index : unlexable) {
      if (index > end && index < boundary.getStartIndex()) {
        end = index;
      }
    }
    return input.getText(Interval.of(first.getStartIndex(), end));
  }

  /** The index of the last character before {@code token}. */
  private static int start(Token token) {
    return token.getStartIndex() - 1;
  }

  private static SourcePosition position(Token token) {
    return new SourcePosition(token.getLine(), token.getCharPositionInLine());
  }

  private static int literalType(String literal) {
    Vocabulary vocabulary = VitruvOCLLexer.VOCABULARY;
    for (int type = 0; type <= vocabulary.getMaxTokenType(); type++) {
      if (literal.equals(vocabulary.getLiteralName(type))) {
        return type;
      }
    }
    throw new IllegalStateException("the grammar has no token " + literal);
  }

  /**
   * The lines of a text without blank and comment lines, joined with {@code \n} and stripped at
   * both ends, together with the document line of each.
   */
  private record Lines(String text, List<Integer> lines) {

    static Lines of(String raw, int firstLine) {
      List<String> kept = new ArrayList<>();
      List<Integer> lines = new ArrayList<>();
      String[] all = raw.split("\n", -1);
      for (int i = 0; i < all.length; i++) {
        String trimmed = all[i].strip();
        if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
          kept.add(all[i]);
          lines.add(firstLine + i);
        }
      }
      return new Lines(String.join("\n", kept).strip(), lines);
    }
  }
}
