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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Static analysis that tells the {@link EvaluationVisitor} whether it may skip a logical operand
 * whose value no longer matters: skipping is allowed only if evaluating the operand could not have
 * reported a runtime error. Works on the parse tree and the type checker's node types only; results
 * are memoized per node and per constraint, so one instance serves one evaluation run.
 */
final class ErrorFreeAnalysis {

  /** The type checker's type of each parse-tree node. */
  private final ParseTreeProperty<Type> nodeTypes;

  /**
   * Per constraint (parse-tree root): whether nothing outside an operand can carry an error into
   * it; see {@link #cannotReportErrors}.
   */
  private final Map<ParseTree, Boolean> constraintCarriesNoErrors = new IdentityHashMap<>();

  /** Result of {@link #isErrorFree} per parse-tree node. */
  private final Map<ParseTree, Boolean> errorFreeNodes = new IdentityHashMap<>();

  /**
   * Creates the analysis for one evaluation run.
   *
   * @param nodeTypes the type checker's node types of the evaluated parse tree
   */
  ErrorFreeAnalysis(ParseTreeProperty<Type> nodeTypes) {
    this.nodeTypes = nodeTypes;
  }

  /**
   * Whether skipping {@code operand} is unobservable: no expression evaluated while {@code operand}
   * is evaluated can report a runtime error or throw because of the constraint's own content.
   *
   * <p>The evaluator reports runtime errors only for comparisons whose operands are not singletons
   * at runtime, for invalid regular expressions in {@code matches()} and for unbound variables
   * (excluded by the type checker), and throws for integer literals that do not fit an {@code int}
   * and for {@code substring} bounds that are no integers. The check covers {@code operand}, every
   * {@code let} initializer of the constraint (a lazily evaluated {@code let} value from outside
   * can be forced inside {@code operand}; lazy {@code select}/{@code reject}/{@code collect}
   * results whose body could report an error are evaluated strictly where they occur, so they
   * cannot carry an error inside), and the absence of {@code append}/{@code iterate} anywhere in
   * the constraint (their result types are not trusted, as in the incremental analysis, so
   * variables bound to their elements may be mistyped). Within these parts it requires: no {@code
   * matches}, no node typed {@code OclAny} or erroneous (an {@code OclAny} may hold a collection),
   * every comparison operand of a non-collection type, and every integer literal within {@code int}
   * range. Exceptions from the model access itself (the wrapper) are infrastructure failures and
   * are not considered.
   */
  boolean cannotReportErrors(ParserRuleContext operand) {
    return isErrorFree(operand)
        && constraintCarriesNoErrors.computeIfAbsent(
            root(operand), root -> !containsUntrustedTypes(root) && letInitializersErrorFree(root));
  }

  /**
   * Whether {@code node}'s subtree contains an {@code append} or {@code iterate}: their result
   * types are not checked against the values they hold, so variables bound to their elements
   * anywhere in the constraint may hold values of another type than their static one.
   */
  private static boolean containsUntrustedTypes(ParseTree node) {
    if (node instanceof VitruvOCLParser.AppendOpContext
        || node instanceof VitruvOCLParser.IterateOpContext) {
      return true;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      if (containsUntrustedTypes(node.getChild(i))) {
        return true;
      }
    }
    return false;
  }

  /** Whether every {@code let} initializer below {@code node} is error-free. */
  private boolean letInitializersErrorFree(ParseTree node) {
    if (node instanceof VitruvOCLParser.VariableDeclarationContext declaration
        && declaration.varInit != null
        && !isErrorFree(declaration.varInit)) {
      return false;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      if (!letInitializersErrorFree(node.getChild(i))) {
        return false;
      }
    }
    return true;
  }

  /** Whether no node of {@code node}'s subtree can report an error; memoized per node. */
  private boolean isErrorFree(ParseTree node) {
    Boolean known = errorFreeNodes.get(node);
    if (known == null) {
      known = computeErrorFree(node);
      errorFreeNodes.put(node, known);
    }
    return known;
  }

  private boolean computeErrorFree(ParseTree node) {
    if (node instanceof VitruvOCLParser.AppendOpContext
        || node instanceof VitruvOCLParser.IterateOpContext
        || node instanceof VitruvOCLParser.MatchesOpContext) {
      return false; // matches() reports an invalid regular expression at runtime
    }
    if (isUntyped(nodeTypes.get(node))) {
      return false;
    }
    if (isComparison(node)) {
      ParserRuleContext ctx = (ParserRuleContext) node;
      for (ParseTree side : List.of(ctx.getChild(0), ctx.getChild(ctx.getChildCount() - 1))) {
        Type type = staticType(side);
        if (type == null || type.isCollection() || isUntyped(type)) {
          return false;
        }
      }
    }
    if (node instanceof VitruvOCLParser.NumberLitContext literal
        && !fitsLiteral(literal.getText())) {
      return false;
    }
    if (node instanceof VitruvOCLParser.SubstringOpContext substring
        && !(isInteger(staticType(substring.start)) && isInteger(staticType(substring.end)))) {
      return false; // visitSubstringOp casts both bounds to IntValue
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      if (!isErrorFree(node.getChild(i))) {
        return false;
      }
    }
    return true;
  }

  /** {@code OclAny} or an error type, also as the element of an optional. */
  private static boolean isUntyped(Type type) {
    if (type == null) {
      return false;
    }
    if (type == Type.ANY || type.isError()) {
      return true;
    }
    if (type.isOptional()) {
      Type element = type.getElementType();
      return element == Type.ANY || element != null && element.isError();
    }
    return false;
  }

  private static boolean isInteger(Type type) {
    return type != null && type.equals(Type.INTEGER);
  }

  /** Mirrors {@link EvaluationVisitor#visitNumberLit}: whether parsing the literal cannot throw. */
  private static boolean fitsLiteral(String text) {
    if (text.contains(".") || text.contains("e") || text.contains("E")) {
      return true;
    }
    try {
      Integer.parseInt(text);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** The type checker's type of {@code node}, looking through single-child wrapper nodes. */
  private Type staticType(ParseTree node) {
    ParseTree current = node;
    while (current != null) {
      Type type = nodeTypes.get(current);
      if (type != null) {
        return type;
      }
      current = current.getChildCount() == 1 ? current.getChild(0) : null;
    }
    return null;
  }

  private static boolean isComparison(ParseTree node) {
    return node instanceof VitruvOCLParser.EqualityComparisonContext
        || node instanceof VitruvOCLParser.InequalityComparisonContext
        || node instanceof VitruvOCLParser.LessThanComparisonContext
        || node instanceof VitruvOCLParser.LessThanOrEqualComparisonContext
        || node instanceof VitruvOCLParser.GreaterThanComparisonContext
        || node instanceof VitruvOCLParser.GreaterThanOrEqualComparisonContext
        || node instanceof VitruvOCLParser.SingleEqualsOpContext
        || node instanceof VitruvOCLParser.MultiEqualsOpContext
        || node instanceof VitruvOCLParser.InvalidBinaryOpContext;
  }

  private static ParseTree root(ParseTree node) {
    ParseTree current = node;
    while (current.getParent() != null) {
      current = current.getParent();
    }
    return current;
  }
}
