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

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.evaluator.lazy.OCLElementSource;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;
import tools.vitruv.dsls.vitruvocl.types.Type;

/** {@code first()} pulls a single element from a lazy receiver instead of draining it. */
class FirstOperationLazinessTest {

  @Test
  void firstPullsOneElement() {
    AtomicInteger pulls = new AtomicInteger();
    List<OCLElement> data =
        IntStream.range(0, 1000).mapToObj(i -> (OCLElement) new OCLElement.IntValue(i)).toList();
    OCLElementSource source =
        () -> {
          Iterator<OCLElement> inner = data.iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              return inner.hasNext();
            }

            @Override
            public OCLElement next() {
              pulls.incrementAndGet();
              return inner.next();
            }
          };
        };
    Value receiver = Value.lazy(source, Type.sequence(Type.INTEGER));

    Value first = new CollectionOperationEvaluation(contextWith(receiver)).first(null);

    assertEquals(0, first.getElements().get(0).tryGetInt());
    assertEquals(1, pulls.get(), "first() must not drain the receiver");
  }

  private static OperationEvaluationContext contextWith(Value receiver) {
    return new OperationEvaluationContext() {
      @Override
      public Value receiver() {
        return receiver;
      }

      @Override
      public Value evaluate(ParserRuleContext expression) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void error(ParserRuleContext ctx, String message) {
        throw new UnsupportedOperationException(message);
      }

      @Override
      public Type nodeType(ParserRuleContext ctx) {
        return null;
      }

      @Override
      public boolean mayReportErrors(ParserRuleContext expression) {
        return false;
      }

      @Override
      public SymbolTable symbolTable() {
        throw new UnsupportedOperationException();
      }
    };
  }
}
