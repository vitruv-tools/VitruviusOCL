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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.api.VitruvOclEngine;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;

/**
 * Characterizes how the evaluator binds the variables of a classifier context: {@code self} is
 * rebound for every instance (also when a lazy value refers to it), and operation parameters are
 * bound to an empty value because no argument is ever supplied.
 */
class ContextVariableBindingTest {

  private static final Path CAD_ECORE = Path.of("src/test/resources/test-metamodels/cad.ecore");
  private static final Path CAD_INSTANCE = Path.of("brake_disc_and_caliper_plate.cad");
  private static final Path BRAKESYSTEM_ECORE =
      Path.of("src/test/resources/test-metamodels/brakesystem.ecore");
  private static final Path BRAKESYSTEM_INSTANCE = Path.of("brakesystem.brakesystem");

  @BeforeAll
  static void setupPaths() {
    MetamodelWrapper.setTestModelsPath(Path.of("src/test/resources/test-models"));
  }

  private static ConstraintResult evalCad(String constraint) {
    ConstraintResult result =
        VitruvOclEngine.forFiles(new Path[] {CAD_ECORE}, new Path[] {CAD_INSTANCE})
            .evaluate(constraint);
    assertThat(result.isSuccess()).as(result.toDetailedErrorString()).isTrue();
    return result;
  }

  private static ConstraintResult evalBrakeSystem(String constraint) {
    ConstraintResult result =
        VitruvOclEngine.forFiles(new Path[] {BRAKESYSTEM_ECORE}, new Path[] {BRAKESYSTEM_INSTANCE})
            .evaluate(constraint);
    assertThat(result.isSuccess()).as(result.toDetailedErrorString()).isTrue();
    return result;
  }

  @Test
  void selfIsReboundForEveryInstance() {
    ConstraintResult result =
        evalCad("context cad::Namespace inv: self.id == \"id3_front_brake_disc\"");

    assertOnlyCaliperViolates(result);
  }

  @Test
  void lazyValueReferringToSelfSeesTheCurrentInstance() {
    ConstraintResult result =
        evalCad(
            """
            context cad::Namespace inv:
              let same = cad::Namespace.allInstances().select(n | n.id == self.id) in
              same.first().id == "id3_front_brake_disc"
            """);

    assertOnlyCaliperViolates(result);
  }

  /** The lazy let value is consumed only partly, for every instance anew. */
  @Test
  void partlyConsumedLazyLetValueIsEvaluatedAnewForEveryInstance() {
    ConstraintResult result =
        evalCad(
            """
            context cad::Namespace inv:
              let same = cad::Namespace.allInstances().select(n | n.id == self.id) in
              same.exists(n | n.id == "id3_front_brake_disc")
            """);

    assertOnlyCaliperViolates(result);
  }

  /**
   * The evaluator binds operation parameters to an empty value. A parameter without any binding
   * also reads as empty, so this only pins down that the parameter resolves and evaluates.
   */
  @Test
  void operationParameterEvaluatesToAnEmptyValue() {
    ConstraintResult result =
        evalBrakeSystem(
            "context brakesystem::BrakeDisk::inspect(thresholds: Set(Integer)) inv:"
                + " thresholds.isEmpty() and thresholds.size() == 0");

    assertThat(result.isSatisfied()).isTrue();
  }

  private static void assertOnlyCaliperViolates(ConstraintResult result) {
    assertThat(result.getViolations()).hasSize(1);
    EObject violating = result.getViolations().get(0).instance();
    assertThat(violating.eGet(violating.eClass().getEStructuralFeature("id")))
        .isEqualTo("id3_front_left_caliper");
  }
}
