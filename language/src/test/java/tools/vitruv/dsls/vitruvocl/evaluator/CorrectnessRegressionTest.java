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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.DummyTestSpecification;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;

/**
 * Regression tests for correctness bugs found in the November 2026 audit: element equality and
 * ordering, hash/equals consistency, string-to-number conversion failures, {@code sortedBy} with
 * empty keys, and bag intersection multiplicities.
 */
class CorrectnessRegressionTest extends DummyTestSpecification {

  private static final EcoreFactory FACTORY = EcoreFactory.eINSTANCE;

  private static EClass newEClass(String name) {
    EPackage pkg = FACTORY.createEPackage();
    pkg.setName("p" + name);
    pkg.setNsURI("http://test/" + name);
    EClass eClass = FACTORY.createEClass();
    eClass.setName(name);
    pkg.getEClassifiers().add(eClass);
    return eClass;
  }

  private static EEnumLiteral literal(String name, int value) {
    EEnum eEnum = FACTORY.createEEnum();
    eEnum.setName("E" + name);
    EPackage pkg = FACTORY.createEPackage();
    pkg.setName("enums" + name);
    pkg.getEClassifiers().add(eEnum);
    EEnumLiteral lit = FACTORY.createEEnumLiteral();
    lit.setName(name);
    lit.setValue(value);
    eEnum.getELiterals().add(lit);
    return lit;
  }

  @Nested
  class MetaclassIdentity {

    private final EClass eClass = newEClass("Node");
    private final EObject first = EcoreUtil.create(eClass);
    private final EObject second = EcoreUtil.create(eClass);

    @Test
    void castedValuesOfSameInstanceAreEqual() {
      OCLElement a = new OCLElement.CastedMetaclassValue(first, eClass);
      OCLElement b = new OCLElement.CastedMetaclassValue(first, eClass);
      assertTrue(OCLElement.semanticEquals(a, b));
      assertEquals(0, OCLElement.compare(a, b));
    }

    @Test
    void castedValueEqualsUncastedValueOfSameInstance() {
      OCLElement casted = new OCLElement.CastedMetaclassValue(first, eClass);
      OCLElement plain = new OCLElement.MetaclassValue(first);
      assertTrue(OCLElement.semanticEquals(casted, plain));
      assertTrue(OCLElement.semanticEquals(plain, casted));
      assertEquals(0, OCLElement.compare(casted, plain));
      assertEquals(OCLElement.semanticHash(casted), OCLElement.semanticHash(plain));
    }

    @Test
    void castedValuesOfDifferentInstancesAreDistinct() {
      OCLElement a = new OCLElement.CastedMetaclassValue(first, eClass);
      OCLElement b = new OCLElement.CastedMetaclassValue(second, eClass);
      assertFalse(OCLElement.semanticEquals(a, b));
      int forward = OCLElement.compare(a, b);
      assertNotEquals(0, forward);
      assertEquals(-Integer.signum(forward), Integer.signum(OCLElement.compare(b, a)));
    }

    @Test
    void valuesOfDifferentCastedInstancesAreNotEqual() {
      Type type = Type.set(Type.ANY);
      Value a = Value.of(List.of(new OCLElement.CastedMetaclassValue(first, eClass)), type);
      Value b = Value.of(List.of(new OCLElement.CastedMetaclassValue(second, eClass)), type);
      assertNotEquals(a, b);
    }

    @Test
    void identityHashCollisionIsBrokenByStableTieBreak() {
      int forward = InstanceOrder.compare(first, second, 42, 42);
      int backward = InstanceOrder.compare(second, first, 42, 42);
      assertNotEquals(0, forward, "distinct instances with colliding hashes must differ");
      assertEquals(-Integer.signum(forward), Integer.signum(backward));
      assertEquals(forward, InstanceOrder.compare(first, second, 42, 42), "order must be stable");
      assertEquals(0, InstanceOrder.compare(first, first, 42, 42));
    }
  }

  @Nested
  class HashEqualsContract {

    @Test
    void enumLiteralsWithSameValueButDifferentNamesAreNotEqualValues() {
      Value red = Value.singleton(new OCLElement.EnumValue(literal("RED", 0)), Type.ANY);
      Value blue = Value.singleton(new OCLElement.EnumValue(literal("BLUE", 0)), Type.ANY);
      assertNotEquals(red, blue);
    }

    @Test
    void crossMetamodelEnumSetsWithSameNamesAreSemanticallyEqual() {
      // Same literal names, but declared values swapped between the two metamodels
      Type type = Type.set(Type.ANY);
      Value first =
          Value.of(
              List.of(
                  new OCLElement.EnumValue(literal("ORANGE", 0)),
                  new OCLElement.EnumValue(literal("RED", 1))),
              type);
      Value second =
          Value.of(
              List.of(
                  new OCLElement.EnumValue(literal("RED", 0)),
                  new OCLElement.EnumValue(literal("ORANGE", 1))),
              type);
      assertTrue(Value.semanticEquals(first, second));
    }

