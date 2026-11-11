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

package tools.vitruv.dsls.vitruvocl.evaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;

/**
 * Runtime semantics of the String operations ({@code concat}, {@code substring}, {@code matches},
 * ...). Extracted from {@link EvaluationVisitor}, which delegates the corresponding {@code
 * visit*Op} methods here.
 */
final class StringOperationEvaluation {

  /** Upper bound for {@link #REGEX_CACHE}; cleared wholesale when exceeded. */
  private static final int REGEX_CACHE_LIMIT = 256;

  /**
   * Compiled {@code matches()} patterns by source string. A pattern usually repeats for every
   * element of an iterator body, so compiling it once avoids per-element recompilation.
   */
  private static final Map<String, java.util.Optional<java.util.regex.Pattern>> REGEX_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final OperationEvaluationContext context;

  StringOperationEvaluation(OperationEvaluationContext context) {
    this.context = context;
  }

  /**
   * Evaluates the {@code concat()} operation.
   *
   * <p>Concatenates two strings together.
   *
   * <p><b>Example:</b> {@code 'Hello'.concat(' World')} → {@code 'Hello World'}
   *
   * @param ctx The concat operation node
   * @return A singleton string value
   */
  Value concat(VitruvOCLParser.ConcatOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return receiver;
    }

    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return receiver;
    }

    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return receiver;
    }

    String argStr = arg.getElements().get(0).tryGetString();
    if (argStr == null) {
      return receiver;
    }

    return Value.stringValue(str + argStr);
  }

  /**
   * Evaluates the {@code length()} operation on a String receiver.
   *
   * <p>Expects a singleton receiver containing a {@code String} value. Returns the number of
   * characters in the string as an {@code INTEGER} singleton.
   *
   * @param ctx the parse tree node for the {@code length()} operation
   * @return a singleton {@code INTEGER} value representing the string length, or an error value if
   *     the receiver is not a singleton {@code String}
   */
  Value length(VitruvOCLParser.LengthOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.size() == 1) {
      String str = receiver.getElements().get(0).tryGetString();
      if (str != null) {
        return Value.intValue(str.length());
      }
    }
    return Value.intValue(0);
  }

  /**
   * Evaluates the substring operation on string values.
   *
   * <p>Extracts a substring from each string in the receiver collection using 1-based OCL indexing.
   * The operation takes two integer arguments: start position (inclusive) and end position
   * (exclusive in Java terms).
   *
   * <p>Example: "Hello".substring(2, 4) returns "el" (positions 2-3 in OCL's 1-based indexing)
   *
   * @param ctx The substring operation context containing start and end expressions
   * @return A collection of substring values, empty if arguments are invalid or out of bounds
   */
  Value substring(VitruvOCLParser.SubstringOpContext ctx) {
    // Get receiver from parent context
    Value receiver = context.receiver();

    Value startVal = context.evaluate(ctx.start);
    Value endVal = context.evaluate(ctx.end);

    if (startVal.isEmpty() || endVal.isEmpty()) {
      return Value.of(List.of(), Type.STRING);
    }

    int start = ((OCLElement.IntValue) startVal.getElements().get(0)).value();
    int end = ((OCLElement.IntValue) endVal.getElements().get(0)).value();

    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      if (elem instanceof OCLElement.StringValue(String str)) {
        int javaStart = start - 1;
        int javaEnd = end;

        if (javaStart >= 0 && javaEnd <= str.length() && javaStart < javaEnd) {
          results.add(new OCLElement.StringValue(str.substring(javaStart, javaEnd)));
        }
      }
    }

    return Value.of(results, Type.STRING);
  }

  /**
   * Evaluates the {@code toUpper()} operation.
   *
   * <p>Converts a string to uppercase.
   *
   * <p><b>Example:</b> {@code 'hello'.toUpper()} → {@code 'HELLO'}
   *
   * @param ctx The toUpper operation node
   * @return A singleton string value in uppercase
   */
  Value toUpper(VitruvOCLParser.ToUpperOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return receiver;
    }

    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return receiver;
    }

    return Value.stringValue(str.toUpperCase());
  }

  /**
   * Evaluates the {@code toLower()} operation.
   *
   * <p>Converts a string to lowercase.
   *
   * <p><b>Example:</b> {@code 'HELLO'.toLower()} → {@code 'hello'}
   *
   * @param ctx The toLower operation node
   * @return A singleton string value in lowercase
   */
  Value toLower(VitruvOCLParser.ToLowerOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return receiver;
    }

    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return receiver;
    }

    return Value.stringValue(str.toLowerCase());
  }

  /**
   * Evaluates the {@code indexOf()} operation.
   *
   * <p>Returns the 1-based index of the first occurrence of a substring, or 0 if not found.
   *
   * <p><b>Example:</b> {@code 'hello'.indexOf('ll')} → {@code 3}
   *
   * @param ctx The indexOf operation node
   * @return A singleton integer value (1-based index, or 0 if not found)
   */
  Value indexOf(VitruvOCLParser.IndexOfOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.intValue(0);
    }

    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return Value.intValue(0);
    }

    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return Value.intValue(0);
    }

    String searchStr = arg.getElements().get(0).tryGetString();
    if (searchStr == null) {
      return Value.intValue(0);
    }

    // Convert Java's 0-based index to OCL's 1-based index
    int javaIndex = str.indexOf(searchStr);
    int oclIndex = (javaIndex == -1) ? 0 : javaIndex + 1;
    return Value.intValue(oclIndex);
  }

  /**
   * Evaluates the {@code equalsIgnoreCase()} operation.
   *
   * <p>Compares two strings ignoring case differences.
   *
   * <p><b>Example:</b> {@code 'Hello'.equalsIgnoreCase('HELLO')} → {@code true}
   *
   * @param ctx The equalsIgnoreCase operation node
   * @return A singleton boolean value
   */
  Value equalsIgnoreCase(VitruvOCLParser.EqualsIgnoreCaseOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.boolValue(false);
    }

    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return Value.boolValue(false);
    }

    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return Value.boolValue(false);
    }

    String compareStr = arg.getElements().get(0).tryGetString();
    if (compareStr == null) {
      return Value.boolValue(false);
    }

    return Value.boolValue(str.equalsIgnoreCase(compareStr));
  }

  /**
   * Evaluates the {@code toInteger()} conversion operation on a String receiver.
   *
   * <p>Parses the receiver string as a decimal integer using {@link Integer#parseInt}, trimming
   * leading and trailing whitespace before parsing. The result is returned as an {@code INTEGER}
   * singleton.
   *
   * <p>Example: {@code ' 42 '->toInteger()} yields {@code 42}
   *
   * @param ctx the parse tree node for the {@code toInteger()} operation
   * @return a singleton {@code INTEGER} value parsed from the receiver string; or an empty {@code
   *     INTEGER} value ("no object") if the receiver is not a singleton {@code String} or the
   *     string cannot be parsed as a decimal integer
   */
  Value toInteger(VitruvOCLParser.ToIntegerOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }
    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return Value.empty(Type.INTEGER);
    }
    try {
      return Value.intValue(Integer.parseInt(str.trim()));
    } catch (NumberFormatException e) {
      return Value.empty(Type.INTEGER); // unparsable: "no object", like div by zero
    }
  }

  /**
   * Evaluates the {@code toReal()} conversion operation on a String receiver.
   *
   * <p>Parses the receiver string as a floating-point number using {@link Double#parseDouble},
   * trimming leading and trailing whitespace before parsing. The result is returned as a {@code
   * DOUBLE} singleton.
   *
   * <p>Example: {@code ' 3.14 '->toReal()} yields {@code 3.14}
   *
   * @param ctx the parse tree node for the {@code toReal()} operation
   * @return a singleton {@code DOUBLE} value parsed from the receiver string; or an empty {@code
   *     DOUBLE} value ("no object") if the receiver is not a singleton {@code String} or the string
   *     cannot be parsed as a floating-point number
   */
  Value toReal(VitruvOCLParser.ToRealOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.empty(Type.DOUBLE);
    }
    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return Value.empty(Type.DOUBLE);
    }
    try {
      return Value.doubleValue(Double.parseDouble(str.trim()));
    } catch (NumberFormatException e) {
      return Value.empty(Type.DOUBLE); // unparsable: "no object", like div by zero
    }
  }

  /**
   * Evaluates the {@code characters()} operation on a String receiver.
   *
   * <p>Splits the receiver string into its individual characters and returns them as a {@code
   * Sequence<String>}, preserving order and allowing duplicates.
   *
   * <p>Example: {@code 'hello'->characters()} yields {@code Sequence{'h','e','l','l','o'}}
   *
   * @param ctx the parse tree node for the {@code characters()} operation
   * @return a {@code Sequence<String>} of single-character strings in source order; or an error
   *     value if the receiver is not a singleton {@code String}
   */
  Value characters(VitruvOCLParser.CharactersOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.of(List.of(), Type.sequence(Type.STRING));
    }
    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return Value.of(List.of(), Type.sequence(Type.STRING));
    }
    List<OCLElement> chars = new ArrayList<>();
    for (char c : str.toCharArray()) {
      chars.add(new OCLElement.StringValue(String.valueOf(c)));
    }
    return Value.of(chars, Type.sequence(Type.STRING));
  }

  /**
   * Evaluates the {@code matches()} operation on a String receiver.
   *
   * <p>Tests whether the receiver string matches the given regular expression pattern using {@link
   * String#matches}, which requires the pattern to match the entire string. Both the receiver and
   * the argument must be singleton {@code String} values.
   *
   * <p>Example: {@code 'hello123'->matches('[a-z]+\\d+')} yields {@code true}
   *
   * @param ctx the parse tree node for the {@code matches()} operation, including the pattern
   *     argument expression
   * @return a singleton {@code BOOLEAN} value — {@code true} if the receiver string matches the
   *     entire pattern, {@code false} otherwise; or an error value if the receiver or argument is
   *     not a singleton {@code String}
   */
  Value matches(VitruvOCLParser.MatchesOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.boolValue(false);
    }
    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return Value.boolValue(false);
    }
    Value argVal = context.evaluate(ctx.arg);
    if (argVal.isEmpty()) {
      return Value.boolValue(false);
    }
    String pattern = argVal.getElements().get(0).tryGetString();
    if (pattern == null) {
      return Value.boolValue(false);
    }
    java.util.regex.Pattern compiled = compileCached(pattern);
    if (compiled == null) {
      context.error(ctx, "Invalid regular expression in matches(): " + pattern);
      return Value.empty(Type.ERROR);
    }
    return Value.boolValue(compiled.matcher(str).matches());
  }

  /**
   * Evaluates the {@code substituteAll()} operation on a String receiver.
   *
   * <p>Replaces every literal occurrence of the pattern string within the receiver with the
   * replacement string using {@link String#replace}. No regex interpretation is applied — the
   * pattern is matched as a plain substring. If the pattern is not found, the receiver string is
   * returned unchanged.
   *
   * <p>Example: {@code 'abcabc'->substituteAll('b', 'X')} yields {@code 'aXcaXc'}
   *
   * @param ctx the parse tree node for the {@code substituteAll()} operation, including the pattern
   *     and replacement argument expressions
   * @return a singleton {@code STRING} value with all occurrences of the pattern replaced, or the
   *     original string if the pattern is not found; or an error value if the receiver, pattern, or
   *     replacement is not a singleton {@code String}
   */
  Value substituteAll(VitruvOCLParser.SubstituteAllOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return receiver;
    }
    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return receiver;
    }
    Value patternVal = context.evaluate(ctx.pattern);
    Value replacementVal = context.evaluate(ctx.replacement);
    if (patternVal.isEmpty() || replacementVal.isEmpty()) {
      return receiver;
    }
    String pattern = patternVal.getElements().get(0).tryGetString();
    String replacement = replacementVal.getElements().get(0).tryGetString();
    if (pattern == null || replacement == null) {
      return receiver;
    }
    return Value.stringValue(str.replace(pattern, replacement));
  }

  /**
   * Evaluates the {@code substituteFirst()} operation on a String receiver.
   *
   * <p>Replaces the first literal occurrence of the pattern string within the receiver with the
   * replacement string. Unlike {@code replaceAll()}, no regex interpretation is applied — the
   * pattern is matched as a plain substring via {@link String#indexOf}. If the pattern is not
   * found, the receiver string is returned unchanged.
   *
   * <p>Example: {@code 'abcabc'->substituteFirst('b', 'X')} yields {@code 'aXcabc'}
   *
   * @param ctx the parse tree node for the {@code substituteFirst()} operation, including the
   *     pattern and replacement argument expressions
   * @return a singleton {@code STRING} value with the first occurrence of the pattern replaced, or
   *     the original string if the pattern is not found; or an error value if the receiver,
   *     pattern, or replacement is not a singleton {@code String}
   */
  Value substituteFirst(VitruvOCLParser.SubstituteFirstOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return receiver;
    }
    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return receiver;
    }
    Value patternVal = context.evaluate(ctx.pattern);
    Value replacementVal = context.evaluate(ctx.replacement);
    if (patternVal.isEmpty() || replacementVal.isEmpty()) {
      return receiver;
    }
    String pattern = patternVal.getElements().get(0).tryGetString();
    String replacement = replacementVal.getElements().get(0).tryGetString();
    if (pattern == null || replacement == null) {
      return receiver;
    }
    int idx = str.indexOf(pattern);
    if (idx == -1) {
      return Value.stringValue(str);
    }
    return Value.stringValue(
        str.substring(0, idx) + replacement + str.substring(idx + pattern.length()));
  }

  /**
   * Evaluates the {@code tokenize()} operation on a String receiver.
   *
   * <p>Splits the receiver string on each literal occurrence of the delimiter string, returning the
   * resulting tokens as a {@code Sequence<String>}. The delimiter is treated as a plain substring
   * (not a regex) via {@link java.util.regex.Pattern#quote}. Trailing empty tokens are preserved
   * ({@code limit = -1}). If the delimiter is empty, the receiver string is returned as a
   * single-element sequence.
   *
   * <p>Example: {@code 'a,b,,c'->tokenize(',')} yields {@code Sequence{'a','b','','c'}}
   *
   * @param ctx the parse tree node for the {@code tokenize()} operation, including the delimiter
   *     argument expression
   * @return a {@code Sequence<String>} of tokens produced by splitting on the delimiter; or an
   *     error value if the receiver or delimiter is not a singleton {@code String}
   */
  Value tokenize(VitruvOCLParser.TokenizeOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.of(List.of(), Type.sequence(Type.STRING));
    }
    String str = receiver.getElements().get(0).tryGetString();
    if (str == null) {
      return Value.of(List.of(), Type.sequence(Type.STRING));
    }
    Value argVal = context.evaluate(ctx.arg);
    if (argVal.isEmpty()) {
      return Value.of(List.of(new OCLElement.StringValue(str)), Type.sequence(Type.STRING));
    }
    String delimiter = argVal.getElements().get(0).tryGetString();
    if (delimiter == null) {
      return Value.of(List.of(new OCLElement.StringValue(str)), Type.sequence(Type.STRING));
    }
    List<OCLElement> tokens = new ArrayList<>();
    if (delimiter.isEmpty()) {
      tokens.add(new OCLElement.StringValue(str));
    } else {
      for (String token : str.split(java.util.regex.Pattern.quote(delimiter), -1)) {
        tokens.add(new OCLElement.StringValue(token));
      }
    }
    return Value.of(tokens, Type.sequence(Type.STRING));
  }

  /**
   * Returns the compiled pattern for {@code regex}, cached across calls, or {@code null} if the
   * pattern is syntactically invalid (then {@code matches()} reports an evaluation error instead of
   * aborting with an uncaught {@link java.util.regex.PatternSyntaxException}).
   */
  private static java.util.regex.Pattern compileCached(String regex) {
    if (REGEX_CACHE.size() > REGEX_CACHE_LIMIT) {
      REGEX_CACHE.clear();
    }
    return REGEX_CACHE
        .computeIfAbsent(
            regex,
            key -> {
              try {
                return java.util.Optional.of(java.util.regex.Pattern.compile(key));
              } catch (java.util.regex.PatternSyntaxException e) {
                return java.util.Optional.empty();
              }
            })
        .orElse(null);
  }
}
