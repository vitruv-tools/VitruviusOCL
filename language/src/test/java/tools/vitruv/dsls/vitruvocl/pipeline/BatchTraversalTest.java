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

package tools.vitruv.dsls.vitruvocl.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.vitruv.dsls.vitruvocl.compiler.PreparedConstraint;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.incremental.support.LiveModel;
import tools.vitruv.dsls.vitruvocl.result.BatchValidationResult;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;

/**
 * A batch of constraints over different types reads the live VSUM's models a bounded number of
 * times: the types of all constraints are collected first and served by one traversal, instead of
 * one traversal per constraint (and per newly seen type).
 */
class BatchTraversalTest {

  @TempDir Path dir;

  @AfterEach
  void unregister() {
    VitruvOCL.clearVSUM();
  }

  private static long modelReads(LiveModel live) {
    return Mockito.mockingDetails(live.vsum()).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("getViewSourceModels"))
        .count();
  }

  @Test
  void batchOverSeveralTypesReadsTheModelsOnce() throws IOException {
    LiveModel live = EdgeFixture.create().model();
    VitruvOCL.registerDirectWrapper(live.wrapper());
    Path constraints = dir.resolve("c.ocl");
    Files.writeString(
        constraints,
        """
        context edge::SubA inv a: self.value >= 0
        context edge::SubB inv b: self.value >= 0
        context edge::Root inv r: self.items.notEmpty()
        context edge::Other inv o: self.amount >= 0
        context edge::Item inv i: edge::Other.allInstances().notEmpty()
        """);
    long before = modelReads(live);
    long preparationsBefore = PreparedConstraint.preparations();

    BatchValidationResult result = VitruvOCL.evaluateConstraints(constraints, 1);

    assertEquals(
        5,
        PreparedConstraint.preparations() - preparationsBefore,
        "each constraint is compiled once, for the prefetch and the evaluation together");

    assertEquals(5, result.getResults().size());
    assertTrue(result.getResults().stream().allMatch(ConstraintResult::isSuccess), "" + result);
    long reads = modelReads(live) - before;
    assertTrue(reads <= 3, "the models were read " + reads + " times for one batch");
  }
}
