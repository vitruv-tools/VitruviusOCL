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

package tools.vitruv.dsls.vitruvocl.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.adapter.emf.SmartLoader;
import tools.vitruv.dsls.vitruvocl.compiler.ConstraintEvaluations;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalConfig;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalLevel;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalSession;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.incremental.support.LiveModel;
import tools.vitruv.dsls.vitruvocl.result.BatchValidationResult;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;
import tools.vitruv.dsls.vitruvocl.result.Warning;

/**
 * The instance API. Before the static facade was switched to delegate to it, the engine was checked
 * to return the same results as the facade's own implementation; these tests pin the observable
 * outcomes instead, so that they outlive the facade.
 */
class VitruvOclEngineTest {

  private static final Path SPACE_MISSION_ECORE =
      Path.of("src/test/resources/test-metamodels/spaceMission.ecore");
  private static final Path[] ECORE = {SPACE_MISSION_ECORE};
  private static final Path[] XMI = {
    Path.of("spacecraft-atlas.spacemission"), Path.of("spacecraft-heavy.spacemission")
  };
  private static final Path[] MISSING_XMI = {Path.of("does-not-exist.spacemission")};
  private static final Path PROJECT = Path.of("src/test/resources/test-project");

  private static final String SATISFIED = "context spaceMission::Spacecraft inv: self.mass >= 0";
  private static final String VIOLATED = "context spaceMission::Spacecraft inv: self.mass < 0";
  private static final String COMPILE_ERROR =
      "context spaceMission::Spacecraft inv: self.noSuchFeature > 0";
  private static final String PRE_BLOCK =
      "context spaceMission::Spacecraft pre massPositive: self.mass >= 0";
  private static final String EDGE_CONSTRAINT = "context edge::Item inv: self.value >= 0";

  private static final IncrementalConfig FULL = IncrementalConfig.of(IncrementalLevel.FULL);

  @TempDir Path dir;

  @BeforeAll
  static void setupPaths() {
    MetamodelWrapper.setTestModelsPath(Path.of("src/test/resources/test-models"));
  }

  private static VitruvOclEngine files() {
    return VitruvOclEngine.forFiles(ECORE, XMI);
  }

  // ------------------------------------------------------------------ single constraints

