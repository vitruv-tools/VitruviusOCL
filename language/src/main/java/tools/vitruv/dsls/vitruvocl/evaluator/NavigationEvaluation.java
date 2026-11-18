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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.value.LazyOperations;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElementSource;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Runtime semantics of navigation chains ({@code a.b.op()...}): each step uses the previous result
 * as its receiver; property steps read EMF features lazily, operation and lifecycle steps are
 * evaluated with the receiver pushed. Extracted from {@link EvaluationVisitor}.
 */
final class NavigationEvaluation {

  private final OperationEvaluationContext context;
  private final TransactionEvaluation transactions;

  NavigationEvaluation(OperationEvaluationContext context, TransactionEvaluation transactions) {
    this.context = context;
    this.transactions = transactions;
  }

  /**
   * Applies a navigation chain to {@code receiver}. A property step immediately followed by
   * {@code @pre} reads the transaction's pre-state; the {@code @pre} step itself is consumed by it.
   *
   * @param navChain the navigation steps
   * @param receiver the value the first step is applied to
   * @return the value after the last step
   */
  Value navigate(List<VitruvOCLParser.NavigationChainCSContext> navChain, Value receiver) {
    Value currentValue = receiver;
    for (int i = 0; i < navChain.size(); i++) {
      VitruvOCLParser.NavigationChainCSContext nav = navChain.get(i);
      if (nav.AT_PRE() != null) {
        continue;
      }
      boolean nextIsAtPre = i + 1 < navChain.size() && navChain.get(i + 1).AT_PRE() != null;
      if (nextIsAtPre
          && nav.navigationTargetCS() instanceof VitruvOCLParser.PropertyNavContext propNav) {
        currentValue = transactions.preStateProperty(propNav, currentValue);
      } else {
        currentValue = step(nav, currentValue);
      }
    }
    return currentValue;
  }

  /** Evaluates one navigation step on {@code receiver}, dispatching on the step's target. */
  private Value step(VitruvOCLParser.NavigationChainCSContext ctx, Value receiver) {
    // dispatch on the target's alternative directly (no visitor allocated per step and element)
    VitruvOCLParser.NavigationTargetCSContext target = ctx.navigationTargetCS();
    if (target instanceof VitruvOCLParser.PropertyNavContext property) {
      return property(property, receiver);
    }
    if (target instanceof VitruvOCLParser.OperationNavContext operation) {
      return context.evaluateChildrenWithReceiver(receiver, operation.operationCall());
    }
    if (target instanceof VitruvOCLParser.LifecycleNavContext lifecycle) {
      return context.evaluateWithReceiver(receiver, lifecycle.lifecyclePredicateCS());
    }
    throw new IllegalStateException("Unexpected navigation step: " + ctx.getText());
  }

  /**
   * Evaluates property access on metaclass instances: attributes and references, with the values of
   * many-valued features flattened into the result collection.
   *
   * @param navCtx The property navigation node - the type is looked up under this node because
   *     {@link tools.vitruv.dsls.vitruvocl.typechecker.TypeCheckVisitor#visitPropertyNav} stores
   *     the combined Ctype keyed by the navigation node, not by the inner {@code propertyAccess()}
   *     node (looking it up under the inner node always misses and silently falls back to {@code
   *     Type.set(Type.ANY)}, which is always {@code unique=true} - this used to make {@code
   *     LazyOperations#dedupe} run unconditionally on every {@code e.p} navigation regardless of
   *     the feature's real {@code unique} flag, silently dropping genuine duplicates from any
   *     many-valued, non-unique feature's result).
   * @param receiver The receiver value (must contain metaclass instances)
   * @return Collection of property values from all receiver instances
   */
  @SuppressWarnings("java:S3776")
  private Value property(VitruvOCLParser.PropertyNavContext navCtx, Value receiver) {
    VitruvOCLParser.PropertyAccessContext ctx = navCtx.propertyAccess();
    String propertyName = ctx.propertyName.getText();
    Type resultType = context.nodeType(navCtx);
    Type finalType = resultType != null ? resultType : Type.set(Type.ANY);
    Type emptyType = resultType != null ? resultType : Type.optional(Type.ANY);

    // Peek (without materializing the rest) to validate that the receiver contains metaclass
    // instances at all; a throwaway single-pull iterator keeps this check lazy.
    Iterator<OCLElement> probe = receiver.elementIterator();
    if (!probe.hasNext()) {
      return Value.empty(emptyType);
    }
    OCLElement first = probe.next();
    EObject firstInstance = first.tryGetInstance();
    if (firstInstance == null) {
      return Value.empty(emptyType);
    }

    // A single object navigated along a single-valued feature (self.name, i.flag) yields at most
    // one value - there is no collection to evaluate lazily (Tisi et al. restrict laziness to
    // collections), so the value is read directly instead of building a lazy chain for it.
    // Many-valued features stay lazy below, so early termination on them is preserved.
    if (receiver.isMaterialized() && receiver.size() == 1) {
      EStructuralFeature feature = firstInstance.eClass().getEStructuralFeature(propertyName);
      if (feature != null && !feature.isMany()) {
        List<OCLElement> values = new ArrayList<>(1);
        navigateOne(first, propertyName).forEachRemaining(values::add);
        return Value.of(values, finalType);
      }
    }

    // Category 1 (fully lazy): each receiver element expands to zero or more navigated values;
    // navigation never needs to consume more of the receiver than the outer consumer asks for.
    Function<OCLElement, Iterator<OCLElement>> expand = elem -> navigateOne(elem, propertyName);
    OCLElementSource mapped = LazyOperations.flatMap(receiver.asSource(), expand);
    // A unique result type (Set/OrderedSet) is only guaranteed duplicate-free once explicitly
    // deduplicated — collecting across multiple receiver elements can otherwise yield the same
    // feature value more than once even though the combined Ctype (see
    // TypeCheckVisitor#typeCheckPropertyAccess) says "unique". Deduplicated incrementally (Kern-
    // anforderung 2) instead of via a post-hoc removeDuplicates() pass over a fully built list.
    OCLElementSource result =
        finalType.isUnique() ? LazyOperations.dedupeSemantically(mapped) : mapped;
    return Value.lazy(result, finalType);
  }

