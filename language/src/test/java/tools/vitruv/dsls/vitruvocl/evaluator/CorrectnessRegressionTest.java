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
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.vitruv.dsls.vitruvocl.DummyTestSpecification;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;
import tools.vitruv.dsls.vitruvocl.typechecker.TypeCheckVisitor;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Regression tests for correctness bugs found in the November 2026 audit: element equality and
 * ordering, hash/equals consistency, string-to-number conversion failures, {@code sortedBy} with
 * empty keys, bag intersection multiplicities, {@code at()} on string sequences, {@code any}/{@code
 * one} with undefined bodies, {@code or} with an undefined operand, and the type checking of
 * Real-to-Integer conformance and {@code includes}/{@code excludes}/{@code excluding} arguments.
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
  class TypeTests {

    @Test
    void everyValueIsKindOfOclAny() {
      assertSingleBool(compile("1.5.oclIsKindOf(OclAny)"), true);
      assertSingleBool(compile("1.oclIsKindOf(OclAny)"), true);
      assertSingleBool(compile("\"a\".oclIsKindOf(OclAny)"), true);
      assertSingleBool(compile("true.oclIsKindOf(OclAny)"), true);
    }

    @Test
    void noValueHasExactTypeOclAny() {
      assertSingleBool(compile("1.5.oclIsTypeOf(OclAny)"), false);
    }

    @Test
    void integerIsKindOfRealButNotTypeOfReal() {
      // Integer conforms to Real (as in the type checker's conformance rules)
      assertSingleBool(compile("1.oclIsKindOf(Real)"), true);
      assertSingleBool(compile("1.oclIsTypeOf(Real)"), false);
    }

    @Test
    void realIsNotKindOfInteger() {
      assertSingleBool(compile("1.5.oclIsKindOf(Integer)"), false);
    }

    /** Every primitive spelling resolves, both as keyword and as plain type name. */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "Real | 1.5",
          "Double | 1.5",
          "Float | 1.5",
          "Integer | 1",
          "UnlimitedNatural | 1",
          "String | \"a\"",
          "ID | \"a\"",
          "Boolean | true",
          "OclAny | 1.5"
        })
    void primitiveTypeNamesResolve(String typeName, String value) {
      assertSize(compile("let x : " + typeName + " = " + value + " in x"), 1);
      assertSize(compile("Sequence{" + value + "}.oclAsType(Sequence(" + typeName + "))"), 1);
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

  /** Runs passes 1 and 2 only and returns the messages of all reported errors. */
  private List<String> typeErrors(String input) {
    ParseTree tree = parse(input);
    ErrorCollector errors = new ErrorCollector();
    var spec = buildDummySpec();
    SymbolTable symbolTable = new SymbolTableImpl(spec);
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(symbolTable, spec, errors, scopes).visit(tree);
    TypeCheckVisitor typeChecker = new TypeCheckVisitor(symbolTable, spec, errors, scopes);
    typeChecker.visit(tree);
    return errors.getErrors().stream().map(CompileError::getMessage).toList();
  }

  /** Asserts that type checking reports an error whose message contains {@code fragment}. */
  private void assertTypeError(String input, String fragment) {
    List<String> messages = typeErrors(input);
    assertTrue(
        messages.stream().anyMatch(m -> m.contains(fragment)),
        "Expected a type error containing '" + fragment + "' for: " + input + ", got " + messages);
  }

  @Nested
  class AtOnStringSequence {

    @Test
    void atReturnsWholeStringElement() {
      assertSingleString(compile("Sequence{\"ab\", \"cd\"}.at(1)"), "ab");
      assertSingleString(compile("Sequence{\"ab\", \"cd\"}.at(2)"), "cd");
    }

    @Test
    void atOnSingleStringStillReturnsCharacter() {
      assertSingleString(compile("\"ab\".at(2)"), "b");
    }
  }

  @Nested
  class UndefinedIteratorBody {

    @Test
    void anyWithUndefinedBodyFindsNothing() {
      assertSize(compile("Sequence{1, 2}.any(x | Sequence{true}.select(b | false).first())"), 0);
    }

    @Test
    void oneWithUndefinedBodyIsFalse() {
      assertSingleBool(
          compile("Sequence{1, 2}.one(x | Sequence{true}.select(b | false).first())"), false);
    }
  }

  @Nested
  class OrWithUndefinedOperand {

    // compile() parses a prefixed expression, so infix expressions are parenthesized

    @Test
    void trueOrUndefinedIsTrue() {
      assertSingleBool(compile("(true or Sequence{true}.select(b | false).first())"), true);
      assertSingleBool(compile("(Sequence{true}.select(b | false).first() or true)"), true);
    }

    @Test
    void falseOrUndefinedStaysFalse() {
      assertSingleBool(compile("(false or Sequence{true}.select(b | false).first())"), false);
    }
  }

  @Nested
  class RealToIntegerConformance {

    @Test
    void realIsNotAssignableToInteger() {
      assertTypeError("let x : Integer = 1.5 in x", "Type mismatch");
    }

    @Test
    void realIsNotAValidIndex() {
      assertTypeError("Sequence{1, 2}.at(1.5)", "index must be Integer");
    }

    @Test
    void conditionalWithRealBranchIsNotInteger() {
      assertTypeError("let x : Integer = if true then 1.5 else 1 endif in x", "Type mismatch");
    }

    @Test
    void integerIsStillAssignableToReal() {
      assertSize(compile("let x : Real = 1 in x"), 1);
    }
  }

  @Nested
  class MembershipArgumentTyping {

    private static final String SINGLE_ELEMENT = "expects a single element";

    @Test
    void includesWithCollectionArgumentIsRejected() {
      assertTypeError("Set{1}.includes(Set{1, 2}.select(x | x > 1))", SINGLE_ELEMENT);
    }

    @Test
    void excludesWithCollectionArgumentIsRejected() {
      assertTypeError("Set{1}.excludes(Set{1, 2}.select(x | x > 1))", SINGLE_ELEMENT);
    }

    @Test
    void excludingWithCollectionArgumentIsRejected() {
      assertTypeError("Set{1}.excluding(Set{1, 2}.select(x | x > 1))", SINGLE_ELEMENT);
    }

    @Test
    void countWithCollectionArgumentIsRejected() {
      assertTypeError("Set{1, 2}.count(Set{1, 2})", SINGLE_ELEMENT);
    }

    @Test
    void argumentIsTypeChecked() {
      // the argument used to be skipped entirely, so errors inside it went unreported
      assertFalse(typeErrors("Set{1}.includes(undefinedVariable)").isEmpty());
      assertFalse(typeErrors("Set{1}.excludes(undefinedVariable)").isEmpty());
      assertFalse(typeErrors("Set{1}.excluding(undefinedVariable)").isEmpty());
    }

    @Test
    void collectionArgumentIsAllowedForNestedReceiver() {
      assertTrue(typeErrors("Sequence{Set{1}}.includes(Set{1})").isEmpty());
    }

    @Test
    void membershipWithElementArgumentStillWorks() {
      assertSingleBool(compile("Set{1, 2}.includes(2)"), true);
      assertSingleBool(compile("Set{1, 2}.excludes(3)"), true);
      assertSize(compile("Set{1, 2}.excluding(2)"), 1);
    }
  }
}
