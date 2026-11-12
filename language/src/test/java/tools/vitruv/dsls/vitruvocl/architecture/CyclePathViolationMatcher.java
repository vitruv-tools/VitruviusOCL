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

import com.tngtech.archunit.library.freeze.ViolationLineMatcher;
import java.util.regex.Pattern;

/**
 * Matches frozen cycle violations by their package path only.
 *
 * <p>ArchUnit describes a cycle as a header ({@code Cycle detected: a -> b -> a}) followed by a
 * sample of the class-level dependencies along each edge. That sample changes whenever a class
 * gains or loses a dependency, which would make ordinary edits inside an already frozen cycle look
 * like new violations. Comparing only the header keeps the frozen rule to its purpose: it fails
 * when a new package cycle appears.
 *
 * <p>Registered as {@code freeze.lineMatcher} in {@code archunit.properties}, so it replaces
 * ArchUnit's default matcher for all frozen rules. Violations that are not cycles are therefore
 * compared the way that default does: ignoring source line numbers and the numbering of lambdas
 * and anonymous classes, which shift with unrelated edits.
 */
public final class CyclePathViolationMatcher implements ViolationLineMatcher {

  private static final String CYCLE_PREFIX = "Cycle detected:";

  /** Start of the first dependency listing, which ends the cycle header. */
  private static final Pattern DETAILS_START = Pattern.compile("\\n\\s*1\\. Dependencies of ");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Source line in a location such as {@code (Foo.java:123)}. */
  private static final Pattern LINE_NUMBER = Pattern.compile("(\\.java):\\d+\\)");

  /** Numbering of lambdas ({@code lambda$m$3}) and anonymous classes ({@code Foo$1}). */
  private static final Pattern SYNTHETIC_NUMBER = Pattern.compile("\\$\\d+");

  @Override
  public boolean matches(String storedViolation, String actualViolation) {
    if (isCycle(storedViolation) && isCycle(actualViolation)) {
      return cyclePath(storedViolation).equals(cyclePath(actualViolation));
    }
    return withoutVolatileParts(storedViolation).equals(withoutVolatileParts(actualViolation));
  }

  private static String withoutVolatileParts(String violation) {
    String withoutLines = LINE_NUMBER.matcher(violation).replaceAll("$1)");
    return SYNTHETIC_NUMBER.matcher(withoutLines).replaceAll("\\$");
  }

  private static boolean isCycle(String violation) {
    return violation.startsWith(CYCLE_PREFIX);
  }

  /** The cycle header with line continuations and indentation collapsed. */
  static String cyclePath(String violation) {
    String header = DETAILS_START.split(violation, 2)[0];
    return WHITESPACE.matcher(header).replaceAll(" ").trim();
  }
}