  /** Navigates {@code propertyName} on a single receiver element; used by {@link #property}. */
  private static Iterator<OCLElement> navigateOne(OCLElement elem, String propertyName) {
    EObject instance = elem.tryGetInstance();
    if (instance == null) {
      return Collections.emptyIterator();
    }
    EStructuralFeature feature = instance.eClass().getEStructuralFeature(propertyName);
    if (feature == null) {
      return Collections.emptyIterator();
    }
    Object value = instance.eGet(feature);
    if (feature.isMany()) {
      // Category 1 (fully lazy): wraps each raw element on demand as the outer consumer (select,
      // exists, notEmpty, ...) actually pulls, instead of eagerly copying the entire feature
      // value into a new ArrayList up front (which used to defeat early termination for any
      // consumer chained onto a single receiver element's many-valued feature - see
      // NavigationSelectNotEmptyStreamingTest). `list.iterator()` is whatever EList
      // implementation EMF backs the feature with, which already transparently resolves proxies
      // on iteration for EReference features exactly as it did before - this only changes *when*
      // elements are pulled from it, not *how* each one is obtained or wrapped.
      List<?> list = (List<?>) value;
      return new SkipNullMappingIterator(list.iterator());
    }
    if (value == null) {
      return Collections.emptyIterator(); // unset optional attribute → ?T? = []
    }
    return List.of(wrap(value)).iterator();
  }

  /**
   * Wraps a raw Java value from EMF's {@code eGet()} into an {@link OCLElement}.
   *
   * <p>Type mapping:
   *
   * <ul>
   *   <li>{@link String} → {@link OCLElement.StringValue}
   *   <li>{@link Integer} → {@link OCLElement.IntValue}
   *   <li>{@link Boolean} → {@link OCLElement.BoolValue}
   *   <li>{@link Float} → {@link OCLElement.FloatValue} (EMF {@code EFloat} attributes)
   *   <li>{@link Double} → {@link OCLElement.DoubleValue}
   *   <li>{@link EEnumLiteral} → {@link OCLElement.EnumValue}
   *   <li>{@link EObject} → {@link OCLElement.MetaclassValue}
   *   <li>anything else → its {@code toString()} as {@link OCLElement.StringValue}, {@code null} →
   *       the empty string
   * </ul>
   *
   * @param value the raw Java value returned by {@code EObject.eGet(feature)}
   * @return the corresponding {@link OCLElement}
   */
  static OCLElement wrap(Object value) {
    if (value == null) {
      return new OCLElement.StringValue("");
    }

    Class<?> clazz = value.getClass();

    if (clazz.equals(String.class)) {
      return new OCLElement.StringValue((String) value);
    }
    if (clazz.equals(Integer.class)) {
      return new OCLElement.IntValue((Integer) value);
    }
    if (clazz.equals(Boolean.class)) {
      return new OCLElement.BoolValue((Boolean) value);
    }
    // EFloat is stored as Java Float — wrap as FloatValue, NOT DoubleValue
    if (clazz.equals(Float.class)) {
      return new OCLElement.FloatValue((Float) value);
    }
    if (clazz.equals(Double.class)) {
      return new OCLElement.DoubleValue((Double) value);
    }
    // EMF enum literals (EAttribute whose eType is an EEnum)
    if (value instanceof EEnumLiteral enumLit) {
      return new OCLElement.EnumValue(enumLit);
    }
    // Any other EObject (class instance from a metamodel)
    if (EObject.class.isAssignableFrom(clazz)) {
      return new OCLElement.MetaclassValue((EObject) value);
    }

    return new OCLElement.StringValue(value.toString());
  }

  /**
   * Lazily maps a raw {@code Iterator<?>} (a many-valued EMF feature's backing list iterator) to
   * {@link OCLElement}s via {@link #wrap}, skipping {@code null} entries one at a time instead of
   * pre-filtering the whole source. Mirrors {@code
   * tools.vitruv.dsls.vitruvocl.evaluator.value.LazyOperations}'s {@code FilterIterator} lookahead
   * pattern: {@code hasNext()} is answered from an already-computed pending element, so
   * constructing/advancing this iterator pulls at most one raw element ahead of what has actually
   * been yielded downstream.
   */
  private static final class SkipNullMappingIterator implements Iterator<OCLElement> {
    private final Iterator<?> upstream;
    private OCLElement pending;
    private boolean hasPending;

    SkipNullMappingIterator(Iterator<?> upstream) {
      this.upstream = upstream;
      advance();
    }

    private void advance() {
      hasPending = false;
      while (upstream.hasNext()) {
        Object item = upstream.next();
        if (item != null) {
          pending = wrap(item);
          hasPending = true;
          return;
        }
      }
    }

    @Override
    public boolean hasNext() {
      return hasPending;
    }

    @Override
    public OCLElement next() {
      if (!hasPending) {
        throw new NoSuchElementException();
      }
      OCLElement result = pending;
      advance();
      return result;
    }
  }
}
