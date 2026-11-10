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

package tools.vitruv.dsls.vitruvocl.evaluator.lazy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.evaluator.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.Value;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;

/**
 * A lazy {@link Value} follows call-by-need (Tisi et al., "Lazy Evaluation for OCL", 2015): no
 * element is computed before it is needed, and none is computed more than once, however many
 * consumers traverse the value — fully, partially, or interleaved.
 */
class CallByNeedValueTest {

  private static final int SIZE = 10;

  private final AtomicInteger predicateCalls = new AtomicInteger();

  private Value lazyEvens() {
    List<OCLElement> base = new ArrayList<>();
    for (int i = 0; i < SIZE; i++) {
      base.add(new OCLElement.IntValue(i));
    }
    OCLElementSource evens =
        LazyOperations.filter(
            OCLElementSource.of(base),
            e -> {
              predicateCalls.incrementAndGet();
              return ((OCLElement.IntValue) e).value() % 2 == 0;
            });
    return Value.lazy(evens, Type.bag(Type.INTEGER));
  }

  private static List<OCLElement> drain(Iterator<OCLElement> it) {
    List<OCLElement> out = new ArrayList<>();
    it.forEachRemaining(out::add);
    return out;
  }

  @Test
  void twoFullTraversals_evaluateEachElementOnce() {
    Value value = lazyEvens();

    List<OCLElement> first = drain(value.elementIterator());
    List<OCLElement> second = drain(value.elementIterator());

    assertEquals(5, first.size());
    assertEquals(first, second);
    assertEquals(SIZE, predicateCalls.get());
  }

  @Test
  void partialTraversalThenFullTraversal_continuesInsteadOfRestarting() {
    Value value = lazyEvens();

    Iterator<OCLElement> partial = value.elementIterator();
    assertEquals(new OCLElement.IntValue(0), partial.next());
    // The filter itself looks ahead to its next match (element 2), so 3 elements are tested.
    assertEquals(3, predicateCalls.get());

    assertEquals(5, value.getElements().size());
    assertEquals(SIZE, predicateCalls.get());
  }

  @Test
  void interleavedTraversals_shareTheComputedElements() {
    Value value = lazyEvens();
    Iterator<OCLElement> a = value.elementIterator();
    Iterator<OCLElement> b = value.elementIterator();

    List<OCLElement> fromA = new ArrayList<>();
    List<OCLElement> fromB = new ArrayList<>();
    while (a.hasNext() || b.hasNext()) {
      if (a.hasNext()) {
        fromA.add(a.next());
      }
      if (b.hasNext()) {
        fromB.add(b.next());
      }
    }

    assertEquals(fromA, fromB);
    assertEquals(5, fromA.size());
    assertEquals(SIZE, predicateCalls.get());
  }

  @Test
  void isEmptyAndIncludes_reuseAlreadyComputedElements() {
    Value value = lazyEvens();

    assertFalse(value.isEmpty());
    assertTrue(value.includes(new OCLElement.IntValue(4)));
    int callsSoFar = predicateCalls.get();
    assertTrue(value.includes(new OCLElement.IntValue(2)));

    assertEquals(callsSoFar, predicateCalls.get());
  }

  @Test
  void failedTraversal_isRethrownInsteadOfYieldingATruncatedCollection() {
    List<OCLElement> base = List.of(new OCLElement.IntValue(1), new OCLElement.IntValue(2));
    Value failing =
        Value.lazy(
            LazyOperations.filter(
                OCLElementSource.of(base),
                e -> {
                  if (((OCLElement.IntValue) e).value() == 2) {
                    throw new IllegalArgumentException("boom");
                  }
                  return true;
                }),
            Type.bag(Type.INTEGER));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, failing::getElements);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, failing::getElements);
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, failing::size);
  }

  @Test
  void downstreamChains_shareTheUpstreamComputation() {
    Value value = lazyEvens();
    Value doubledA =
        Value.lazy(
            LazyOperations.filter(value.asSource(), e -> true), Type.bag(Type.INTEGER));
    Value doubledB =
        Value.lazy(
            LazyOperations.filter(value.asSource(), e -> true), Type.bag(Type.INTEGER));

    assertEquals(doubledA.getElements(), doubledB.getElements());
    assertEquals(SIZE, predicateCalls.get());
  }
}
