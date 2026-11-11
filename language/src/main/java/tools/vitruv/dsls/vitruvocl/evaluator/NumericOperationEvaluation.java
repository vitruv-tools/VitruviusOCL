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
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;

/**
 * Runtime semantics of the numeric operations ({@code abs}, {@code floor}, {@code div}, ...).
 * Extracted from {@link EvaluationVisitor}, which delegates the corresponding {@code visit*Op}
 * methods here.
 */
final class NumericOperationEvaluation {

  private final OperationEvaluationContext context;

  NumericOperationEvaluation(OperationEvaluationContext context) {
    this.context = context;
  }

  /**
   * Evaluates the {@code abs()} operation.
   *
   * <p>Returns the absolute value of each element. Supports Integer, Float, and Double receivers.
   *
   * <p><b>Example:</b> {@code (-3).abs()} → {@code 3}, {@code (-2.5).abs()} → {@code 2.5}
   *
   * @param ctx The abs operation node
   * @return A collection with absolute values
   */
  Value abs(VitruvOCLParser.AbsOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      if (elem.tryGetInt() != null) {
        results.add(new OCLElement.IntValue(Math.abs(elem.tryGetInt())));
      } else if (elem.tryGetFloat() != null) {
        results.add(new OCLElement.FloatValue(Math.abs(elem.tryGetFloat())));
      } else if (elem.tryGetDouble() != null) {
        results.add(new OCLElement.DoubleValue(Math.abs(elem.tryGetDouble())));
      }
    }
    return Value.of(results, receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code floor()} operation.
   *
   * <p>For integers, this is a no-op. For Float and Double, applies {@link Math#floor}.
   *
   * <p><b>Example:</b> {@code 2.7.floor()} → {@code 2.0}, {@code 5.floor()} → {@code 5}
   *
   * @param ctx The floor operation node
   * @return A collection with floored values
   */
  Value floor(VitruvOCLParser.FloorOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      if (elem.tryGetInt() != null) {
        results.add(elem); // no-op for integers
      } else if (elem.tryGetFloat() != null) {
        results.add(new OCLElement.DoubleValue(Math.floor(elem.tryGetFloat())));
      } else if (elem.tryGetDouble() != null) {
        results.add(new OCLElement.DoubleValue(Math.floor(elem.tryGetDouble())));
      }
    }
    return Value.of(results, receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code ceil()} operation.
   *
   * <p>For integers, this is a no-op. For Float and Double, applies {@link Math#ceil}.
   *
   * <p><b>Example:</b> {@code 2.3.ceil()} → {@code 3.0}, {@code 5.ceil()} → {@code 5}
   *
   * @param ctx The ceil operation node
   * @return A collection with ceiled values
   */
  Value ceil(VitruvOCLParser.CeilOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      if (elem.tryGetInt() != null) {
        results.add(elem); // no-op for integers
      } else if (elem.tryGetFloat() != null) {
        results.add(new OCLElement.DoubleValue(Math.ceil(elem.tryGetFloat())));
      } else if (elem.tryGetDouble() != null) {
        results.add(new OCLElement.DoubleValue(Math.ceil(elem.tryGetDouble())));
      }
    }
    return Value.of(results, receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code ceiling()} operation on a numeric receiver.
   *
   * <p>Applies {@link Math#ceil} element-wise to the receiver collection. {@code INTEGER} elements
   * are returned unchanged; {@code FLOAT} and {@code DOUBLE} elements are rounded up to the nearest
   * integer value and returned as {@code DOUBLE}. The result preserves the receiver's collection
   * kind and multiplicity.
   *
   * <p>Example: {@code 2.3->ceiling()} yields {@code 3.0}
   *
   * @param ctx the parse tree node for the {@code ceiling()} operation
   * @return a collection of the same kind as the receiver with each element rounded up to the
   *     nearest integer value; or an error value if any element is not numeric
   */
  Value ceiling(VitruvOCLParser.CeilingOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      if (elem.tryGetInt() != null) {
        results.add(elem);
      } else if (elem.tryGetFloat() != null) {
        results.add(new OCLElement.DoubleValue(Math.ceil(elem.tryGetFloat())));
      } else if (elem.tryGetDouble() != null) {
        results.add(new OCLElement.DoubleValue(Math.ceil(elem.tryGetDouble())));
      }
    }
    return Value.of(results, receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code round()} operation.
   *
   * <p>For integers, this is a no-op. For Float and Double, rounds to the nearest integer using
   * "round half up" semantics ({@link Math#round}).
   *
   * <p><b>Example:</b> {@code 2.5.round()} → {@code 3.0}, {@code 2.4.round()} → {@code 2.0}
   *
   * @param ctx The round operation node
   * @return A collection with rounded values
   */
  Value round(VitruvOCLParser.RoundOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      if (elem.tryGetInt() != null) {
        results.add(elem); // no-op for integers
      } else if (elem.tryGetFloat() != null) {
        results.add(new OCLElement.DoubleValue(Math.round(elem.tryGetFloat())));
      } else if (elem.tryGetDouble() != null) {
        results.add(new OCLElement.DoubleValue(Math.round(elem.tryGetDouble())));
      }
    }
    return Value.of(results, receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code div()} integer division operation.
   *
   * <p>Performs truncating integer division of the receiver by the argument. Both operands must be
   * singleton {@code INTEGER} values and the divisor must be non-zero.
   *
   * <p>Example: {@code 7->div(2)} yields {@code 3}
   *
   * <p>Follows OCL#'s "no object" propagation: an empty receiver/argument, a non-integer operand,
   * or a zero divisor all yield an empty result rather than a numeric placeholder, so that the
   * absence is never mistaken for a genuine computed value downstream.
   *
   * @param ctx the parse tree node for the {@code div()} operation, including the argument
   *     expression
   * @return a singleton {@code INTEGER} value representing the truncated quotient; or an empty
   *     {@code INTEGER} value ("no object") if either operand is not a singleton {@code Integer},
   *     or the divisor is zero
   */
  Value div(VitruvOCLParser.DivOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (receiver.isEmpty() || arg.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }
    Integer left = receiver.getElements().get(0).tryGetInt();
    Integer right = arg.getElements().get(0).tryGetInt();
    if (left == null || right == null) {
      return Value.empty(Type.INTEGER);
    }
    if (right == 0) {
      return Value.empty(Type.INTEGER);
    }
    return Value.intValue(left / right);
  }

  /**
   * Evaluates the {@code mod()} integer remainder operation.
   *
   * <p>Computes the remainder of dividing the receiver by the argument using Java's {@code %}
   * operator. Both operands must be singleton {@code INTEGER} values.
   *
   * <p>Example: {@code 7->mod(3)} yields {@code 1}
   *
   * <p>Follows OCL#'s "no object" propagation: an empty receiver/argument, a non-integer operand,
   * or a zero divisor all yield an empty result rather than a numeric placeholder, so that the
   * absence is never mistaken for a genuine computed value downstream.
   *
   * @param ctx the parse tree node for the {@code mod()} operation, including the argument
   *     expression
   * @return a singleton {@code INTEGER} value representing the remainder; or an empty {@code
   *     INTEGER} value ("no object") if either operand is not a singleton {@code Integer}, or the
   *     divisor is zero
   */
  Value mod(VitruvOCLParser.ModOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (receiver.isEmpty() || arg.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }
    Integer left = receiver.getElements().get(0).tryGetInt();
    Integer right = arg.getElements().get(0).tryGetInt();
    if (left == null || right == null) {
      return Value.empty(Type.INTEGER);
    }
    if (right == 0) {
      return Value.empty(Type.INTEGER);
    }
    return Value.intValue(left % right);
  }
}
