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

package tools.vitruv.dsls.vitruvocl.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.common.CompileError;

/**
 * Characterization test for constraints that do not compile, through the three ways a constraint is
 * compiled: {@link VitruvOCLCompiler#compile(String)}, {@link PreparedConstraint} (prepare once,
 * evaluate) and the rendered {@code ConstraintResult} of an evaluation.
 *
 * <p>For every constraint in {@code compiler/compile-failures.txt} it records the compiler's
 * errors, whether the prepared constraint compiled, the errors of its evaluation and the compiler
 * errors of the rendered result.
 */
class CompileFailureTest {

  private static final String SNAPSHOT = "/compiler/compile-failures.txt";

  private static MetamodelWrapper metamodels;

  @BeforeAll
  static void loadMetamodels() throws IOException {
    metamodels = new MetamodelWrapper();
    metamodels.loadMetamodel(Path.of("src/test/resources/test-metamodels/spaceMission.ecore"));
  }

  static Stream<Arguments> snapshotCases() throws IOException {
    List<Arguments> cases = new ArrayList<>();
    String name = null;
    List<String> body = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    for (String line : snapshotLines()) {
      if (line.startsWith("# ")) {
        continue;
      }
      if (line.startsWith("### ")) {
        addCase(cases, name, body, expected);
        name = line.substring(4);
        body = new ArrayList<>();
        expected = new ArrayList<>();
      } else if (name != null && line.startsWith("=> ")) {
        expected.add(line.substring(3));
      } else if (name != null && !line.isEmpty()) {
        body.add(line);
      }
    }
    addCase(cases, name, body, expected);
    return cases.stream();
  }

  private static List<String> snapshotLines() throws IOException {
    try (InputStream in =
        Objects.requireNonNull(CompileFailureTest.class.getResourceAsStream(SNAPSHOT), SNAPSHOT)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  private static void addCase(
      List<Arguments> cases, String name, List<String> body, List<String> expected) {
    if (name != null) {
      cases.add(Arguments.of(name, String.join("\n", body), expected));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("snapshotCases")
  void compileFailuresMatchSnapshot(String name, String constraint, List<String> expected)
      throws IOException {
    List<String> actual = new ArrayList<>();

    VitruvOCLCompiler compiler = new VitruvOCLCompiler(metamodels, null);
    compiler.compile(constraint);
    actual.add("compiler hasErrors=" + compiler.hasErrors());
    compiler.getErrors().getErrors().forEach(e -> actual.add("compiler " + render(e)));

    PreparedConstraint prepared = PreparedConstraint.prepare(constraint, metamodels);
    actual.add("prepared compiled=" + prepared.isCompiled());
    EvaluationOutcome outcome = prepared.evaluate(metamodels, List.of(), false, null);
    actual.add("outcome failed=" + outcome.failed());
    outcome.errors().forEach(e -> actual.add("outcome " + render(e)));

    ConstraintEvaluations.render(constraint, metamodels, List.of(), outcome)
        .getCompilerErrors()
        .forEach(e -> actual.add("result " + render(e)));

    assertEquals(expected, actual);
  }

  private static String render(CompileError e) {
    return e.getLine()
        + ":"
        + e.getColumn()
        + "-"
        + e.getEndLine()
        + ":"
        + e.getEndColumn()
        + " "
        + e.getSeverity()
        + " "
        + e.getSource().replace("\n", "\\n")
        + " | "
        + e.getMessage().replace("\n", "\\n");
  }
}
