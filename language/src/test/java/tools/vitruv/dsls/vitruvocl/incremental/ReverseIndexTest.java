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
package tools.vitruv.dsls.vitruvocl.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.junit.jupiter.api.Test;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.dsls.vitruvocl.incremental.delta.EChangeClassifier;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;

/** Maintenance of the {@link ReverseIndex} from transaction events. */
class ReverseIndexTest {

  @Test
  @SuppressWarnings("unchecked")
  void unclassifiableChangeRebuildsTheIndex() {
    EdgeFixture fx = EdgeFixture.create();
    ReverseIndex index = new ReverseIndex(fx.model().wrapper()::getAllRootObjects);
    EObject a1 = fx.find("a1");
    EObject b2 = fx.find("b2");
    EReference refs = (EReference) EdgeFixture.feature(b2, "refs");
    assertEquals(List.of(fx.find("b1")), index.holders(refs, a1)); // builds the index

    // a change the classifier cannot interpret, e.g. one that added b2 -> a1
    EdgeFixture.list(b2, "refs").add(a1);
    EChange<EObject> unknown = mock(EChange.class);
    index.apply(EChangeClassifier.classify(List.of(unknown)));

    assertTrue(index.holders(refs, a1).contains(b2), "b2 must be found as a holder of a1");
  }

  @Test
  void declaredReferencesAreIndexedInOneWalk() {
    EdgeFixture fx = EdgeFixture.create();
    AtomicInteger walks = new AtomicInteger();
    ReverseIndex index =
        new ReverseIndex(
            () -> {
              walks.incrementAndGet();
              return fx.model().wrapper().getAllRootObjects();
            });
    EObject a1 = fx.find("a1");
    EReference refs = (EReference) EdgeFixture.feature(a1, "refs");
    EReference next = (EReference) EdgeFixture.feature(a1, "next");
    index.declare(List.of(refs, next));

    assertEquals(List.of(fx.find("b1")), index.holders(refs, a1));
    assertEquals(List.of(a1), index.holders(next, fx.find("b1")));
    assertEquals(1, walks.get(), "both references must come from one model walk");
  }
}
