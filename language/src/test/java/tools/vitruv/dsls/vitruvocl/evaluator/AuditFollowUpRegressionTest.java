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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.DummyTestSpecification;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.evaluator.OCLElement.DoubleValue;
import tools.vitruv.dsls.vitruvocl.evaluator.OCLElement.FloatValue;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;
import tools.vitruv.dsls.vitruvocl.typechecker.TypeCheckVisitor;

/**
 * Regression tests for the remaining medium findings of the November 2026 audit: order of lazy
 * {@code intersection}, overflow in {@code sum()}, equality of nested sets, collection operands of
 * {@code ==}, EFloat versus Real equality, and the typing of numeric results, {@code collect} and
 * self-referencing initializers.
 */
class AuditFollowUpRegressionTest extends DummyTestSpecification {

  /** Result of passes 1 and 2: the reported error messages and the type of the root node. */
  private record Checked(List<String> errors, Type type) {}

  private Checked check(String input) {
    ParseTree tree = parse(input);
    ErrorCollector errors = new ErrorCollector();
    var spec = buildDummySpec();
    SymbolTable symbolTable = new SymbolTableImpl(spec);
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(symbolTable, spec, errors, scopes).visit(tree);
    TypeCheckVisitor typeChecker = new TypeCheckVisitor(symbolTable, spec, errors, scopes);
    Type type = typeChecker.visit(tree);
    return new Checked(errors.getErrors().stream().map(CompileError::getMessage).toList(), type);
  }

  private void assertTypeError(String input) {
    assertTrue(!check(input).errors().isEmpty(), "expected a type error for: " + input);
  }

  @Nested
  class LazyIntersectionOrder {

    @Test
    void keepsTheReceiverOrder() {
      assertCollection(compile("Sequence{1, 2, 3}.intersection(Sequence{3, 2, 1})"), 1, 2, 3);
    }

    @Test
    void bagIntersectionKeepsTheMinimumMultiplicity() {
      assertCollection(compile("Bag{1, 1, 2}.intersection(Bag{1, 2, 2})"), 1, 2);
    }

    @Test
    void firstIsTheFirstCommonElementOfTheReceiver() {
      assertSingleInt(compile("Sequence{1, 2, 3}.intersection(Sequence{3, 2, 1}).first()"), 1);
    }
  }

  @Nested
  class SumOverflow {

    @Test
    void integerSumWrapsLikeAddition() {
      Value plus = compile("(2147483647 + 1)");
      Value sum = compile("Sequence{2147483647, 1}.sum()");
      assertTrue(Value.semanticEquals(plus, sum), "sum " + sum + " differs from + " + plus);
    }
  }

  @Nested
  class NestedSetEquality {

    @Test
    void setsOfSetsAreEqualRegardlessOfInnerOrder() {
      assertSingleInt(
          compile("Set{Set{Set{1, 4}, Set{2, 3}}, Set{Set{4, 1}, Set{2, 3}}}.size()"), 1);
    }
  }

  @Nested
  class CollectionEqualityOperands {

    @Test
    void setEqualsSetIsATypeError() {
      assertTypeError("(Set{1, 2} == Set{1, 2})");
    }

    @Test
    void sequenceNotEqualsSequenceIsATypeError() {
      assertTypeError("(Sequence{1, 2} != Sequence{2, 1})");
    }

    @Test
    void singletonOperandsStillTypeCheck() {
      assertEquals(List.of(), check("(Set{1, 2}.size() == 2)").errors());
    }
  }

  @Nested
  class FloatEquality {

    @Test
    void floatEqualsTheMatchingRealLiteral() {
      OCLElement attribute = new FloatValue(0.1f);
      OCLElement literal = new DoubleValue(0.1);
      assertTrue(OCLElement.semanticEquals(attribute, literal));
      assertTrue(OCLElement.semanticEquals(literal, attribute));
      assertEquals(OCLElement.semanticHash(attribute), OCLElement.semanticHash(literal));
    }

    @Test
    void floatOrdersLikeTheMatchingRealLiteral() {
      OCLElement attribute = new FloatValue(0.1f);
      OCLElement literal = new DoubleValue(0.1);
      assertEquals(0, OCLElement.compare(attribute, literal));
      assertEquals(0, OCLElement.compare(literal, attribute));
    }

    @Test
    void equalityStaysTransitiveForLargeIntegers() {
      OCLElement f = new FloatValue(16777216f);
      OCLElement i1 = new OCLElement.IntValue(16777216);
      OCLElement i2 = new OCLElement.IntValue(16777217);
      assertTrue(OCLElement.semanticEquals(f, i1));
      assertTrue(!OCLElement.semanticEquals(f, i2), "16777217 is not the float 16777216");
    }
  }

