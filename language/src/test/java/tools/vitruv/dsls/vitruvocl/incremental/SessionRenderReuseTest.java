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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;

/**
 * A transaction re-renders only the results it can change: those of re-evaluated constraints and
 * those whose violations describe an object the transaction touched (labels show its current
 * attribute values). Other results are reused unchanged.
 */
class SessionRenderReuseTest {

  private static final String NEGATIVE = "context edge::Item inv negative: self.value < 0";

  private static void set(EdgeFixture fx, String key, String feature, Object value) {
    EObject o = fx.find(key);
    o.eSet(EdgeFixture.feature(o, feature), value);
  }

  private static ConstraintResult first(IncrementalResult result) {
    return result.result().getResults().get(0);
  }

  @Test
  void unaffectedResultIsReused() {
    EdgeFixture fx = EdgeFixture.create();
    try (IncrementalSession session =
        IncrementalSession.open(
            List.of(NEGATIVE),
            fx.model().wrapper(),
            IncrementalConfig.of(IncrementalLevel.RELEVANT_INSTANCES))) {
      ConstraintResult before = first(session.latestResult());

      IncrementalResult after =
          session.onTransaction(fx.model().record(() -> set(fx, "x", "label", "renamed")));

      assertSame(before, first(after), "an untouched result must not be rendered again");
    }
  }

  @Test
  void labelOfATouchedViolatingObjectIsUpdated() {
    EdgeFixture fx = EdgeFixture.create();
    try (IncrementalSession session =
        IncrementalSession.open(
            List.of(NEGATIVE),
            fx.model().wrapper(),
            IncrementalConfig.of(IncrementalLevel.RELEVANT_INSTANCES))) {
      // the constraint does not read 'name', so it is not re-evaluated
      IncrementalResult after =
          session.onTransaction(fx.model().record(() -> set(fx, "a1", "name", "renamedItem")));

      assertTrue(
          first(after).toString().contains("renamedItem")
              || String.valueOf(first(after).getViolations()).contains("renamedItem"),
          "the violation of a1 must show its new name: " + first(after).getViolations());
    }
  }
}