    @Test
    void relationalEnumOrderFollowsDeclaredValue() {
      OCLElement low = new OCLElement.EnumValue(literal("ZULU", 0));
      OCLElement high = new OCLElement.EnumValue(literal("ALPHA", 1));
      assertTrue(OCLElement.compareRelational(low, high) < 0);
    }

    @Test
    void equalValuesHaveEqualHashCodes() {
      EClass eClass = newEClass("Item");
      EObject instance = EcoreUtil.create(eClass);
      Value casted =
          Value.singleton(new OCLElement.CastedMetaclassValue(instance, eClass), Type.ANY);
      Value plain = Value.singleton(new OCLElement.MetaclassValue(instance), Type.ANY);
      assertEquals(casted, plain);
      assertEquals(casted.hashCode(), plain.hashCode());

      Value red1 = Value.singleton(new OCLElement.EnumValue(literal("RED", 0)), Type.ANY);
      Value red2 = Value.singleton(new OCLElement.EnumValue(literal("RED", 0)), Type.ANY);
      assertEquals(red1, red2);
      assertEquals(red1.hashCode(), red2.hashCode());
    }
  }

  @Nested
  class StringConversion {

    @Test
    void toIntegerOfUnparsableStringIsEmpty() {
      assertSize(compile("\"abc\".toInteger()"), 0);
    }

    @Test
    void toRealOfUnparsableStringIsEmpty() {
      assertSize(compile("\"abc\".toReal()"), 0);
    }

    @Test
    void validConversionsStillWork() {
      assertSingleInt(compile("\" 42 \".toInteger()"), 42);
      assertSingleDouble(compile("\"0\".toReal()"), 0.0);
    }
  }

  @Nested
  class SortedByEmptyKey {

    @Test
    void emptyKeySortsFirstInsteadOfCrashing() {
      // 6.div(0) yields the empty value, so element 1 gets an empty sort key
      Value result = compile("Sequence{3, 1, 2}.sortedBy(x | 6.div(x - 1))");
      assertSize(result, 3);
      assertIntAt(result, 0, 1, "element with empty key sorts first");
      assertIntAt(result, 1, 3, "key 6.div(2) = 3");
      assertIntAt(result, 2, 2, "key 6.div(1) = 6");
    }
  }

  @Nested
  class BooleanBodyRules {

    @Test
    void selectAndRejectAcceptOptionalBooleanBody() {
      assertSize(compile("Sequence{1, 2}.select(x | Sequence{x > 1}.first())"), 1);
      assertSize(compile("Sequence{1, 2}.reject(x | Sequence{x > 1}.first())"), 1);
    }

    @Test
    void selectAndRejectAcceptBooleanCollectionBody() {
      compile("Sequence{1, 2}.select(x | Sequence{x > 1})");
      compile("Sequence{1, 2}.reject(x | Sequence{x > 1})");
    }
  }

  @Nested
  class SortedByResultKind {

    @Test
    void sortedByOnSequenceKeepsDuplicatesAsSequence() {
      Value result = compile("Sequence{3, 1, 3}.sortedBy(x | x)");
      assertSize(result, 3);
      assertTrue(result.getRuntimeType().isOrdered());
      assertFalse(result.getRuntimeType().isUnique(), "Sequence.sortedBy yields a Sequence");
    }

    @Test
    void sortedByOnBagYieldsSequence() {
      Value result = compile("Bag{2, 1, 2}.sortedBy(x | x)");
      assertSize(result, 3);
      assertTrue(result.getRuntimeType().isOrdered());
      assertFalse(result.getRuntimeType().isUnique(), "Bag.sortedBy yields a Sequence");
    }

    @Test
    void sortedByOnSetYieldsOrderedSet() {
      Value result = compile("Set{3, 1, 2}.sortedBy(x | x)");
      assertSize(result, 3);
      assertTrue(result.getRuntimeType().isOrdered());
      assertTrue(result.getRuntimeType().isUnique(), "Set.sortedBy yields an OrderedSet");
    }

    @Test
    void sequenceResultKeepsSequenceSemanticsDownstream() {
      // including() on a Sequence appends; on an OrderedSet it would drop the duplicate
      assertSize(compile("Sequence{2, 1, 2}.sortedBy(x | x).including(1)"), 4);
    }
  }

  @Nested
  class Intersection {

    @Test
    void setIntersectBagHasNoDuplicates() {
      assertSize(compile("Set{1}.intersection(Bag{1, 1})"), 1);
    }

    @Test
    void bagIntersectBagUsesMinimumMultiplicity() {
      assertSize(compile("Bag{1, 1}.intersection(Bag{1})"), 1);
      assertSize(compile("Bag{1, 1, 2}.intersection(Bag{1, 1, 1})"), 2);
      assertSize(compile("Bag{1, 2, 3}.intersection(Bag{3, 2, 2})"), 2);
    }
  }
}
