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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor;
import tools.vitruv.dsls.vitruvocl.typechecker.TypeCheckVisitor;

/**
 * Every labeled alternative of the grammar, in particular every operation, is wired into both the
 * type checker and the evaluator.
 *
 * <p>ANTLR generates a default {@code visit...} method for every labeled alternative, which only
 * visits the children. An operation added to the grammar therefore compiles without any further
 * change, but its type rule or runtime semantics silently fall back to that default. This test
 * fails instead, naming the visitor method that is missing.
 */
class OperationCoverageTest {

  /**
   * Alternatives the evaluator never visits: malformed operations that only exist for a precise
   * message of the type checker, which always rejects them, and the Type/Tag options of a
   * correspondence filter, which the evaluator reads from the tree itself.
   */
  private static final Set<Class<?>> TYPE_CHECKER_ONLY =
      Set.of(
          VitruvOCLParser.SingleEqualsOpContext.class,
          VitruvOCLParser.MultiEqualsOpContext.class,
          VitruvOCLParser.UnknownBinaryOpContext.class,
          VitruvOCLParser.IteratorMissingBodyContext.class,
          VitruvOCLParser.NoArgOpWithArgsContext.class,
          VitruvOCLParser.OpMissingParensContext.class,
          VitruvOCLParser.UnknownOperationContext.class,
          VitruvOCLParser.UnknownFilterOptionContext.class,
          VitruvOCLParser.InvalidFilterArgContext.class,
          VitruvOCLParser.CorrTypeFilterContext.class,
          VitruvOCLParser.CorrTagFilterContext.class);

  /**
   * Alternatives of {@code operationCall} that only wrap the rule of an operation family; for them
   * the generated default, which visits the single child, is exactly the evaluator's behaviour.
   */
  private static final Set<Class<?>> PASS_THROUGH_IN_EVALUATOR =
      Set.of(
          VitruvOCLParser.CollectionOperationContext.class,
          VitruvOCLParser.StringOperationContext.class,
          VitruvOCLParser.IteratorOperationContext.class,
          VitruvOCLParser.TypeOperationContext.class);

  /** The labeled alternatives of the grammar, e.g. {@code ToUpperOpContext}. */
  static Stream<Class<?>> alternatives() {
    return Arrays.stream(VitruvOCLParser.class.getDeclaredClasses())
        .filter(ParserRuleContext.class::isAssignableFrom)
        .filter(context -> context.getSuperclass() != ParserRuleContext.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("alternatives")
  void alternativeIsTypeCheckedAndEvaluated(Class<?> alternative) {
    String method = "visit" + alternative.getSimpleName().replaceFirst("Context$", "");

    assertThat(declares(TypeCheckVisitor.class, method, alternative))
        .as("TypeCheckVisitor declares %s", method)
        .isTrue();
    if (!TYPE_CHECKER_ONLY.contains(alternative)
        && !PASS_THROUGH_IN_EVALUATOR.contains(alternative)) {
      assertThat(declares(EvaluationVisitor.class, method, alternative))
          .as("EvaluationVisitor declares %s", method)
          .isTrue();
    }
  }

  @Test
  void operationsAreAmongTheAlternatives() {
    assertThat(alternatives()).contains(VitruvOCLParser.ToUpperOpContext.class);
  }

  private static boolean declares(Class<?> visitor, String method, Class<?> parameter) {
    try {
      visitor.getDeclaredMethod(method, parameter);
      return true;
    } catch (NoSuchMethodException missing) {
      return false;
    }
  }
}
