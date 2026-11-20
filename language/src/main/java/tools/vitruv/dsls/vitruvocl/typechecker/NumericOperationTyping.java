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
import tools.vitruv.dsls.vitruvocl.types.Type;
import tools.vitruv.dsls.vitruvocl.types.TypeResolver;

/**
 * Type rules of the numeric operations ({@code abs}, {@code floor}, {@code div}, ...). Extracted
 * from {@link TypeCheckVisitor}, which delegates the corresponding {@code visit*Op} methods here.
 */
final class NumericOperationTyping {

  private final OperationTypingContext context;

  NumericOperationTyping(OperationTypingContext context) {
    this.context = context;
  }

  /**
   * Type checks numeric operations (abs, floor, ceil, round).
   *
   * <p>All require numeric collection receivers and preserve collection type.
   */
  Type abs(VitruvOCLParser.AbsOpContext ctx) {
    return scalarNumeric(ctx, "abs");
  }

  /**
   * Visits a 'floor' operation node in the AST.
   *
   * <p>This method delegates to {@link #scalarNumeric} to perform type checking and result type
   * computation for the 'floor' numeric operation.
   *
   * @param ctx the context of the 'floor' operation node in the AST
   * @return the resulting numeric type of the operation
   */
  Type floor(VitruvOCLParser.FloorOpContext ctx) {
    return rounding(ctx, "floor");
  }

  /**
   * Visits a 'ceil' operation node in the AST.
   *
   * <p>This method delegates to {@link #scalarNumeric} to perform type checking and result type
   * computation for the 'ceil' numeric operation.
   *
   * @param ctx the context of the 'ceil' operation node in the AST
   * @return the resulting numeric type of the operation
   */
  Type ceil(VitruvOCLParser.CeilOpContext ctx) {
    return rounding(ctx, "ceil");
  }

  /**
   * Type checks the {@code ceiling()} operation (alias for ceil).
   *
   * @param ctx The ceiling operation node
   * @return Integer, with the receiver's multiplicity
   */
  Type ceiling(VitruvOCLParser.CeilingOpContext ctx) {
    return rounding(ctx, "ceiling");
  }

  /**
   * Visits a 'round' operation node in the AST.
   *
   * <p>This method delegates to {@link #scalarNumeric} to perform type checking and result type
   * computation for the 'round' numeric operation.
   *
   * @param ctx the context of the 'round' operation node in the AST
   * @return the resulting numeric type of the operation
   */
  Type round(VitruvOCLParser.RoundOpContext ctx) {
    return rounding(ctx, "round");
  }

  /**
   * Type checks the {@code div()} operation (integer division).
   *
   * <p>Both receiver and argument must be Integer.
   *
   * @param ctx The div operation node
   * @return Type.INTEGER
   */
  Type div(VitruvOCLParser.DivOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);

    Type baseReceiver = receiverType.isSingleton() ? receiverType.getElementType() : receiverType;
    Type baseArg = argType.isSingleton() ? argType.getElementType() : argType;

    if (baseReceiver != Type.INTEGER && baseReceiver != Type.ERROR) {
      context.error(ctx, "div() requires Integer receiver, got " + receiverType);
    }
    if (baseArg != Type.INTEGER && baseArg != Type.ERROR) {
      context.error(ctx, "div() requires Integer argument, got " + argType);
    }
    context.recordType(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type checks the {@code mod()} operation (integer remainder).
   *
   * <p>Both receiver and argument must be Integer.
   *
   * @param ctx The mod operation node
   * @return Type.INTEGER
   */
  Type mod(VitruvOCLParser.ModOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);

    Type baseReceiver = receiverType.isSingleton() ? receiverType.getElementType() : receiverType;
    Type baseArg = argType.isSingleton() ? argType.getElementType() : argType;

    if (baseReceiver != Type.INTEGER && baseReceiver != Type.ERROR) {
      context.error(ctx, "mod() requires Integer receiver, got " + receiverType);
    }
    if (baseArg != Type.INTEGER && baseArg != Type.ERROR) {
      context.error(ctx, "mod() requires Integer argument, got " + argType);
    }
    context.recordType(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type checks a rounding operation ({@code floor}, {@code ceil}, {@code ceiling}, {@code round}):
   * like {@link #scalarNumeric}, but the result is an Integer, as in OCL.
   */
  private Type rounding(ParserRuleContext ctx, String opName) {
    Type receiverType = scalarNumeric(ctx, opName);
    if (receiverType == Type.ERROR) {
      return Type.ERROR;
    }
    Type resultType = receiverType.isSingleton() ? Type.singleton(Type.INTEGER) : Type.INTEGER;
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Helper for numeric element operations ({@code abs}, {@code floor}, {@code ceil}, {@code
   * round}).
   *
   * <p>Accepts both collections and singletons ¡T! — a scalar Real/Float/Integer is ¡T! and numeric
   * operations on it are valid.
   *
   * @param ctx the operation node
   * @param opName operation name for error messages
   * @return the receiver's type unchanged, or {@link Type#ERROR}
   */
  private Type scalarNumeric(ParserRuleContext ctx, String opName) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    Type checkType = receiverType.isSingleton() ? receiverType.getElementType() : receiverType;

    // Reject collections — abs/floor/ceil/round require scalar receiver
    if (checkType.isCollection()) {
      context.error(ctx, "'" + opName + "' requires scalar numeric receiver, not a collection");
      return Type.ERROR;
    }

    if (TypeResolver.isNumeric(checkType)) {
      context.recordType(ctx, receiverType);
      return receiverType;
    }

    if (checkType != Type.ERROR) {
      context.error(ctx, "'" + opName + "' requires numeric receiver");
    }
    return Type.ERROR;
  }
}
