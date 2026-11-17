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

package tools.vitruv.dsls.vitruvocl.evaluator.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.jupiter.api.Test;

/**
 * The buffers of {@link LazyOperations#dedupe} and {@link LazyOperations#intersect} are hashed by
 * {@link OCLElement#semanticHash}, so a lookup compares a candidate only with the elements in its
 * hash bucket instead of with every buffered element.
 */
class HashedBufferTest {

  private static final int SIZE = 2_000;

  private final AtomicInteger equalsCalls = new AtomicInteger();

  private final BiPredicate<OCLElement, OCLElement> countingEquals =
      (a, b) -> {
        equalsCalls.incrementAndGet();
        return OCLElement.semanticEquals(a, b);
      };

  private static List<OCLElement> ints(int from, int to) {
    List<OCLElement> list = new ArrayList<>();
    for (int i = from; i < to; i++) {
      list.add(new OCLElement.IntValue(i));
    }
    return list;
  }

  private static EEnumLiteral enumLiteral(String enumName, String literalName) {
    EcoreFactory factory = EcoreFactory.eINSTANCE;
    EPackage pkg = factory.createEPackage();
    pkg.setName(enumName.toLowerCase(java.util.Locale.ROOT));
    EEnum eEnum = factory.createEEnum();
    eEnum.setName(enumName);
    pkg.getEClassifiers().add(eEnum);
    EEnumLiteral literal = factory.createEEnumLiteral();
    literal.setName(literalName);
    eEnum.getELiterals().add(literal);
    return literal;
  }

  private static List<OCLElement> drain(OCLElementSource source) {
    List<OCLElement> out = new ArrayList<>();
    source.newIterator().forEachRemaining(out::add);
    return out;
  }

  @Test
  void semanticallyEqualElements_haveEqualHashes() {
    EEnumLiteral red = enumLiteral("Color", "RED");
    EEnumLiteral otherRed = enumLiteral("Signal", "RED"); // same name, different enum
    List<List<OCLElement>> equalGroups =
        List.of(
            List.of(
                new OCLElement.IntValue(1),
                new OCLElement.DoubleValue(1.0),
                new OCLElement.FloatValue(1.0f)),
            List.of(
                new OCLElement.StringValue("RED"),
                new OCLElement.EnumValue(red),
                new OCLElement.EnumValue(otherRed)),
            List.of(new OCLElement.BoolValue(true), new OCLElement.BoolValue(true)),
            List.of(new OCLElement.ObjectRef("o1"), new OCLElement.ObjectRef("o1")));

    for (List<OCLElement> group : equalGroups) {
      for (OCLElement a : group) {
        for (OCLElement b : group) {
          assertTrue(OCLElement.semanticEquals(a, b), a + " should equal " + b);
          assertEquals(OCLElement.semanticHash(a), OCLElement.semanticHash(b), a + " vs " + b);
        }
      }
    }
  }

  @Test
  void dedupe_ofDistinctElements_comparesOnlyWithinHashBuckets() {
    List<OCLElement> source = ints(0, SIZE);
    source.addAll(ints(0, SIZE)); // every element twice

    List<OCLElement> result =
        drain(
            LazyOperations.dedupe(
                OCLElementSource.of(source), countingEquals, OCLElement::semanticHash));

    assertEquals(ints(0, SIZE), result);
    assertTrue(equalsCalls.get() <= 2 * SIZE, "equals calls: " + equalsCalls.get());
  }

  @Test
  void dedupe_treatsNumericTypesAsEqualLikeSemanticEquals() {
    List<OCLElement> source =
        List.of(
            new OCLElement.IntValue(1),
            new OCLElement.DoubleValue(1.0),
            new OCLElement.IntValue(2));

    List<OCLElement> result =
        drain(
            LazyOperations.dedupe(
                OCLElementSource.of(source),
                OCLElement::semanticEquals,
                OCLElement::semanticHash));

    assertEquals(List.of(new OCLElement.IntValue(1), new OCLElement.IntValue(2)), result);
  }

  @Test
  void intersect_comparesOnlyWithinHashBuckets() {
    List<OCLElement> left = ints(0, SIZE);
    List<OCLElement> right = ints(SIZE / 2, SIZE + SIZE / 2);

    List<OCLElement> result =
        drain(
            LazyOperations.intersect(
                OCLElementSource.of(left),
                OCLElementSource.of(right),
                countingEquals,
                OCLElement::semanticHash));

    assertEquals(SIZE / 2, result.size());
    assertTrue(result.containsAll(ints(SIZE / 2, SIZE)));
    assertTrue(equalsCalls.get() <= 2 * SIZE, "equals calls: " + equalsCalls.get());
  }
}
