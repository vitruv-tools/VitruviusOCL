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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reading {@code .ocl} constraint files and the conventional project layout ({@code
 * model/src/main/{constraints.ocl,ecore,instances}}).
 */
public final class ConstraintFiles {

  /** A constraint keyword at a word boundary, not the {@code pre} of the {@code @pre} operator. */
  private static final Pattern CONSTRAINT_KEYWORD = Pattern.compile("(?<!@)\\b(?:inv|pre|post)\\b");

  private ConstraintFiles() {}

  /**
   * Reads the constraints of an {@code .ocl} file, one unit per {@code inv}/{@code pre}/{@code
   * post} block, each prefixed with its {@code context} header. Comment lines ({@code --}), import
   * declarations and blank lines are dropped.
   *
   * @throws IOException if the file cannot be read
   */
  public static List<String> read(Path oclFile) throws IOException {
    String content = Files.readString(oclFile);
    StringBuilder cleaned = new StringBuilder();
    for (String line : content.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("--") && !trimmed.startsWith("import ") && !trimmed.isEmpty()) {
        cleaned.append(line).append("\n");
      }
    }
    List<String> constraints = new ArrayList<>();
    for (String part : cleaned.toString().split("(?=context\\s)")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty() && trimmed.startsWith("context")) {
        constraints.addAll(splitByConstraintKeyword(trimmed));
      }
    }
    return constraints;
  }

  /**
   * Splits a {@code context ...} block on its {@code inv}/{@code pre}/{@code post} boundaries, so
   * that a shared header followed by several blocks yields independent units, each re-prefixed with
   * the header. The lookbehind keeps {@code self.mass@pre} from being split mid-token.
   */
  private static List<String> splitByConstraintKeyword(String contextBlock) {
    Matcher headerMatcher = CONSTRAINT_KEYWORD.matcher(contextBlock);
    if (!headerMatcher.find()) {
      return List.of(contextBlock);
    }
    String header = contextBlock.substring(0, headerMatcher.start()).trim();
    List<String> result = new ArrayList<>();
    for (String segment : contextBlock.split("(?<!@)(?=\\b(?:inv|pre|post)\\b)")) {
      String trimmed = segment.trim();
      if (!trimmed.isEmpty() && !trimmed.startsWith("context")) {
        result.add(header + "\n" + trimmed);
      }
    }
    return result.isEmpty() ? List.of(contextBlock) : result;
  }

  /** The constraints file of a project: {@code model/src/main/constraints.ocl}. */
  public static Path projectConstraints(Path projectDir) {
    return mainDir(projectDir).resolve("constraints.ocl");
  }

  /** The {@code .ecore} files below {@code model/src/main/ecore}; empty if it does not exist. */
  static Path[] projectMetamodels(Path projectDir) throws IOException {
    return regularFiles(mainDir(projectDir).resolve("ecore"), ".ecore");
  }

  /** All files below {@code model/src/main/instances}; empty if it does not exist. */
  static Path[] projectInstances(Path projectDir) throws IOException {
    return regularFiles(mainDir(projectDir).resolve("instances"), "");
  }

  private static Path mainDir(Path projectDir) {
    return projectDir.resolve("model/src/main");
  }

  private static Path[] regularFiles(Path directory, String extension) throws IOException {
    if (!Files.isDirectory(directory)) {
      return new Path[0];
    }
    try (Stream<Path> stream = Files.walk(directory)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase().endsWith(extension))
          .toArray(Path[]::new);
    }
  }
}
