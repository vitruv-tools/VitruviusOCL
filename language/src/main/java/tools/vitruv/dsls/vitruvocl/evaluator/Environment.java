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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;

/**
 * The runtime values of the variables of one evaluation: which {@link Value} each {@link
 * VariableSymbol} is currently bound to. The symbol table describes the variables; the evaluator
 * owns their values.
 *
 * <p>Bindings are keyed by symbol identity ({@code Symbol} does not override {@code equals}), so
 * same-named variables of different scopes are independent. A rebinding replaces the previous
 * value, which iterators use to bind one symbol to element after element. Lazy values read a
 * variable when they are consumed, so they see its binding at that time.
 *
 * <p>Lifetime: when the values were stored in the symbols, a binding lived as long as its symbol.
 * Two mechanisms keep that bound here:
 *
 * <ul>
 *   <li>The keys are held weakly, so the binding of a symbol that is no longer reachable (e.g. the
 *       {@code let} variable of an iterator body, created anew per element) can be collected.
 *   <li>A weak key does not help when the value itself reaches its symbol, as a partly consumed
 *       lazy {@code let} value does through its closures and scopes. The evaluator therefore drops
 *       every binding made for a context instance once that instance is done ({@link #mark}, {@link
 *       #dropBindingsSince}); nothing reads them afterwards.
 * </ul>
 *
 * <p>Not thread-safe; an evaluation runs on one thread.
 */
final class Environment {

  /** The symbols that were bound when the mark was taken. */
  static final class Mark {
    private final Set<VariableSymbol> bound;

    private Mark(Set<VariableSymbol> bound) {
      this.bound = bound;
    }
  }

  private final Map<VariableSymbol, Value> values = new WeakHashMap<>();

  /**
   * The value {@code symbol} is bound to.
   *
   * @return the value, or {@code null} if the symbol has not been bound in this evaluation
   */
  Value valueOf(VariableSymbol symbol) {
    return values.get(symbol);
  }

  /** Binds {@code symbol} to {@code value}, replacing any earlier binding. */
  void bind(VariableSymbol symbol, Value value) {
    values.put(symbol, value);
  }

  /** Remembers which symbols are bound now, for {@link #dropBindingsSince}. */
  Mark mark() {
    Set<VariableSymbol> bound = Collections.newSetFromMap(new IdentityHashMap<>());
    bound.addAll(values.keySet());
    return new Mark(bound);
  }

  /**
   * Removes the bindings of every symbol that was not bound when {@code mark} was taken. Symbols
   * bound at that time keep their current value, also if they were rebound since.
   */
  void dropBindingsSince(Mark mark) {
    values.keySet().removeIf(symbol -> !mark.bound.contains(symbol));
  }
}
