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

import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.incremental.support.LiveModel;

/**
 * Within one transaction, constraints re-evaluated over the same extension share one traversal of
 * the live VSUM instead of each reading the models again.
 */
class SessionTransactionTraversalTest {

  private static long modelReads(LiveModel live) {
    return Mockito.mockingDetails(live.vsum()).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("getViewSourceModels"))
        .count();
  }

  @Test
  void constraintsOfOneTransactionShareATraversal() {
    EdgeFixture fx = EdgeFixture.create();
    LiveModel live = fx.model();
    List<String> constraints =
        List.of(
            "context edge::Item inv a: self.value >= 0",
            "context edge::Item inv b: self.value < 1000",
            "context edge::Item inv c: self.value <> 42");
    try (IncrementalSession session =
        IncrementalSession.open(
            constraints, live.wrapper(), IncrementalConfig.of(IncrementalLevel.POLARITY))) {
      long before = modelReads(live);
      IncrementalResult result =
          session.onTransaction(
              live.record(
                  () -> {
                    EObject a1 = fx.find("a1");
                    a1.eSet(EdgeFixture.feature(a1, "value"), 9);
                  }));
      long reads = modelReads(live) - before;

      assertEquals(3, result.stats().constraintsEvaluated());
      assertTrue(reads <= 2, "the models were read " + reads + " times in one transaction");
    }
  }
}
