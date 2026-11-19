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
import java.util.List;
import java.util.stream.Stream;
import tools.vitruv.dsls.vitruvocl.source.ConstraintUnit;
import tools.vitruv.dsls.vitruvocl.source.OclSourceSplitter;

/**
 * Reading {@code .ocl} constraint files and the conventional project layout ({@code
 * model/src/main/{constraints.ocl,ecore,instances}}).
 */
public final class ConstraintFiles {

  private ConstraintFiles() {}

  /**
   * Reads the constraints of an {@code .ocl} file, one unit per {@code inv}/{@code pre}/{@code
   * post} block (and per context without blocks), each prefixed with its {@code context} header and
   * without blank, comment and import lines; see {@link OclSourceSplitter}. Text before the first
   * {@code context} is ignored; syntax errors are not reported here but when compiling the units.
   *
   * @throws IOException if the file cannot be read
   */
  public static List<String> read(Path oclFile) throws IOException {
    return OclSourceSplitter.split(Files.readString(oclFile)).units().stream()
        .map(ConstraintUnit::text)
        .toList();
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
