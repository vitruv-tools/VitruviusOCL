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

package tools.vitruv.dsls.vitruvocl.typechecker;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import tools.vitruv.dsls.vitruvocl.common.EditDistance;
import tools.vitruv.dsls.vitruvocl.operations.OclOperations;

/**
 * "Did you mean?" candidates for misspelled names: the closest known name by case-insensitive
 * Damerau-Levenshtein distance, and whether it is close enough for a confident suggestion (see
 * {@link EditDistance#editThreshold}).
 */
final class SuggestionProvider {

  /** All operation names known to VitruvOCL, from the shared {@link OclOperations} catalog. */
  private static final List<String> KNOWN_OPERATIONS = OclOperations.names();

  private SuggestionProvider() {}

  /**
   * Case-insensitive edit distance between a typed name and a candidate.
   *
   * @param typed the name as written
   * @param candidate a known name
   * @return the Damerau-Levenshtein distance of both names in lower case
   */
  static int distance(String typed, String candidate) {
    return EditDistance.damerauLevenshtein(
        typed.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
  }

  /**
   * The candidate closest to {@code typed}, regardless of distance; the first one on ties.
   *
   * @param typed the name as written
   * @param candidates the known names
   * @return the closest candidate, or {@code null} if there are none
   */
  static String closest(String typed, Collection<String> candidates) {
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : candidates) {
      int d = distance(typed, candidate);
      if (d < bestDistance) {
        bestDistance = d;
        best = candidate;
      }
    }
    return best;
  }

  /**
   * The candidate closest to {@code typed} if it is within the edit threshold for the length of
   * {@code typed}.
   *
   * @param typed the name as written
   * @param candidates the known names
   * @return the close candidate, or {@code null} if none is close enough
   */
  static String closeMatch(String typed, Collection<String> candidates) {
    String best = closest(typed, candidates);
    return isClose(typed, best) ? best : null;
  }

  /**
   * Whether {@code candidate} is within the edit threshold for the length of {@code typed}.
   *
   * @param typed the name as written
   * @param candidate a known name, or {@code null}
   * @return {@code true} if the candidate is close enough for a confident suggestion
   */
  static boolean isClose(String typed, String candidate) {
    return candidate != null
        && distance(typed, candidate) <= EditDistance.editThreshold(typed.length());
  }

  /**
   * The known operation closest to {@code typed}, regardless of distance.
   *
   * @param typed the operation name as written
   * @return the closest operation name, or {@code null} only if the catalog is empty
   */
  static String closestOperation(String typed) {
    return closest(typed, KNOWN_OPERATIONS);
  }

  /**
   * The known operation closest to {@code typed} if it is within the edit threshold.
   *
   * @param typed the operation name as written
   * @return the close operation name, or {@code null} if none is close enough
   */
  static String closeOperation(String typed) {
    return closeMatch(typed, KNOWN_OPERATIONS);
  }
}
