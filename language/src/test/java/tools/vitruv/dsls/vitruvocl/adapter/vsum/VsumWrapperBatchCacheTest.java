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

package tools.vitruv.dsls.vitruvocl.adapter.vsum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;

/**
 * A wrapper of the live VSUM traverses the models on every {@code allInstances()} request, since it
 * is not notified of changes. Within a batch, during which the models do not change, one
 * traversal serves all requests.
 */
class VsumWrapperBatchCacheTest {

  @Test
  void batchServesRepeatedRequestsFromOneTraversal() {
    EdgeFixture fx = EdgeFixture.create();
    VsumWrapper wrapper = fx.model().wrapper();
    EClass item = fx.find("a1").eClass().getESuperTypes().get(0);

    List<List<EObject>> results =
        wrapper.batch(view -> List.of(view.getAllInstances(item), view.getAllInstances(item)));

    assertSame(results.get(0), results.get(1), "the second request must reuse the first result");
    assertEquals(4, results.get(0).size());
  }

  @Test
  void outsideABatchChangesAreSeen() {
    EdgeFixture fx = EdgeFixture.create();
    VsumWrapper wrapper = fx.model().wrapper();
    EClass item = fx.find("a1").eClass().getESuperTypes().get(0);
    List<EObject> before = wrapper.getAllInstances(item);

    EdgeFixture.list(fx.root(1), "items").add(EdgeFixture.item("SubA", "a3", 1));

    List<EObject> after = wrapper.getAllInstances(item);
    assertNotSame(before, after);
    assertEquals(before.size() + 1, after.size());
  }

  @Test
  void callersOutsideTheBatchSeeChangesWhileItRuns() {
    EdgeFixture fx = EdgeFixture.create();
    VsumWrapper wrapper = fx.model().wrapper();
    EClass item = fx.find("a1").eClass().getESuperTypes().get(0);

    wrapper.batch(
        view -> {
          int cached = view.getAllInstances(item).size();
          // e.g. a VSUM propagation on another thread while a long batch runs
          EdgeFixture.list(fx.root(1), "items").add(EdgeFixture.item("SubA", "a3", 1));
          assertEquals(cached + 1, wrapper.getAllInstances(item).size());
          return null;
        });
  }
}
