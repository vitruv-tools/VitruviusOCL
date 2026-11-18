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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.dsls.vitruvocl.compiler.VitruvOCLCompiler;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;

/**
 * {@code @pre} after a navigation that starts at a qualified metaclass (e.g. {@code
 * edge::Item.allInstances().value@pre}) reads the pre-state, like {@code @pre} after any other
 * navigation.
 */
class AtPreQualifiedNavigationTest {

  @Test
  void preStateOfAQualifiedNavigationIsRead() {
    EdgeFixture fx = EdgeFixture.create();
    // values 5, 3, 7, 9 sum to 24 before the transaction raises a1 from 5 to 9
    List<EChange<EObject>> transaction =
        fx.model()
            .record(
                () -> {
                  EObject a1 = fx.find("a1");
                  a1.eSet(EdgeFixture.feature(a1, "value"), 9);
                });
    VitruvOCLCompiler compiler =
        new VitruvOCLCompiler(fx.model().wrapper(), null, transaction);

    compiler.compile(
        "context edge::Root post sumBefore: edge::Item.allInstances().value@pre.sum() == 24");

    assertFalse(compiler.hasErrors(), () -> "errors: " + compiler.getErrors().getErrors());
    assertTrue(
        compiler.getLastEvaluator().getViolationRecords().isEmpty(),
        "the pre-state sum is 24, the post-state sum 28");
  }
}
