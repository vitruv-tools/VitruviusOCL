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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.vitruv.dsls.vitruvocl.source.ConstraintUnit;
import tools.vitruv.dsls.vitruvocl.source.OclSourceSplitter;
import tools.vitruv.framework.vsum.constraints.ConstraintDiagnostic;
import tools.vitruv.framework.vsum.constraints.ConstraintSeverity;
import tools.vitruv.framework.vsum.constraints.InvalidConstraintException;

/**
 * Splits the content of a {@code .ocl} file into single invariants, each of which can be evaluated
 * on its own, using {@link OclSourceSplitter}; {@code pre} and {@code post} blocks are skipped.
 */
final class OclInvariantParser {

  /**
   * A single invariant with its own context declaration.
   *
   * @param name the invariant name, {@code null} if the invariant is unnamed
   * @param contextType the context type as written, e.g. {@code shop::Item}
   * @param severity the value of the {@code @severity} annotation
   * @param source a complete constraint consisting of the context declaration and the invariant
   */
  record ParsedInvariant(
      String name, String contextType, ConstraintSeverity severity, String source) {}

  private OclInvariantParser() {}

  /**
   * Parses the given constraints.
   *
   * @param content the content of an {@code .ocl} file
   * @return the invariants in declaration order; {@code pre} and {@code post} blocks are skipped,
   *     since the checker evaluates states, not transactions
   * @throws InvalidConstraintException if the content contains syntax errors or unknown severities
   */
  static List<ParsedInvariant> parse(String content) {
    OclSourceSplitter.SplitResult split = OclSourceSplitter.split(content);
    if (!split.syntaxErrors().isEmpty()) {
      throw new InvalidConstraintException(
          split.syntaxErrors().stream()
              .map(
                  error ->
                      new ConstraintDiagnostic(
                          error.position().line(), error.position().column(), error.message()))
              .toList());
    }
    List<ParsedInvariant> invariants = new ArrayList<>();
    for (ConstraintUnit unit : split.units()) {
      if (unit.kind() == ConstraintUnit.Kind.INV) {
        invariants.add(
            new ParsedInvariant(
                unit.name(),
                unit.contextType(),
                severity(unit),
                unit.header() + "\n  " + unit.body()));
      }
    }
    return invariants;
  }

  private static ConstraintSeverity severity(ConstraintUnit unit) {
    if (unit.severity() == null) {
      return ConstraintSeverity.WARNING;
    }
    try {
      // the type checker accepts exactly the upper-case names of the severity levels
      return ConstraintSeverity.valueOf(unit.severity());
    } catch (IllegalArgumentException unknownSeverity) {
      throw new InvalidConstraintException(
          List.of(
              new ConstraintDiagnostic(
                  unit.severityPosition().line(),
                  unit.severityPosition().column(),
                  "unknown severity '"
                      + unit.severity()
                      + "', valid values are "
                      + Arrays.toString(ConstraintSeverity.values()))));
    }
  }

  /**
   * Maps a severity reported by the VitruvOCL evaluator to the Vitruv severity, falling back to
   * {@link ConstraintSeverity#WARNING} for unknown values.
   *
   * @param value the severity of a violation, may be {@code null}
   * @return the severity
   */
  static ConstraintSeverity toSeverity(String value) {
    if (value == null) {
      return ConstraintSeverity.WARNING;
    }
    try {
      return ConstraintSeverity.valueOf(value);
    } catch (IllegalArgumentException unknownSeverity) {
      return ConstraintSeverity.WARNING;
    }
  }
}
