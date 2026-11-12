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
import org.junit.jupiter.params.provider.ValueSource;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.pipeline.MetamodelWrapperInterface;
import tools.vitruv.dsls.vitruvocl.pipeline.VitruvOCLCompiler;

/**
 * {@code and}, {@code or} and {@code implies} skip their right operand when the left one already
 * decides the result and the right one cannot report an error, so its (possibly expensive)
 * evaluation, here a correspondence lookup per pair, is not paid.
 */
class LogicalShortCircuitTest {

  /** The edge fixture's wrapper, counting every correspondence query. */
  private static MetamodelWrapperInterface counting(AtomicInteger lookups) {
    MetamodelWrapperInterface delegate = EdgeFixture.create().model().wrapper();
    return (MetamodelWrapperInterface)
        Proxy.newProxyInstance(
            MetamodelWrapperInterface.class.getClassLoader(),
            new Class<?>[] {MetamodelWrapperInterface.class},
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

  @org.junit.jupiter.api.Test
  void undecidedLeftOperandEvaluatesTheRightOne() {
    AtomicInteger lookups = new AtomicInteger();
    VitruvOCLCompiler compiler = new VitruvOCLCompiler(counting(lookups), null);
    compiler.compile(
        "context edge::SubA inv: self.value > 0 and edge::Other.allInstances().exists(o | o ~"
            + " self)");
    assertFalse(compiler.hasErrors(), () -> "errors: " + compiler.getErrors().getErrors());
    org.junit.jupiter.api.Assertions.assertTrue(lookups.get() > 0, "lookups are counted");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "self.value < 0 and edge::Other.allInstances().exists(o | o ~ self)",
        "self.value < 0 implies edge::Other.allInstances().exists(o | o ~ self)",
        "self.value > 0 or edge::Other.allInstances().exists(o | o ~ self)"
      })
  void decidedLeftOperandSkipsTheRightOne(String body) {
    AtomicInteger lookups = new AtomicInteger();
    VitruvOCLCompiler compiler = new VitruvOCLCompiler(counting(lookups), null);
    compiler.compile("context edge::SubA inv: " + body);
    assertFalse(compiler.hasErrors(), () -> "errors: " + compiler.getErrors().getErrors());
    org.junit.jupiter.api.Assertions.assertNotNull(compiler.getLastEvaluator(), "evaluated");
    assertEquals(0, lookups.get(), "the right operand must not be evaluated");
  }
}
