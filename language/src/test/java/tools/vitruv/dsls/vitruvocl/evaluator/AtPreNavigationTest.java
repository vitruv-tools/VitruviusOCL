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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.dsls.vitruvocl.compiler.VitruvOCLCompiler;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;

/**
 * Characterization test for reading the pre-state with {@code @pre}: many-valued references and
 * attributes, receiver elements without the navigated feature, and features that were unset before
 * the transaction.
 *
 * <p>The transaction removes {@code a2} from {@code b1.refs}, adds the tags {@code t1, t1} to the
 * first root, raises {@code a1.extra} from 1 to 5 and sets {@code a2.next} (unset before) to {@code
 * b1}; {@code a1.next} and {@code b2.next} already point to {@code b1}. Each constraint is a {@code
 * post} block of {@code edge::Root}, evaluated for both roots.
 */
class AtPreNavigationTest {

  private EdgeFixture fx;
  private List<EChange<EObject>> transaction;

  @BeforeEach
  void recordTransaction() {
    fx = EdgeFixture.create();
    EObject b2 = fx.find("b2");
    b2.eSet(EdgeFixture.feature(b2, "next"), fx.find("b1"));
    transaction =
        fx.model()
            .record(
                () -> {
                  EObject a1 = fx.find("a1");
                  EObject a2 = fx.find("a2");
                  EObject b1 = fx.find("b1");
                  EdgeFixture.list(b1, "refs").remove(a2);
                  EdgeFixture.list(fx.root(1), "tags").addAll(List.of("t1", "t1"));
                  a1.eSet(EdgeFixture.feature(a1, "extra"), 5);
                  a2.eSet(EdgeFixture.feature(a2, "next"), b1);
                });
  }

  @ParameterizedTest(name = "{0} -> {1} violations")
  @CsvSource(
      delimiterString = "=>",
      value = {
        // many-valued reference: b1.refs was [a1, a2], is [a1]
        "edge::SubB.allInstances().refs@pre.size() == 2 => 0",
        "edge::SubB.allInstances().refs.size() == 1 => 0",
        "edge::SubB.allInstances().refs@pre.name.includes('a2') => 0",
        "edge::SubB.allInstances().refs@pre.size() == 1 => 2",
        // many-valued, non-unique attribute: the tags were empty
        "self.tags@pre.isEmpty() => 0",
        "self.tags.size() == 2 or self.tags.isEmpty() => 0",
        // receiver elements without the feature (SubB has no extra) are skipped
        "edge::Item.allInstances().extra@pre.sum() == 3 => 0",
        "edge::Item.allInstances().extra.sum() == 7 => 0",
        // unset single-valued reference: a2.next was unset before and is skipped
        "edge::Item.allInstances().next@pre.name.includes('b1') => 0",
        // the live navigation deduplicates its unique result, the pre-state one does not
        "edge::Item.allInstances().next.size() == 1 => 0",
        "edge::Item.allInstances().next@pre.size() == 2 => 0",
        "edge::Item.allInstances().next@pre.size() == 1 => 2"
      })
  void preStateIsRead(String constraint, int expectedViolations) {
    VitruvOCLCompiler compiler = new VitruvOCLCompiler(fx.model().wrapper(), null, transaction);

    compiler.compile("context edge::Root post: " + constraint.replace('\'', '"'));

    assertFalse(compiler.hasErrors(), () -> "errors: " + compiler.getErrors().getErrors());
    assertEquals(
        expectedViolations, compiler.getLastEvaluator().getViolationRecords().size(), constraint);
  }
}
