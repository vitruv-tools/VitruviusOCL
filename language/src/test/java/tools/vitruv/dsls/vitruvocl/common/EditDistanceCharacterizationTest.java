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

package tools.vitruv.dsls.vitruvocl.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins the edit distance and suggestion threshold used for "did you mean" diagnostics. */
class EditDistanceCharacterizationTest {

  @ParameterizedTest
  @CsvSource({
    "select, select, 0",
    "selcet, select, 1", // adjacent transposition counts once
    "slect, select, 1",
    "kitten, sitting, 3",
    "'', abc, 3",
    "abc, '', 3",
    "ca, abc, 3", // optimal string alignment, not unrestricted Damerau (which gives 2)
  })
  void distance(String a, String b, int expected) {
    assertThat(EditDistance.damerauLevenshtein(a, b)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"1, 1", "3, 1", "4, 2", "6, 2", "7, 3", "20, 3"})
  void threshold(int length, int expected) {
    assertThat(EditDistance.editThreshold(length)).isEqualTo(expected);
  }
}
