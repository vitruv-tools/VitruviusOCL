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

package tools.vitruv.dsls.vitruvocl.architecture;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

class CyclePathViolationMatcherTest {

  private static final String COMMON_PIPELINE_HEADER =
      "Cycle detected: common -> \n                pipeline -> \n                common\n";

  private final CyclePathViolationMatcher matcher = new CyclePathViolationMatcher();

  @Test
  void sameCycleWithDifferentDependencySamplesMatches() {
    String stored =
        COMMON_PIPELINE_HEADER
            + "  1. Dependencies of common\n"
            + "    - Field <a.B.x> has type <c.D> in (B.java:0)\n";
    String actual =
        COMMON_PIPELINE_HEADER
            + "  1. Dependencies of common\n"
            + "    - Method <a.B.y()> calls method <c.D.z()> in (B.java:42)\n";

    assertThat(matcher.matches(stored, actual)).isTrue();
  }

  @Test
  void differentCyclePathsDoNotMatch() {
    String stored = COMMON_PIPELINE_HEADER + "  1. Dependencies of common\n";
    String actual =
        "Cycle detected: common -> \n                evaluator -> \n                common\n"
            + "  1. Dependencies of common\n";

    assertThat(matcher.matches(stored, actual)).isFalse();
  }

  @Test
  void longerCycleThroughSamePackagesDoesNotMatch() {
    String stored = COMMON_PIPELINE_HEADER + "  1. Dependencies of common\n";
    String actual =
        "Cycle detected: common -> \n                pipeline -> \n                evaluator -> \n"
            + "                common\n  1. Dependencies of common\n";

    assertThat(matcher.matches(stored, actual)).isFalse();
  }

  @Test
  void headerWithoutDetailsMatchesHeaderWithDetails() {
    assertThat(
            matcher.matches(
                COMMON_PIPELINE_HEADER, COMMON_PIPELINE_HEADER + "  1. Dependencies of common\n"))
        .isTrue();
  }

  @Test
  void nonCycleViolationsAreComparedVerbatim() {
    assertThat(matcher.matches("Class <a.B> violates x", "Class <a.B> violates x")).isTrue();
    assertThat(matcher.matches("Class <a.B> violates x", "Class <a.C> violates x")).isFalse();
  }

  @Test
  void nonCycleViolationsIgnoreLineNumbers() {
    assertThat(
            matcher.matches(
                "Method <a.B.m()> calls method <c.D.n()> in (B.java:12)",
                "Method <a.B.m()> calls method <c.D.n()> in (B.java:57)"))
        .isTrue();
  }

  @Test
  void nonCycleViolationsIgnoreLambdaAndAnonymousClassNumbering() {
    assertThat(
            matcher.matches(
                "Method <a.B.lambda$m$3(x.Y)> calls <a.B$1.run()> in (B.java:12)",
                "Method <a.B.lambda$m$7(x.Y)> calls <a.B$2.run()> in (B.java:20)"))
        .isTrue();
  }

  @Test
  void nonCycleViolationsStillDistinguishMembers() {
    assertThat(
            matcher.matches(
                "Method <a.B.m()> calls method <c.D.n()> in (B.java:12)",
                "Method <a.B.m()> calls method <c.D.other()> in (B.java:12)"))
        .isFalse();
  }

  @Test
  void cycleIsNeverMatchedByNonCycleViolation() {
    assertThat(matcher.matches(COMMON_PIPELINE_HEADER, "Class <a.B> violates x")).isFalse();
  }

  @Test
  void cyclePathCollapsesLineContinuations() {
    assertThat(CyclePathViolationMatcher.cyclePath(COMMON_PIPELINE_HEADER))
        .isEqualTo("Cycle detected: common -> pipeline -> common");
  }

  /** Guards against format changes in ArchUnit upgrades, which the hand-written cases miss. */
  @Test
  void realArchUnitReportIsReducedToItsCyclePath() {
    String fixturePackage = "tools.vitruv.dsls.vitruvocl.architecture.fixture";
    JavaClasses fixture = new ClassFileImporter().importPackages(fixturePackage);
    List<String> details =
        slices()
            .matching(fixturePackage + ".(*)..")
            .should()
            .beFreeOfCycles()
            .evaluate(fixture)
            .getFailureReport()
            .getDetails();

    assertThat(details).hasSize(1);
    String report = details.get(0);
    assertThat(report).contains("Dependencies of");
    assertThat(CyclePathViolationMatcher.cyclePath(report))
        .isEqualTo("Cycle detected: Slice alpha -> Slice beta -> Slice alpha");
    String otherSample = report.replace("Alpha.beta", "Alpha.renamedField");
    assertThat(otherSample).isNotEqualTo(report);
    assertThat(matcher.matches(report, otherSample)).isTrue();
  }
}
