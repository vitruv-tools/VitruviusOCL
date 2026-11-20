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

package tools.vitruv.dsls.vitruvocl.vitruv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.vitruv.dsls.vitruvocl.api.ConstraintFiles;
import tools.vitruv.dsls.vitruvocl.source.ConstraintUnit;
import tools.vitruv.dsls.vitruvocl.source.OclSourceSplitter;
import tools.vitruv.framework.vsum.constraints.ConstraintDiagnostic;
import tools.vitruv.framework.vsum.constraints.InvalidConstraintException;

/**
 * How {@code .ocl} files are split into constraints, side by side for the three users of {@link
 * OclSourceSplitter}: {@link ConstraintFiles#read} (CLI and engine), {@link
 * OclInvariantParser#parse} (Vitruv checker) and the splitter itself.
 *
 * <p>For every document in {@code source/constraint-splitting.txt} it records the units all three
 * produce (texts with {@code \n} and {@code \r} escaped) and the diagnostics the checker and the
 * splitter report, and it asserts that both users reproduce the splitter: the unit texts, and the
 * invariant sources, names, context types and syntax errors of the checker. Since both users are
 * built on the splitter, these assertions check the wiring; the snapshot pins the behaviour. Until
 * then it pinned the users' own implementations; a case tagged {@code [fix]} marks where the former
 * regex-based {@link ConstraintFiles#read} (or, for a byte order mark, the checker) behaved
 * differently.
 *
 * <p>Tags in a case name: {@code [crlf]} uses CRLF line breaks, {@code [cr]} CR line breaks, {@code
 * [noeol]} omits the final line break.
 */
class ConstraintSplittingTest {

  private static final String SNAPSHOT = "/source/constraint-splitting.txt";

