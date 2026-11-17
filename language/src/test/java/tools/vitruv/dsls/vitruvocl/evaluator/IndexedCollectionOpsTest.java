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
package tools.vitruv.dsls.vitruvocl.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.DummyTestSpecification;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Semantics of the collection operations that use a semantic hash index instead of linear scans
 * ({@code isUnique}, {@code includesAll}, {@code excludesAll}, {@code symmetricDifference}, {@code
 * minus}), nested-collection hashing, and cached {@code matches()} patterns.
 */
class IndexedCollectionOpsTest extends DummyTestSpecification {

  @Test
  void isUniqueDetectsDuplicateSingletonKeys() {
    assertSingleBool(compile("Sequence{1, 2, 3}.isUnique(x | x)"), true);
    assertSingleBool(compile("Sequence{1, 2, 1}.isUnique(x | x)"), false);
    assertSingleBool(compile("Sequence{1, 2, 3, 4}.isUnique(x | x.mod(2))"), false);
  }

  @Test
  void isUniqueComparesNumericKeysAcrossTypes() {
    assertSingleBool(compile("Sequence{1, 2}.isUnique(x | x * 1.0)"), true);
    assertSingleBool(compile("Sequence{2, 4}.isUnique(x | x / 2.0 * 2)"), true);
  }

  @Test
  void isUniqueTreatsTwoEmptyKeysAsDuplicates() {
    // 6.div(0) is the empty value; two elements share it
    assertSingleBool(compile("Sequence{0, 0}.isUnique(x | 6.div(x))"), false);
    assertSingleBool(compile("Sequence{0, 1}.isUnique(x | 6.div(x))"), true);
  }

  @Test
  void isUniqueComparesMultiElementKeysPairwise() {
    assertSingleBool(compile("Sequence{1, 2}.isUnique(x | Sequence{x, x})"), true);
    assertSingleBool(compile("Sequence{1, 1}.isUnique(x | Sequence{x, 0})"), false);
  }

  @Test
  void includesAllAndExcludesAll() {
    assertSingleBool(compile("Sequence{1, 2, 3}.includesAll(Set{3, 1})"), true);
    assertSingleBool(compile("Sequence{1, 2, 3}.includesAll(Set{3, 4})"), false);
    assertSingleBool(compile("Sequence{1, 2, 3}.includesAll(Sequence{1.0})"), true);
    assertSingleBool(compile("Sequence{1, 2, 3}.excludesAll(Set{4, 5})"), true);
    assertSingleBool(compile("Sequence{1, 2, 3}.excludesAll(Set{5, 2})"), false);
  }

  @Test
  void symmetricDifferenceKeepsElementsOnlyInOneSide() {
    assertCollection(compile("Set{1, 2, 3}.symmetricDifference(Set{2, 3, 4})"), 1, 4);
    assertCollection(compile("Set{1, 2}.symmetricDifference(Set{1, 2})"));
  }

  @Test
  void minusRemovesIndexedElementsAndIsReplayable() {
    Type type = Type.sequence(Type.INTEGER);
    Value left = Value.of(ints(1, 2, 3, 4), type);
    Value right = Value.of(ints(2, 4, 6), type);
    Value result = left.minus(right);
    assertCollection(result, 1, 3);
    // a lazy result may be traversed again; the memoized index must stay valid
    assertEquals(2, result.size());
  }

  @Test
  void nestedCollectionsDedupeRegardlessOfElementOrder() {
    assertSize(compile("Sequence{1, 2, 1}.collectNested(x | Set{x, 9}).asSet()"), 2);
    assertSize(compile("Sequence{1, 2}.collectNested(x | Bag{1, 2}).asSet()"), 1);
  }

  @Test
  void matchesUsesRegexAndReportsInvalidPatterns() {
    assertSingleBool(compile("\"abc\".matches(\"[a-c]+\")"), true);
    assertSingleBool(compile("\"abd\".matches(\"[a-c]+\")"), false);
    // compile() fails the test on evaluation errors, so an error surfaces as AssertionError
    AssertionError error =
        assertThrows(AssertionError.class, () -> compile("\"abc\".matches(\"[unclosed\")"));
    assertTrue(error.getMessage().contains("Pass 3"), error.getMessage());
  }

  private static List<OCLElement> ints(int... values) {
    return java.util.Arrays.stream(values)
        .mapToObj(v -> (OCLElement) new OCLElement.IntValue(v))
        .toList();
  }
}
