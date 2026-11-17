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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.symboltable.GlobalScope;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;
import tools.vitruv.dsls.vitruvocl.types.Type;

class EnvironmentTest {

  private final GlobalScope scope = new GlobalScope();

  private VariableSymbol symbol(String name) {
    return new VariableSymbol(name, Type.singleton(Type.INTEGER), scope, false);
  }

  private static Value v(int i) {
    return new Value(List.of(new OCLElement.IntValue(i)), Type.singleton(Type.INTEGER));
  }

  @Test
  void droppingBindingsSinceAMarkKeepsEarlierSymbolsWithTheirLatestValue() {
    Environment environment = new Environment();
    VariableSymbol self = symbol("self");
    VariableSymbol let = symbol("s");
    environment.bind(self, v(1));

    Environment.Mark mark = environment.mark();
    environment.bind(self, v(2));
    environment.bind(let, v(3));
    environment.dropBindingsSince(mark);

    assertThat(environment.valueOf(self)).isEqualTo(v(2));
    assertThat(environment.valueOf(let)).isNull();
  }

  @Test
  void aMarkCanBeReusedForEveryInstance() {
    Environment environment = new Environment();
    VariableSymbol self = symbol("self");
    environment.bind(self, v(0));
    Environment.Mark mark = environment.mark();

    for (int i = 1; i <= 3; i++) {
      VariableSymbol perInstance = symbol("s" + i);
      environment.bind(self, v(i));
      environment.bind(perInstance, v(10 * i));
      environment.dropBindingsSince(mark);

      assertThat(environment.valueOf(perInstance)).isNull();
      assertThat(environment.valueOf(self)).isEqualTo(v(i));
    }
  }
}
