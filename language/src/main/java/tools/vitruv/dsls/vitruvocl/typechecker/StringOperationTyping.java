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

import org.antlr.v4.runtime.ParserRuleContext;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;

/**
 * Type rules of the String operations ({@code concat}, {@code substring}, {@code matches}, ...).
 * Extracted from {@link TypeCheckVisitor}, which delegates the corresponding {@code visit*Op}
 * methods here.
 */
final class StringOperationTyping {

  private final OperationTypingContext context;

  StringOperationTyping(OperationTypingContext context) {
    this.context = context;
  }

  /**
   * Type checks string operations (concat, substring, toUpper, etc.).
   *
   * <p>All validate String receiver and appropriate argument types.
   */
  Type concat(VitruvOCLParser.ConcatOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);

    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "concat requires String receiver");
      return Type.ERROR;
    }

    if (!argType.isConformantTo(Type.STRING)) {
      context.error(ctx, "concat argument must be String");
    }

    context.recordType(ctx, Type.STRING);
    return Type.STRING;
  }

  /**
   * Type-checks the {@code length()} operation on a String receiver.
   *
   * <p>Verifies that the receiver type is conformant to {@link Type#STRING}, then registers and
   * returns {@link Type#INTEGER} as the result type.
   *
   * @param ctx the parse tree node for the {@code length()} operation
   * @return {@link Type#INTEGER}; or {@link Type#ERROR} if the receiver is not conformant to {@code
   *     STRING}
   */
  Type length(VitruvOCLParser.LengthOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'length' requires String receiver");
      return Type.ERROR;
    }
    context.recordType(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Visits a 'substring' operation node in the AST.
   *
   * <p>This operation extracts a substring from a String receiver using start and end indices.
   *
   * <p>Steps:
   *
   * <ul>
   *   <li>Check that the receiver is of type {@link Type#STRING}.
   *   <li>Visit and check that both start and end indices are of type {@link Type#INTEGER}.
   *   <li>If type checks pass, the resulting type is {@link Type#STRING}.
   * </ul>
   *
   * @param ctx the context of the 'substring' operation node in the AST
   * @return {@link Type#STRING} if the receiver and indices are valid; otherwise {@link Type#ERROR}
   */
  Type substring(VitruvOCLParser.SubstringOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'substring' requires String");
      return Type.ERROR;
    }

    Type startType = context.check(ctx.start);
    Type endType = context.check(ctx.end);

    if (!startType.isConformantTo(Type.INTEGER)) {
      context.error(ctx, "Start index must be Integer");
    }

    if (!endType.isConformantTo(Type.INTEGER)) {
      context.error(ctx, "End index must be Integer");
    }

    context.recordType(ctx, Type.STRING);
    return Type.STRING;
  }

  /**
   * Visits a 'toUpper' operation node in the AST.
   *
   * <p>Delegates to {@link #stringNoArg} for type checking and ensures the receiver is a String.
   * The result type is {@link Type#STRING}.
   *
   * @param ctx the context of the 'toUpper' operation node in the AST
   * @return {@link Type#STRING} if the receiver is a String; otherwise {@link Type#ERROR}
   */
  Type toUpper(VitruvOCLParser.ToUpperOpContext ctx) {
    return stringNoArg(ctx, "toUpper");
  }

  /**
   * Visits a 'toLower' operation node in the AST.
   *
   * <p>Delegates to {@link #stringNoArg} for type checking and ensures the receiver is a String.
   * The result type is {@link Type#STRING}.
   *
   * @param ctx the context of the 'toLower' operation node in the AST
   * @return {@link Type#STRING} if the receiver is a String; otherwise {@link Type#ERROR}
   */
  Type toLower(VitruvOCLParser.ToLowerOpContext ctx) {
    return stringNoArg(ctx, "toLower");
  }

  /**
   * Visits an 'indexOf' operation node in the AST.
   *
   * <p>This operation returns the position of a substring within a String receiver.
   *
   * <ul>
   *   <li>Check that the receiver is {@link Type#STRING}.
   *   <li>Check that the argument is {@link Type#STRING}.
   *   <li>If valid, the resulting type is {@link Type#INTEGER}.
   * </ul>
   *
   * @param ctx the context of the 'indexOf' operation node in the AST
   * @return {@link Type#INTEGER} if the receiver and argument are valid; otherwise {@link
   *     Type#ERROR}
   */
  Type indexOf(VitruvOCLParser.IndexOfOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'indexOf' requires String");
      return Type.ERROR;
    }

    Type argType = context.check(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      context.error(ctx, "Argument must be String");
    }

    context.recordType(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Visits an 'equalsIgnoreCase' operation node in the AST.
   *
   * <p>This operation compares the receiver String with another String argument, ignoring case.
   *
   * <ul>
   *   <li>Check that the receiver is {@link Type#STRING}.
   *   <li>Check that the argument is {@link Type#STRING}.
   *   <li>If valid, the resulting type is {@link Type#BOOLEAN}.
   * </ul>
   *
   * @param ctx the context of the 'equalsIgnoreCase' operation node in the AST
   * @return {@link Type#BOOLEAN} if the receiver and argument are valid; otherwise {@link
   *     Type#ERROR}
   */
  Type equalsIgnoreCase(VitruvOCLParser.EqualsIgnoreCaseOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'equalsIgnoreCase' requires String");
      return Type.ERROR;
    }

    Type argType = context.check(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      context.error(ctx, "Argument must be String");
    }

    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code toInteger()} operation.
   *
   * @param ctx The toInteger operation node
   * @return Type.INTEGER
   */
  Type toInteger(VitruvOCLParser.ToIntegerOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'toInteger' requires String receiver");
      return Type.ERROR;
    }
    context.recordType(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type checks the {@code toReal()} operation.
   *
   * @param ctx The toReal operation node
   * @return Type.DOUBLE
   */
  Type toReal(VitruvOCLParser.ToRealOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'toReal' requires String receiver");
      return Type.ERROR;
    }
    context.recordType(ctx, Type.DOUBLE);
    return Type.DOUBLE;
  }

  /**
   * Type checks the {@code characters()} operation.
   *
   * <p>Splits a String into a Sequence of single-character Strings.
   *
   * @param ctx The characters operation node
   * @return Sequence(String)
   */
  Type characters(VitruvOCLParser.CharactersOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'characters' requires String receiver");
      return Type.ERROR;
    }
    Type resultType = Type.sequence(Type.STRING);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code matches()} operation.
   *
   * <p>Tests whether the receiver matches a regex pattern.
   *
   * @param ctx The matches operation node
   * @return Type.BOOLEAN
   */
  Type matches(VitruvOCLParser.MatchesOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'matches' requires String receiver");
      return Type.ERROR;
    }
    Type argType = context.check(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      context.error(ctx, "matches() pattern argument must be String, got " + argType);
    }
    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code substituteAll()} operation.
   *
   * @param ctx The substituteAll operation node
   * @return Type.STRING
   */
  Type substituteAll(VitruvOCLParser.SubstituteAllOpContext ctx) {
    return substitute(ctx, ctx.pattern, ctx.replacement, "substituteAll");
  }

  /**
   * Type checks the {@code substituteFirst()} operation.
   *
   * @param ctx The substituteFirst operation node
   * @return Type.STRING
   */
  Type substituteFirst(VitruvOCLParser.SubstituteFirstOpContext ctx) {
    return substitute(ctx, ctx.pattern, ctx.replacement, "substituteFirst");
  }

  /**
   * Type checks the {@code tokenize()} operation.
   *
   * <p>Splits the receiver String by a delimiter, returning a Sequence of Strings.
   *
   * @param ctx The tokenize operation node
   * @return Sequence(String)
   */
  Type tokenize(VitruvOCLParser.TokenizeOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'tokenize' requires String receiver");
      return Type.ERROR;
    }
    Type argType = context.check(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      context.error(ctx, "tokenize() delimiter must be String, got " + argType);
    }
    Type resultType = Type.sequence(Type.STRING);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /** Helper for string operations with no arguments. */
  private Type stringNoArg(ParserRuleContext ctx, String opName) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'" + opName + "' requires String");
      return Type.ERROR;
    }

    context.recordType(ctx, Type.STRING);
    return Type.STRING;
  }

  /** Helper for substituteAll / substituteFirst — identical type rules. */
  private Type substitute(
      ParserRuleContext ctx,
      VitruvOCLParser.ExpCSContext patternCtx,
      VitruvOCLParser.ExpCSContext replacementCtx,
      String opName) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isConformantTo(Type.STRING)) {
      context.error(ctx, "'" + opName + "' requires String receiver");
      return Type.ERROR;
    }
    Type patternType = context.check(patternCtx);
    if (!patternType.isConformantTo(Type.STRING)) {
      context.error(ctx, opName + "() pattern must be String, got " + patternType);
    }
    Type replacementType = context.check(replacementCtx);
    if (!replacementType.isConformantTo(Type.STRING)) {
      context.error(ctx, opName + "() replacement must be String, got " + replacementType);
    }
    context.recordType(ctx, Type.STRING);
    return Type.STRING;
  }
}
