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

package tools.vitruv.dsls.vitruvocl.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.vitruv.dsls.vitruvocl.source.ConstraintUnit;

/**
 * The name of a result is the name of the first constraint block of its text, read from the tokens.
 *
 * <p>Until the name was read from the {@link ConstraintUnit}, the CLI and the violation messages
 * took it from the first {@code inv|pre|post NAME :} a regular expression found anywhere in the
 * text. The cases marked "fixed" are where that differed: it missed names with a parameter
 * specification, read names from strings, comments and text outside any context, and skipped an
 * unnamed first block.
 */
class ConstraintResultNameTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiterString = "=>",
      nullValues = "<none>",
      quoteCharacter = '`',
      value = {
        "context S inv myInvariant:\\n  self.mass > 0 => myInvariant",
        "context S inv:\\n  self.mass > 0 => <none>",
        "context S inv  myName  :\\n  self.mass > 0 => myName",
        "context S inv singleLine: self.mass > 0 => singleLine",
        "context S\\n  self.mass > 0 => <none>",
        "context S::op(p : Integer) pre p1: p > 0 => p1",
        "context S::op(p : Integer) post q1: true => q1",
        "context S post: self.mass@pre > 0 => <none>",
        "context S inv a: true inv b: false => a",
        "context S inv a : true => a",
        "context S inv a: @severity CRITICAL true => a",
        "context S => <none>",
        "` ` => <none>",
        "garbage => <none>",
        "context S inv self.mass > 0 => <none>",
        "import \"x.ecore\"\\ncontext S inv a: true => a",
        "\uFEFFcontext S inv a: true => a",
        // a reserved word is still the name, although the grammar rejects it
        "context S inv count: true => count",
        // fixed: the name before a parameter specification is found
        "context S inv myName(self.mass > 0): true => myName",
        // fixed: no name is read from a string, a comment or a later block
        "context S inv: self.n != \"inv b: x\" => <none>",
        "context S -- inv y: comment\\ninv: true => <none>",
        "context S inv: true inv b: false => <none>",
        "context A inv: true context B inv b: x => <none>",
        "inv a: true context S inv: x => <none>"
      })
  void nameIsTheNameOfTheFirstBlock(String constraint, String expectedName) {
    ConstraintResult result =
        new ConstraintResult(
            constraint.replace("\\n", "\n"), true, List.of(), List.of(), List.of());

    assertEquals(expectedName, result.getName().orElse(null));
    result
        .getUnit()
        .ifPresent(unit -> assertNotEquals(ConstraintUnit.Kind.EMPTY_CONTEXT, unit.kind()));
  }
}
