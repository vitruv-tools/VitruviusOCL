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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.compiler.VitruvOCLCompiler;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;

/**
 * Which kinds of parse-tree nodes the evaluator trusts not to report an error, observed through the
 * logical short circuit: the right operand of {@code self.value < 0 and R} is skipped only if
 * nothing in it (or in a {@code let} initializer of the constraint) can report one. {@code R}
 * always contains a correspondence lookup, so a skipped operand leaves the lookup counter at 0.
 */
class ErrorFreeAnalysisTest {

  private static final String LOOKUP = "edge::Other.allInstances().exists(o | o ~ self)";

  /** The edge fixture's wrapper, counting every correspondence query. */
  private static ModelAccess counting(AtomicInteger lookups) {
    ModelAccess delegate = EdgeFixture.create().model().wrapper();
    return (ModelAccess)
        Proxy.newProxyInstance(
            ModelAccess.class.getClassLoader(),
            new Class<?>[] {ModelAccess.class},
            (proxy, method, args) -> {
              if (method.getName().toLowerCase().contains("correspond")) {
                lookups.incrementAndGet();
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static boolean rightOperandSkipped(String constraint) {
    AtomicInteger lookups = new AtomicInteger();
    VitruvOCLCompiler compiler = new VitruvOCLCompiler(counting(lookups), null);
    try {
      compiler.compile(constraint);
    } catch (NumberFormatException e) {
      // An integer literal outside the int range throws when it is evaluated; every such case puts
      // the literal behind the lookup (or forces it through a let), so throwing means evaluated.
      return false;
    }
    assertFalse(
        compiler.hasErrors(),
        () ->
            "errors: "
                + compiler.getErrors().getErrors().stream().map(CompileError::getMessage).toList());
    return lookups.get() == 0;
  }

  @ParameterizedTest(name = "{0} -> skipped: {1}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '`',
      textBlock =
          """
          true                                                         => true
          self.value == 5                                              => true
          self.value != 5                                              => true
          self.value <= 5                                              => true
          self.name.size() > 0                                         => true
          self.name.substring(1, 2) == "a"                             => true
          self.value < 2147483647                                      => true
          self.value < 3000000000                                      => false
          self.value > 2.5                                             => true
          self.value < 1e3                                             => true
          let k = 1 in k > 0                                           => true
          Sequence{1, 2}.includes(1)                                   => true
          self.name.matches("a.*")                                     => false
          Sequence{1}.append(2).size() > 0                             => false
          Sequence{1, 2}.iterate(i; acc: Integer = 0 | acc + i) > 0    => false
          self.next.name == "b1"                                       => true
          self.oclAsType(OclAny) == self                               => false
          """)
  void nodeKindInTheRightOperand(String expression, boolean skipped) {
    assertEquals(
        skipped,
        rightOperandSkipped(
            "context edge::SubA inv: self.value < 0 and (" + LOOKUP + " and " + expression + ")"));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '`',
      textBlock =
          """
          self.value >= 0 or (LOOKUP and self.value == 5)              => true
          self.value >= 0 or (LOOKUP and self.name.matches("a.*"))     => false
          self.value < 0 implies (LOOKUP and self.value == 5)          => true
          self.value < 0 implies (LOOKUP and self.name.matches("a.*")) => false
          """)
  void otherLogicalOperators(String body, boolean skipped) {
    assertEquals(
        skipped, rightOperandSkipped("context edge::SubA inv: " + body.replace("LOOKUP", LOOKUP)));
  }

  @ParameterizedTest(name = "{0} -> skipped: {1}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '`',
      textBlock =
          """
          self.name.matches("a.*")                                     => true
          Sequence{1}.append(2).size() > 0                             => false
          Sequence{1, 2}.iterate(i; acc: Integer = 0 | acc + i) > 0    => false
          """)
  void nodeKindElsewhereInTheConstraint(String expression, boolean skipped) {
    assertEquals(
        skipped,
        rightOperandSkipped(
            "context edge::SubA inv: (self.value < 0 and " + LOOKUP + ") or " + expression));
  }

  @ParameterizedTest(name = "let {0} -> skipped: {1}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '`',
      textBlock =
          """
          self.value > 0                                               => true
          self.name.matches("a.*")                                     => false
          self.value < 3000000000                                      => false
          """)
  void letInitializer(String initializer, boolean skipped) {
    assertEquals(
        skipped,
        rightOperandSkipped(
            "context edge::SubA inv: let m = "
                + initializer
                + " in self.value < 0 and (m or "
                + LOOKUP
                + ")"));
  }
}