  @TempDir Path tempDir;

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
      } else if (name != null) {
        body.add(line);
      }
    }
    addCase(cases, name, body, expected);
    return cases.stream();
  }

  private static List<String> snapshotLines() throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            ConstraintSplittingTest.class.getResourceAsStream(SNAPSHOT), SNAPSHOT)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  private static void addCase(
      List<Arguments> cases, String name, List<String> body, List<String> expected) {
    if (name == null) {
      return;
    }
    List<String> lines = new ArrayList<>(body);
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1); // the blank separator line before the next case
    }
    String lineBreak = name.contains("[crlf]") ? "\r\n" : name.contains("[cr]") ? "\r" : "\n";
    String content = String.join(lineBreak, lines) + (name.contains("[noeol]") ? "" : lineBreak);
    cases.add(Arguments.of(name, content, expected));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("snapshotCases")
  void splittingMatchesSnapshot(String name, String content, List<String> expected)
      throws IOException {
    Path file = tempDir.resolve("constraints.ocl");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    List<String> files = ConstraintFiles.read(file);
    List<OclInvariantParser.ParsedInvariant> invariants = List.of();
    List<ConstraintDiagnostic> checkerErrors = List.of();
    try {
      invariants = OclInvariantParser.parse(content);
    } catch (InvalidConstraintException invalid) {
      checkerErrors = invalid.getDiagnostics();
    }
    OclSourceSplitter.SplitResult split = OclSourceSplitter.split(content);
    assertEquals(split.units(), OclSourceSplitter.unitsOf(content), "unitsOf finds the same units");

    List<String> actual = new ArrayList<>();
    files.forEach(unit -> actual.add("files " + escape(unit)));
    invariants.forEach(inv -> actual.add(render(inv)));
    checkerErrors.forEach(
        d -> actual.add("checker error " + d.line() + ":" + d.column() + " " + d.message()));
    for (ConstraintUnit unit : split.units()) {
      actual.add(render(unit));
      if (unit.kind() == ConstraintUnit.Kind.INV) {
        actual.add("splitter source | " + escape(checkerSource(unit)));
      }
    }
    split
        .syntaxErrors()
        .forEach(
            d ->
                actual.add(
                    "splitter error "
                        + d.position().line()
                        + ":"
                        + d.position().column()
                        + " "
                        + d.message()));
    assertEquals(expected, actual);

    assertTextLinesMatch(split.units());
    assertEquals(files, split.units().stream().map(ConstraintUnit::text).toList(), "files");
    assertReproducesChecker(invariants, checkerErrors, split);
  }

  /** Every line of a unit's text has its document line, in document order. */
  private static void assertTextLinesMatch(List<ConstraintUnit> units) {
    for (ConstraintUnit unit : units) {
      assertEquals(unit.text().split("\n", -1).length, unit.textLines().size(), unit.text());
      for (int i = 1; i < unit.textLines().size(); i++) {
        assertTrue(unit.textLines().get(i - 1) <= unit.textLines().get(i), unit.text());
      }
    }
  }

  /**
   * The splitter yields the checker's invariants (source, name, context type) or its syntax errors;
   * an unknown severity is reported by the checker at the splitter's severity position.
   */
  private static void assertReproducesChecker(
      List<OclInvariantParser.ParsedInvariant> invariants,
      List<ConstraintDiagnostic> checkerErrors,
      OclSourceSplitter.SplitResult split) {
    List<ConstraintUnit> inv =
        split.units().stream().filter(u -> u.kind() == ConstraintUnit.Kind.INV).toList();
    if (!split.syntaxErrors().isEmpty()) {
      List<String> splitterErrors =
          split.syntaxErrors().stream()
              .map(d -> d.position().line() + ":" + d.position().column() + " " + d.message())
              .toList();
      List<String> checker =
          checkerErrors.stream().map(d -> d.line() + ":" + d.column() + " " + d.message()).toList();
      assertEquals(checker, splitterErrors, "syntax errors");
    } else if (!checkerErrors.isEmpty()) {
      ConstraintUnit unknown =
          inv.stream()
              .filter(u -> u.severity() != null && !isSeverity(u.severity()))
              .findFirst()
              .orElseThrow();
      assertEquals(unknown.severityPosition().line(), checkerErrors.get(0).line());
      assertEquals(unknown.severityPosition().column(), checkerErrors.get(0).column());
    } else {
      assertEquals(
          invariants.stream().map(OclInvariantParser.ParsedInvariant::source).toList(),
          inv.stream().map(ConstraintSplittingTest::checkerSource).toList(),
          "sources");
      assertEquals(
          invariants.stream().map(i -> i.name() + " " + i.contextType()).toList(),
          inv.stream().map(u -> u.name() + " " + u.contextType()).toList(),
          "names and context types");
    }
  }

  private static boolean isSeverity(String value) {
    try {
      tools.vitruv.framework.vsum.constraints.ConstraintSeverity.valueOf(value);
      return true;
    } catch (IllegalArgumentException unknown) {
      return false;
    }
  }

  /** The checker's form of an invariant source: header, line break, indented block. */
  private static String checkerSource(ConstraintUnit unit) {
    return unit.header() + "\n  " + unit.body();
  }

  private static String render(OclInvariantParser.ParsedInvariant inv) {
    return "checker name="
        + inv.name()
        + " context="
        + inv.contextType()
        + " severity="
        + inv.severity()
        + " | "
        + escape(inv.source());
  }

  private static String render(ConstraintUnit unit) {
    String severity =
        unit.severityPosition() == null
            ? String.valueOf(unit.severity())
            : unit.severity()
                + "@"
                + unit.severityPosition().line()
                + ":"
                + unit.severityPosition().column();
    return "splitter "
        + unit.kind()
        + " name="
        + unit.name()
        + " context="
        + unit.contextType()
        + " severity="
        + severity
        + " start="
        + unit.start().line()
        + ":"
        + unit.start().column()
        + " lines="
        + unit.textLines()
        + " | "
        + escape(unit.text());
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
  }
}