  @Test
  void evaluatesSatisfiedViolatedAndInvalidConstraints() {
    ConstraintResult satisfied = files().evaluate(SATISFIED);
    ConstraintResult violated = files().evaluate(VIOLATED);
    ConstraintResult invalid = files().evaluate(COMPILE_ERROR);

    assertThat(satisfied.isSuccess()).isTrue();
    assertThat(satisfied.isSatisfied()).isTrue();
    assertThat(violated.isSuccess()).isTrue();
    assertThat(violated.getViolations())
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.instance().eClass().getName()).isEqualTo("Spacecraft"));
    assertThat(invalid.isSuccess()).isFalse();
    assertThat(invalid.getCompilerErrors()).isNotEmpty();
  }

  @Test
  void skipsPrePostBlocksWithoutATransaction() {
    assertThat(warningMessages(files().evaluate(PRE_BLOCK))).anyMatch(SatisfiesSkip::isSkip);
    assertThat(warningMessages(files().evaluate(PRE_BLOCK, List.of())))
        .noneMatch(SatisfiesSkip::isSkip);
  }

  @Test
  void reportsLoadErrorsInTheResult() {
    ConstraintResult result = VitruvOclEngine.forFiles(ECORE, MISSING_XMI).evaluate(SATISFIED);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getFileErrors()).isNotEmpty();
  }

  @Test
  void evaluatesAgainstAGivenModel() {
    SmartLoader.LoadResult load = SmartLoader.loadForConstraint(VIOLATED, ECORE, XMI);

    assertThat(describe(VitruvOclEngine.forModel(load.wrapper).evaluate(VIOLATED)))
        .isEqualTo(describe(files().evaluate(VIOLATED)));
  }

  @Test
  void evaluatesAgainstAVirtualModel() {
    LiveModel live = EdgeFixture.create().model();

    ConstraintResult result = VitruvOclEngine.forVsum(live.vsum()).evaluate(EDGE_CONSTRAINT);

    assertThat(result.isSuccess()).as(result.toDetailedErrorString()).isTrue();
  }

  // ------------------------------------------------------------------ constraint lists

  @Test
  void evaluateAllKeepsOrderAndReportsDuplicates() {
    List<String> constraints =
        List.of(VIOLATED, SATISFIED, COMPILE_ERROR, SATISFIED, PRE_BLOCK, VIOLATED);

    List<ConstraintResult> results =
        files().withThreadPoolSize(3).evaluateAll(constraints).getResults();

    assertThat(results).extracting(ConstraintResult::getConstraint).isEqualTo(constraints);
    for (int i : new int[] {0, 1, 2, 4}) {
      assertThat(describe(results.get(i)))
          .as("same as a single evaluation: %s", constraints.get(i))
          .isEqualTo(describe(files().evaluate(constraints.get(i))));
    }
    for (int i : new int[] {3, 5}) {
      assertThat(results.get(i).getWarnings())
          .extracting(Warning::getType)
          .containsExactly(Warning.WarningType.DUPLICATE_CONSTRAINT);
    }
  }

  @Test
  void evaluateAllWithATransactionEvaluatesPrePostBlocks() {
    List<String> constraints = List.of(SATISFIED, PRE_BLOCK);

    assertThat(warningMessages(files().evaluateAll(constraints).getResults().get(1)))
        .anyMatch(SatisfiesSkip::isSkip);
    assertThat(warningMessages(files().evaluateAll(constraints, List.of()).getResults().get(1)))
        .noneMatch(SatisfiesSkip::isSkip);
  }

  @Test
  void evaluateAllReportsLoadErrorsForEveryConstraint() {
    List<ConstraintResult> results =
        VitruvOclEngine.forFiles(ECORE, MISSING_XMI)
            .evaluateAll(List.of(SATISFIED, VIOLATED))
            .getResults();

    assertThat(results).hasSize(2).allSatisfy(r -> assertThat(r.getFileErrors()).isNotEmpty());
  }

  @Test
  void evaluateAllOfNothingIsEmpty() {
    assertThat(files().evaluateAll(List.of()).getResults()).isEmpty();
  }

  @Test
  void evaluateFileEvaluatesEveryBlockAsAUnit() throws IOException {
    Path oclFile = writeConstraintsFile();

    BatchValidationResult result = files().evaluateFile(oclFile);
    BatchValidationResult withTransaction = files().evaluateFile(oclFile, List.of());

    assertThat(result.getResults()).hasSize(3);
    assertThat(result.getResults().get(0).isSatisfied()).isTrue();
    assertThat(result.getResults().get(1).getViolations()).isNotEmpty();
    assertThat(warningMessages(result.getResults().get(2))).anyMatch(SatisfiesSkip::isSkip);
    assertThat(warningMessages(withTransaction.getResults().get(2)))
        .noneMatch(SatisfiesSkip::isSkip);
  }

  @Test
  void missingConstraintsFileThrows() {
    assertThatThrownBy(() -> files().evaluateFile(dir.resolve("missing.ocl")))
        .isInstanceOf(IOException.class);
  }

  @Test
  void evaluatesAProject() throws IOException {
    Path constraints = ConstraintFiles.projectConstraints(PROJECT);

    BatchValidationResult result = VitruvOclEngine.forProject(PROJECT).evaluateFile(constraints);

    assertThat(result.getResults()).hasSize(ConstraintFiles.read(constraints).size());
    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.getSatisfiedConstraints()).isNotEmpty();
  }

  @Test
  void aProjectWithoutMetamodelsCannotBeEvaluated() throws IOException {
    ConstraintResult result = VitruvOclEngine.forProject(dir).evaluate(SATISFIED);

    assertThat(result.isSuccess()).isFalse();
  }

  // ------------------------------------------------------------------ engine properties

  @Test
  void withThreadPoolSizeReturnsANewEngineAndKeepsTheOriginal() {
    VitruvOclEngine engine = files();
    VitruvOclEngine single = engine.withThreadPoolSize(1);

    assertThat(single).isNotSameAs(engine);
    assertThat(describe(single.evaluateAll(List.of(SATISFIED, VIOLATED))))
        .isEqualTo(describe(engine.evaluateAll(List.of(SATISFIED, VIOLATED))));
  }

  @Test
  void threadPoolSizeMustBePositive() {
    assertThatThrownBy(() -> files().withThreadPoolSize(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void filePathArraysAreCopied() {
    Path[] ecore = ECORE.clone();
    Path[] xmi = XMI.clone();
    VitruvOclEngine engine = VitruvOclEngine.forFiles(ecore, xmi);
    ecore[0] = dir.resolve("missing.ecore");
    xmi[0] = MISSING_XMI[0];

    assertThat(engine.evaluate(SATISFIED).getFileErrors()).isEmpty();
  }

  @Test
  void rejectsNullArguments() {
    assertThatThrownBy(() -> VitruvOclEngine.forModel(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> VitruvOclEngine.forVsum(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> VitruvOclEngine.forFiles(null, XMI))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> VitruvOclEngine.forProject(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> files().evaluate(SATISFIED, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> files().evaluateAll(List.of(SATISFIED), null))
        .isInstanceOf(NullPointerException.class);
  }

  // ------------------------------------------------------------------ incremental evaluation

  @Test
  void openIncrementalSessionEvaluatesInitially() {
    try (IncrementalSession session =
        files().openIncrementalSession(List.of(SATISFIED, VIOLATED), FULL)) {
      assertThat(session.latestResult().result().getResults()).hasSize(2);
    }
  }

  @Test
  void openIncrementalSessionOverAProject() throws IOException {
    List<String> constraints = ConstraintFiles.read(ConstraintFiles.projectConstraints(PROJECT));

    try (IncrementalSession session =
        VitruvOclEngine.forProject(PROJECT).openIncrementalSession(constraints, FULL)) {
      assertThat(session.latestResult().result().getResults()).hasSize(constraints.size());
    }
  }

  @Test
  void openIncrementalSessionFailsOnLoadErrors() {
    VitruvOclEngine engine = VitruvOclEngine.forFiles(ECORE, MISSING_XMI);

    assertThatThrownBy(() -> engine.openIncrementalSession(List.of(SATISFIED), FULL))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void attachesToAModelThatReportsTransactions() {
    LiveModel live = EdgeFixture.create().model();
    VitruvOclEngine engine = VitruvOclEngine.forModel(live.wrapper());

    try (IncrementalSession session =
        engine.attachIncrementalSession(List.of(EDGE_CONSTRAINT), FULL, r -> {}, e -> {})) {
      assertThat(session.latestResult().result().getResults()).hasSize(1);
    }
  }

  @Test
  void attachingNeedsAModelThatReportsTransactions() {
    assertThatThrownBy(
            () -> files().attachIncrementalSession(List.of(SATISFIED), FULL, r -> {}, e -> {}))
        .isInstanceOf(IllegalStateException.class);
  }

  // ------------------------------------------------------------------ ConstraintFiles

  @Test
  void constraintFilesSplitsContextBlocksIntoUnits() throws IOException {
    Path oclFile =
        Files.writeString(
            dir.resolve("units.ocl"),
            """
            import spaceMission : 'spaceMission.ecore'
            -- comment
            context spaceMission::Spacecraft
              inv a: self.mass >= 0
              pre b: self.mass@pre >= 0
            context spaceMission::Mission inv c: true
            """);

    assertThat(ConstraintFiles.read(oclFile))
        .containsExactly(
            "context spaceMission::Spacecraft\ninv a: self.mass >= 0",
            "context spaceMission::Spacecraft\npre b: self.mass@pre >= 0",
            "context spaceMission::Mission\ninv c: true");
  }

  @Test
  void projectConstraintsFollowTheProjectLayout() {
    assertThat(ConstraintFiles.projectConstraints(PROJECT))
        .isEqualTo(PROJECT.resolve("model/src/main/constraints.ocl"));
  }

  // ------------------------------------------------------------------ helpers

  /** Whether a warning is the notice that a pre/post block was skipped. */
  private static final class SatisfiesSkip {
    static boolean isSkip(String message) {
      return message.startsWith(ConstraintEvaluations.PRE_POST_SKIPPED_PREFIX);
    }
  }

  private Path writeConstraintsFile() throws IOException {
    return Files.writeString(
        dir.resolve("constraints.ocl"),
        """
        -- a comment
        import spaceMission : 'spaceMission.ecore'
        context spaceMission::Spacecraft
          inv nonNegative: self.mass >= 0
          inv negative: self.mass < 0
          pre massPositive: self.mass >= 0
        """);
  }

  private static List<String> warningMessages(ConstraintResult result) {
    return result.getWarnings().stream().map(Warning::getMessage).toList();
  }

  private static String describe(BatchValidationResult result) {
    return result.getResults().stream()
        .map(VitruvOclEngineTest::describe)
        .collect(Collectors.joining("\n---\n"));
  }

  /** Everything a caller can observe, with object identities (hash codes) masked. */
  private static String describe(ConstraintResult result) {
    String text =
        String.join(
            "\n",
            "constraint=" + result.getConstraint(),
            "success=" + result.isSuccess(),
            "satisfied=" + result.isSatisfied(),
            "errors="
                + result.getCompilerErrors().stream()
                    .map(e -> e.getLine() + ":" + e.getColumn() + " " + e.getMessage())
                    .toList(),
            "fileErrors=" + result.getFileErrors(),
            "warnings=" + result.getWarnings(),
            "violations="
                + result.getViolations().stream()
                    .map(
                        v ->
                            v.severity()
                                + " "
                                + v.message()
                                + " "
                                + v.instance().eClass().getName())
                    .toList());
    return text.replaceAll("@[0-9a-f]{4,}", "@#");
  }
}
