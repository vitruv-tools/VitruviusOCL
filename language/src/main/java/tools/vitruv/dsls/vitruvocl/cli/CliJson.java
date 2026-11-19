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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.List;
import tools.vitruv.dsls.vitruvocl.result.BatchValidationResult;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;
import tools.vitruv.dsls.vitruvocl.result.FileError;
import tools.vitruv.dsls.vitruvocl.result.Warning;

/**
 * The JSON documents the CLI prints, as records serialized with Gson.
 *
 * <p>Fields are written in declaration order; a {@code null} field is left out, which is how the
 * optional parts (the severity of an {@code eval-batch} error, the errors and warnings of an {@code
 * eval-batch} constraint without any) are omitted. HTML characters are not escaped, so the output
 * matches the CLI's earlier hand-written JSON, except that control characters and U+2028/U+2029 are
 * escaped now; {@code CliGoldenTest} pins the bytes, including the field order.
 */
final class CliJson {

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private CliJson() {}

  /**
   * One error: a compiler error with its position, or a file error at line and column 0.
   *
   * @param line the line, 0 for a file error
   * @param column the column, 0 for a file error
   * @param message the message
   * @param severity the severity, or {@code null} where the document has none ({@code eval-batch})
   */
  record ErrorEntry(int line, int column, String message, String severity) {}

  /** The result of {@code check}. */
  record CheckOutput(boolean success, List<ErrorEntry> diagnostics) {}

  /** The result of {@code eval}. */
  record EvalOutput(
      boolean success, boolean satisfied, List<ErrorEntry> errors, List<String> warnings) {}

  /**
   * One constraint of {@code eval-batch}.
   *
   * @param errors the errors, or {@code null} if there are none
   * @param warnings the warnings, or {@code null} if there are none
   */
  record BatchEntry(
      String name,
      boolean success,
      boolean satisfied,
      List<ErrorEntry> errors,
      List<String> warnings) {}

  /** The result of {@code eval-batch}. */
  record BatchOutput(boolean success, List<BatchEntry> constraints) {}

  /** A command that failed with an exception. */
  record FailureOutput(boolean success, String error) {}

  /** The JSON of {@code check}: success and every error, with severity. */
  static String check(ConstraintResult result) {
    return GSON.toJson(new CheckOutput(result.isSuccess(), errors(result, true)));
  }

  /** The JSON of {@code eval}: success, satisfaction, errors with severity and warnings. */
  static String eval(ConstraintResult result) {
    return GSON.toJson(
        new EvalOutput(
            result.isSuccess(), result.isSatisfied(), errors(result, true), warnings(result)));
  }

  /**
   * The JSON of {@code eval-batch}: one entry per constraint, named by its first block ({@code
   * unknown} if unnamed), with errors (without severity) and warnings only if there are any.
   */
  static String batch(BatchValidationResult batch) {
    List<BatchEntry> entries = new ArrayList<>();
    for (ConstraintResult result : batch.getResults()) {
      List<ErrorEntry> errors = errors(result, false);
      List<String> warnings = warnings(result);
      entries.add(
          new BatchEntry(
              result.getName().orElse("unknown"),
              result.isSuccess(),
              result.isSatisfied(),
              errors.isEmpty() ? null : errors,
              warnings.isEmpty() ? null : warnings));
    }
    return GSON.toJson(new BatchOutput(true, entries));
  }

  /** The JSON of a command that failed with {@code exception}. */
  static String failure(Exception exception) {
    return GSON.toJson(
        new FailureOutput(
            false, exception.getClass().getSimpleName() + ": " + exception.getMessage()));
  }

  /**
   * Both error categories a {@link ConstraintResult} can carry: its compiler errors (with source
   * position) followed by its file errors (missing or unreadable metamodel or model files, reported
   * once per batch rather than per constraint, at line and column 0). Without them, a batch that
   * failed to load would show results that fail for no visible reason.
   *
   * @param includeSeverity whether to add the severity ({@code ERROR} for a file error); {@code
   *     eval-batch} omits it for backward compatibility with its {@code {line,column,message}}
   *     shape
   */
  private static List<ErrorEntry> errors(ConstraintResult result, boolean includeSeverity) {
    List<ErrorEntry> errors = new ArrayList<>();
    result
        .getCompilerErrors()
        .forEach(
            e ->
                errors.add(
                    new ErrorEntry(
                        e.getLine(),
                        e.getColumn(),
                        e.getMessage(),
                        includeSeverity ? String.valueOf(e.getSeverity()) : null)));
    for (FileError e : result.getFileErrors()) {
      errors.add(new ErrorEntry(0, 0, e.toString(), includeSeverity ? "ERROR" : null));
    }
    return errors;
  }

  private static List<String> warnings(ConstraintResult result) {
    return result.getWarnings().stream().map(Warning::getMessage).toList();
  }
}