  @Nested
  class NumericResultTypes {

    @Test
    void sumOfMixedNumbersIsReal() {
      assertEquals(Type.DOUBLE, check("Sequence{1, 2.5}.sum()").type().getElementType());
    }

    @Test
    void avgIsReal() {
      assertEquals(Type.DOUBLE, check("Sequence{1, 2}.avg()").type().getElementType());
    }

    @Test
    void roundingResultsAreIntegersAtRuntime() {
      assertSingleInt(compile("2.5.round()"), 3);
      assertSingleInt(compile("2.7.floor()"), 2);
      assertSingleInt(compile("2.1.ceil()"), 3);
    }

    @Test
    void roundingOutsideTheIntegerRangeIsUndefined() {
      assertSize(compile("3000000000.5.round()"), 0);
      assertSize(compile("10000000000.5.floor()"), 0);
    }

    @Test
    void roundingReturnsInteger() {
      assertEquals(Type.INTEGER, check("2.5.floor()").type().getElementType());
      assertEquals(Type.INTEGER, check("2.5.ceil()").type().getElementType());
      assertEquals(Type.INTEGER, check("2.5.round()").type().getElementType());
    }
  }

  @Nested
  class CollectTyping {

    @Test
    void collectOnASetIsABag() {
      Type type = check("Set{1, 2}.collect(x | 1)").type();
      assertEquals(Type.bag(Type.INTEGER).getCollectionKind(), type.getCollectionKind());
      assertSize(compile("Set{1, 2}.collect(x | 1)"), 2);
    }

    @Test
    void collectOnASequenceIsASequence() {
      Type type = check("OrderedSet{1, 2}.collect(x | 1)").type();
      assertEquals(Type.sequence(Type.INTEGER).getCollectionKind(), type.getCollectionKind());
    }
  }

  @Nested
  class SelfReferencingInitializers {

    @Test
    void letInitializerCannotSeeItsOwnVariable() {
      assertTypeError("let b = b in b");
    }

    @Test
    void letInitializerSeesTheVariableItShadows() {
      Checked checked = check("let x = 5 in let x = (x * 2) in x");
      assertEquals(List.of(), checked.errors());
      assertEquals(Type.INTEGER, checked.type().getElementType());
    }

    @Test
    void laterLetVariablesSeeEarlierOnes() {
      assertEquals(List.of(), check("let a = 1, b = a in b").errors());
    }

    @Test
    void iterateAccumulatorInitializerCannotSeeTheIteratorVariable() {
      assertTypeError("Set{1, 2}.iterate(x; acc = x | acc)");
    }

    @Test
    void iterateAccumulatorInitializerCannotSeeTheAccumulator() {
      assertTypeError("Set{1}.iterate(x; acc = acc | acc)");
    }
  }

  @Nested
  class OclAsTypeTyping {

    @Test
    void castBetweenUnrelatedTypesIsATypeError() {
      assertTypeError("\"a\".oclAsType(Integer)");
    }

    @Test
    void castToARelatedTypeTypeChecks() {
      assertEquals(List.of(), check("1.oclAsType(Real)").errors());
    }

    @Test
    void castToACollectionTypeKeepsTheReceiverKind() {
      // the evaluator casts the members and keeps the receiver's collection kind
      Type type = check("Sequence{1, 1}.oclAsType(Set(Real))").type();
      assertEquals(Type.sequence(Type.DOUBLE).getCollectionKind(), type.getCollectionKind());
    }

    @Test
    void castToACollectionTypeIsNotWrappedTwice() {
      Type type = check("Set{2.5}.oclAsType(Set(Real))").type();
      assertEquals(Type.DOUBLE, type.getElementType());
    }
  }

  @Nested
  class LazyFilterErrors {

    @Test
    void selectReportsAnErrorOfALaterElementLikeEagerEvaluation() {
      org.junit.jupiter.api.Assertions.assertThrows(
          AssertionError.class,
          () -> compile("Sequence{\"a\", \"(\"}.select(p | \"a\".matches(p)).notEmpty()"),
          "the invalid regular expression of the second element must be reported");
    }

    @Test
    void collectReportsAnErrorOfALaterElementLikeEagerEvaluation() {
      org.junit.jupiter.api.Assertions.assertThrows(
          AssertionError.class,
          () -> compile("Sequence{\"a\", \"(\"}.collect(p | \"a\".matches(p)).includes(true)"));
    }

    @Test
    void rejectReportsAnErrorOfALaterElementLikeEagerEvaluation() {
      org.junit.jupiter.api.Assertions.assertThrows(
          AssertionError.class,
          () -> compile("Sequence{\"b\", \"(\"}.reject(p | \"a\".matches(p)).notEmpty()"));
    }
  }
}
