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

package tools.vitruv.dsls.vitruvocl.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden-file test of the CLI: for every command line in {@code cli/golden.txt} it records the exit
 * status and every line of standard output and standard error, byte for byte (only line breaks and
 * the platform's path separator are normalized).
 */
class CliGoldenTest {

  private static final String GOLDEN = "/cli/golden.txt";
  private static final String CLI = "src/test/resources/cli";
  private static final String MM = "src/test/resources/test-metamodels/spaceMission.ecore";
  private static final String MODEL =
      "src/test/resources/test-models/spacecraft-voyager.spacemission";

  static Stream<Arguments> goldenCases() throws IOException {
    List<Arguments> cases = new ArrayList<>();
    String name = null;
    String args = null;
    List<String> expected = new ArrayList<>();
    for (String line : goldenLines()) {
      if (line.startsWith("# ")) {
        continue;
      }
      if (line.startsWith("### ")) {
        if (name != null) {
          cases.add(Arguments.of(name, args, expected));
        }
        name = line.substring(4);
        args = null;
        expected = new ArrayList<>();
      } else if (line.startsWith("$ ") || line.equals("$")) {
        args = line.length() > 2 ? line.substring(2) : "";
      } else if (line.startsWith("=> ")) {
        expected.add(line.substring(3));
      }
    }
    if (name != null) {
      cases.add(Arguments.of(name, args, expected));
    }
    return cases.stream();
  }

  private static List<String> goldenLines() throws IOException {
    try (InputStream in =
        Objects.requireNonNull(CliGoldenTest.class.getResourceAsStream(GOLDEN), GOLDEN)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldenCases")
  void outputMatchesGoldenFile(String name, String args, List<String> expected) throws IOException {
    String[] arguments =
        args.isEmpty()
            ? new String[0]
            : args.replace("{CLI}", CLI).replace("{MM}", MM).replace("{MODEL}", MODEL).split(" ");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int status;
    try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      status = VitruvOclCli.run(arguments, outStream, errStream);
    }

    List<String> actual = new ArrayList<>();
    actual.add("exit " + status);
    // an empty line is written without a trailing space, which editors may strip
    lines(out).forEach(line -> actual.add(line.isEmpty() ? "out" : "out " + line));
    lines(err).forEach(line -> actual.add(line.isEmpty() ? "err" : "err " + line));
    assertEquals(expected, actual);
  }

  /**
   * The lines of a captured stream, with the test paths written as on Unix and the working
   * directory as {@code {CWD}} (absolute paths in messages differ per machine).
   */
  private static List<String> lines(ByteArrayOutputStream stream) {
    String text = stream.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    for (String path : List.of(MODEL, MM, CLI)) {
      text = unixPath(text, Path.of(path).toString(), path);
    }
    String separator = Path.of("a", "b").toString().substring(1, 2);
    String cwd = Path.of("").toAbsolutePath().toString();
    text = unixPath(text, cwd + separator, "{CWD}/");
    return text.isEmpty() ? List.of() : text.lines().toList();
  }

  /** Replaces a platform path, also as escaped in JSON, by its Unix form. */
  private static String unixPath(String text, String platform, String unix) {
    return text.replace(platform.replace("\\", "\\\\"), unix).replace(platform, unix);
  }
}
