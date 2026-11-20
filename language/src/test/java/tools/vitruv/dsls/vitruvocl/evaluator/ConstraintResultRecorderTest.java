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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.compiler.VitruvOCLCompiler;
import tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor.SkippedConstraint;
import tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor.ViolationRecord;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;

/**
 * What the evaluator records per evaluated constraint and instance: violating instances, violation
 * records with severity and interpolated {@code @message}, and the {@code pre}/{@code post} blocks
 * it skipped for lack of a transaction. The edge fixture has the {@code SubA} instances {@code a1}
 * (value 5) and {@code a2} (value 7).
 */
class ConstraintResultRecorderTest {

  private final EdgeFixture fixture = EdgeFixture.create();

  private EvaluationVisitor evaluate(VitruvOCLCompiler compiler, String constraint) {
    compiler.compile(constraint);
    assertFalse(
        compiler.hasErrors(),
        () ->
            "errors: "
                + compiler.getErrors().getErrors().stream().map(CompileError::getMessage).toList());
    EvaluationVisitor evaluator = compiler.getLastEvaluator();
    assertNotNull(evaluator, "evaluated");
    return evaluator;
  }

  private EvaluationVisitor evaluate(String constraint) {
    return evaluate(new VitruvOCLCompiler(fixture.model().wrapper(), null), constraint);
  }

  private EvaluationVisitor evaluateWithTransaction(String constraint) {
    return evaluate(new VitruvOCLCompiler(fixture.model().wrapper(), null, List.of()), constraint);
  }

  @Test
  void violationWithoutAnnotationsHasDefaultSeverityAndNoMessage() {
    EvaluationVisitor evaluator = evaluate("context edge::SubA inv: self.value < 6");

    EObject a2 = fixture.find("a2");
    assertEquals(List.of(a2), evaluator.getViolatingInstances());
    assertEquals(
        List.of(new ViolationRecord("WARNING", null, a2)), evaluator.getViolationRecords());
  }

  @Test
  void violationsAreRecordedPerInstanceAndInvariantInEvaluationOrder() {
    EvaluationVisitor evaluator =
        evaluate(
            """
            context edge::SubA
              inv i1: @severity CRITICAL self.value < 0
              inv i2: @message "second" self.value > 6
            """);

    EObject a1 = fixture.find("a1");
    EObject a2 = fixture.find("a2");
    assertEquals(List.of(a1, a1, a2), evaluator.getViolatingInstances());
    assertEquals(
        List.of(
            new ViolationRecord("CRITICAL", null, a1),
            new ViolationRecord("WARNING", "second", a1),
            new ViolationRecord("CRITICAL", null, a2)),
        evaluator.getViolationRecords());
  }

  @Test
  void messageTemplateIsInterpolated() {
    EvaluationVisitor evaluator =
        evaluate(
            """
            context edge::SubA inv:
              @message "{self.name} has {self.value}, {self.missing}, {self}, {other.name}"
              self.value < 6
            """);

    EObject a2 = fixture.find("a2");
    String self = "SubA@" + Integer.toHexString(a2.hashCode());
    assertEquals(
        "\"a2\" has 7, {missing}, " + self + ", {other.name}",
        evaluator.getViolationRecords().get(0).customMessage());
  }

  @Test
  void preAndPostBlocksAreSkippedOncePerContextWithoutTransaction() {
    EvaluationVisitor evaluator =
        evaluate(
            """
            context edge::SubA
              inv: true
              pre p1: self.value < 0
              post: self.value < 0
            """);

    assertEquals(
        List.of(
            new SkippedConstraint("pre", "p1", "edge::SubA"),
            new SkippedConstraint("post", null, "edge::SubA")),
        evaluator.getSkippedConstraints());
    assertEquals(List.of(), evaluator.getViolationRecords());
  }

  @Test
  void contextWithoutPreAndPostSkipsNothing() {
    assertEquals(
        List.of(), evaluate("context edge::SubA inv: self.value < 6").getSkippedConstraints());
  }

  @Test
  void preAndPostBlocksAreEvaluatedWithTransaction() {
    EvaluationVisitor evaluator =
        evaluateWithTransaction(
            """
            context edge::SubA
              inv: self.value < 6
              pre: @severity INFO self.value > 6
              post: @severity MAJOR true
            """);

    EObject a1 = fixture.find("a1");
    EObject a2 = fixture.find("a2");
    assertEquals(List.of(), evaluator.getSkippedConstraints());
    assertEquals(
        List.of(new ViolationRecord("INFO", null, a1), new ViolationRecord("WARNING", null, a2)),
        evaluator.getViolationRecords());
  }
}
