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
import java.util.function.Predicate;
import org.antlr.v4.runtime.ParserRuleContext;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.transaction.TransactionModel;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Runtime semantics of the constructs evaluated against the transaction of a {@code post} block:
 * the pre-state read of {@code @pre} and the {@code OCLisNew}/{@code OCLisModified}/{@code
 * OCLisDeleted} lifecycle predicates. Extracted from {@link EvaluationVisitor}.
 */
final class TransactionEvaluation {

  private final OperationEvaluationContext context;

  /**
   * The ordered atomic changes between the pre-state and the current post-state; empty for callers
   * without a transaction (see {@link TransactionModel}).
   */
  private final TransactionModel transaction;

  TransactionEvaluation(OperationEvaluationContext context, TransactionModel transaction) {
    this.context = context;
    this.transaction = transaction;
  }

  /**
   * Pre-state counterpart of the live property navigation: reads each receiver element's feature
   * value as it existed at transaction start, for the {@code self.attr@pre} form.
   *
   * <p>Unlike the live path, this eagerly materializes the receiver and does not deduplicate a
   * unique result — {@code @pre} is a comparatively rare, non-hot-path construct, so the added
   * simplicity outweighs the lazy-pipeline benefits that matter for large collection chains.
   *
   * @param navCtx the property navigation node the {@code @pre} step is attached to
   * @param receiver the receiver value (must contain metaclass instances)
   * @return collection of pre-state property values from all receiver instances
   */
  Value preStateProperty(VitruvOCLParser.PropertyNavContext navCtx, Value receiver) {
    String propertyName = navCtx.propertyAccess().propertyName.getText();
    Type resultType = context.nodeType(navCtx);
    Type finalType = resultType != null ? resultType : Type.set(Type.ANY);

    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      EObject instance = elem.tryGetInstance();
      if (instance == null) {
        continue;
      }
      EStructuralFeature feature = instance.eClass().getEStructuralFeature(propertyName);
      if (feature == null) {
        continue;
      }
      if (feature.isMany()) {
        List<?> currentList = (List<?>) instance.eGet(feature);
        List<Object> currentValues = new ArrayList<>(currentList);
        List<Object> preValues =
            transaction.getPreStateMultiValue(instance, feature, currentValues);
        for (Object v : preValues) {
          if (v != null) {
            results.add(NavigationEvaluation.wrap(v));
          }
        }
      } else {
        Object currentVal = instance.eGet(feature);
        Object preVal = transaction.getPreStateSingleValue(instance, feature, currentVal);
        if (preVal != null) {
          results.add(NavigationEvaluation.wrap(preVal));
        }
      }
    }
    return Value.of(results, finalType);
  }

  /**
   * Evaluates {@code OCLisNew} (bare or with a {@code (attr => val, ...)} aggregate) for each
   * receiver element: {@code true} iff the underlying instance was created in this evaluation's
   * transaction and, for the aggregate form, its current (post-state) attribute values match.
   *
   * @param ctx The OCLisNew predicate node
   * @return one Boolean per receiver element
   */
  Value isNew(VitruvOCLParser.OclIsNewPredContext ctx) {
    return perElement(
        ctx,
        instance ->
            transaction.wasCreated(instance)
                && matchesAggregate(instance, ctx.lifecycleAggregateArgs()));
  }

  /**
   * Evaluates {@code OCLisModified} (bare or with a {@code (attr => val, ...)} aggregate) for each
   * receiver element: {@code true} iff the underlying instance had any feature changed in this
   * evaluation's transaction and, for the aggregate form, its current (post-state, i.e.
   * target-value, not delta) attribute values match.
   *
   * @param ctx The OCLisModified predicate node
   * @return one Boolean per receiver element
   */
  Value isModified(VitruvOCLParser.OclIsModifiedPredContext ctx) {
    return perElement(
        ctx,
        instance ->
            transaction.wasModified(instance)
                && matchesAggregate(instance, ctx.lifecycleAggregateArgs()));
  }

  /**
   * Evaluates {@code OCLisDeleted} for each receiver element: {@code true} iff the underlying
   * instance was deleted in this evaluation's transaction. No aggregate form exists.
   *
   * <p>Structurally, {@code self.OCLisDeleted} is always {@code false}: the context loop only ever
   * iterates currently-live instances, so a deleted object can never be bound to {@code self}. This
   * only becomes meaningful when navigating to a still-reachable (e.g. {@code @pre}-recovered)
   * reference to a since-deleted object.
   *
   * @param ctx The OCLisDeleted predicate node
   * @return one Boolean per receiver element
   */
  Value isDeleted(VitruvOCLParser.OclIsDeletedPredContext ctx) {
    return perElement(ctx, transaction::wasDeleted);
  }

  /**
   * One Boolean per receiver element: whether its instance satisfies {@code predicate}; {@code
   * false} for elements that are no model objects.
   */
  private Value perElement(ParserRuleContext ctx, Predicate<EObject> predicate) {
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : context.receiver().getElements()) {
      EObject instance = elem.tryGetInstance();
      results.add(new OCLElement.BoolValue(instance != null && predicate.test(instance)));
    }
    Type resultType = context.nodeType(ctx);
    return Value.of(results, resultType != null ? resultType : Type.singleton(Type.BOOLEAN));
  }

  /**
   * Checks a {@code (attr => val, ...)} aggregate's attribute values against {@code instance}'s
   * <em>current</em> (post-state) values — target-value semantics, not deltas, and not {@code
   * @pre}-relative. Returns {@code true} (vacuously) for the bare, no-aggregate form.
   */
  private boolean matchesAggregate(
      EObject instance, VitruvOCLParser.LifecycleAggregateArgsContext argsCtx) {
    if (argsCtx == null) {
      return true;
    }
    for (VitruvOCLParser.LifecycleAggregateArgContext arg : argsCtx.lifecycleAggregateArg()) {
      String attrName = arg.attr.getText();
      EStructuralFeature feature = instance.eClass().getEStructuralFeature(attrName);
      if (feature == null) {
        return false; // type-checker already rejects unknown attributes; defensive fallback
      }
      Value expectedValue = context.evaluate(arg.value);
      if (expectedValue == null || expectedValue.isEmpty()) {
        return false;
      }
      Object actualRaw = instance.eGet(feature);
      if (actualRaw == null) {
        return false;
      }
      if (!OCLElement.semanticEquals(
          NavigationEvaluation.wrap(actualRaw), expectedValue.getElements().get(0))) {
        return false;
      }
    }
    return true;
  }
}
