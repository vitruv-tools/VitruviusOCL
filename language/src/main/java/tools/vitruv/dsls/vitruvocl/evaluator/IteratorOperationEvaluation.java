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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.lazy.LazyOperations;
import tools.vitruv.dsls.vitruvocl.evaluator.lazy.OCLElementSource;
import tools.vitruv.dsls.vitruvocl.symboltable.LocalScope;
import tools.vitruv.dsls.vitruvocl.symboltable.Scope;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;

/**
 * Runtime semantics of the iterator operations ({@code select}, {@code collect}, {@code forAll},
 * {@code iterate}, ...), including the lazy single-variable fast paths and the scope handling that
 * binds iterator variables per element. Extracted from {@link EvaluationVisitor}, which delegates
 * the corresponding {@code visit*Op} methods here.
 */
final class IteratorOperationEvaluation {

  private final OperationEvaluationContext context;

  IteratorOperationEvaluation(OperationEvaluationContext context) {
    this.context = context;
  }

  /**
   * Leaves {@code enteredScope} by re-entering its fixed parent; same contract as {@code
   * EvaluationVisitor.exitScope} (robust against current-scope drift while pulling lazy sources).
   */
  private void exitScope(Scope enteredScope) {
    context.symbolTable().enterScope(enteredScope.getEnclosingScope());
  }

  /**
   * Evaluates the {@code select()} iterator operation.
   *
   * <p>Filters the collection, keeping only elements that satisfy the predicate.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3,4}.select(x | x > 2)} → {@code Bag{3,4}}
   *
   * @param ctx The select operation node
   * @return A filtered collection
   */
  Value select(VitruvOCLParser.SelectOpContext ctx) {
    Value receiver = context.receiver();

    // Get iterator variables
    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.size() == 2) {
      return evaluateSelectTwoVars(ctx, receiver, iterVars.get(0), iterVars.get(1));
    }

    if (!iterVars.isEmpty()) {
      return evaluateSelectSingleVar(ctx, receiver, iterVars.get(0));
    }
    return Value.of(List.of(), receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code reject()} iterator operation.
   *
   * <p>Filters the collection, keeping only elements that do NOT satisfy the predicate.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3,4}.reject(x | x > 2)} → {@code Bag{1,2}}
   *
   * @param ctx The reject operation node
   * @return A filtered collection
   */
  Value reject(VitruvOCLParser.RejectOpContext ctx) {
    Value receiver = context.receiver();

    // Get iterator variables
    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.size() == 2) {
      return evaluateRejectTwoVars(ctx, receiver, iterVars.get(0), iterVars.get(1));
    }

    if (!iterVars.isEmpty()) {
      return evaluateRejectSingleVar(ctx, receiver, iterVars.get(0));
    }
    return Value.of(List.of(), receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code collect()} iterator operation.
   *
   * <p>Transforms each element in the collection using the provided expression.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3}.collect(x | x * 2)} → {@code Bag{2,4,6}}
   *
   * @param ctx The collect operation node
   * @return A new collection with transformed elements
   */
  Value collect(VitruvOCLParser.CollectOpContext ctx) {
    Value receiver = context.receiver();

    // Get iterator variables
    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.size() == 2) {
      return evaluateCollectTwoVars(ctx, receiver, iterVars.get(0), iterVars.get(1));
    }

    if (!iterVars.isEmpty()) {
      return evaluateCollectSingleVar(ctx, receiver, iterVars.get(0));
    }
    return Value.of(List.of(), receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code collectNested()} iterator operation on a collection receiver.
   *
   * <p>Projects each element of the receiver collection through the body expression and wraps each
   * result as a {@link OCLElement.NestedCollection}, preserving the collection structure of each
   * projected value rather than flattening it. This distinguishes {@code collectNested()} from
   * {@code collect()}, which flattens one level. The result is always a {@code Bag}.
   *
   * <p>Example: {@code teams->collectNested(t | t.members)}
   *
   * @param ctx the parse tree node for the {@code collectNested()} operation, including the
   *     iterator variable list and body expression
   * @return a {@code Bag} of {@link OCLElement.NestedCollection} wrappers, one per receiver
   *     element, each holding the unevaluated collection produced by the body expression; or an
   *     error value if no iterator variable is declared
   */
  Value collectNested(VitruvOCLParser.CollectNestedOpContext ctx) {
    Value receiver = context.receiver();
    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }
    if (iterVars.isEmpty()) {
      return Value.of(List.of(), Type.bag(receiver.getRuntimeType().getElementType()));
    }

    Type elemType = receiver.getRuntimeType().getElementType();
    Type iterVarType = Type.singleton(elemType);
    // iterScope MUST be constructed before touching the receiver at all - see visitSortedByOp's
    // identical comment for why (draining a lazy receiver walks its own, unrelated sibling
    // scope(s), which would otherwise get silently captured as iterScope's parent instead of the
    // correct enclosing one). Empirically confirmed as a real bug here too (reverting this
    // ordering makes LazyChainRegressionTest's shadowing test for collectNested() return
    // [-3, -2, -1] instead of [1, 2, 3]).
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVars.get(0), iterVarType, iterScope);
    // Streams the (possibly lazy) receiver via elementIterator() instead of getElements(), so a
    // preceding select()/collect() chain is pulled one element at a time instead of forced into
    // its own fully materialized ArrayList before this loop even starts.
    Iterator<OCLElement> it = receiver.elementIterator();
    List<OCLElement> results = new ArrayList<>();
    while (it.hasNext()) {
      OCLElement elem = it.next();
      // The NestedCollection wraps bodyResult for indefinite later consumption (e.g. by a
      // subsequent flatten()), well after iterSymbol has been rebound to later elements - must
      // materialize now, not defer.
      Value bodyResult =
          evalInScopeMaterialized(
              iterScope, iterSymbol, iterVarType, elem, () -> context.evaluate(ctx.body));
      results.add(new OCLElement.NestedCollection(bodyResult));
    }
    return Value.of(results, Type.bag(elemType));
  }

  /**
   * Evaluates the {@code forAll()} iterator operation.
   *
   * <p>Returns true if the predicate holds for ALL elements in the collection. Short-circuits on
   * first false.
   *
   * <p><b>Example:</b> {@code Bag{2,4,6}.forAll(x | x > 0)} → {@code true}
   *
   * @param ctx The forAll operation node
   * @return A singleton boolean value
   */
  Value forAll(VitruvOCLParser.ForAllOpContext ctx) {
    Value receiver = context.receiver();

    // Get iterator variables
    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.size() == 2) {
      return evaluateForAllTwoVars(ctx, receiver, iterVars.get(0), iterVars.get(1));
    }

    if (!iterVars.isEmpty()) {
      return evaluateForAllSingleVar(ctx, receiver, iterVars.get(0));
    }
    return Value.boolValue(true);
  }

  /**
   * Evaluates the {@code exists()} iterator operation.
   *
   * <p>Returns true if the predicate holds for AT LEAST ONE element in the collection.
   * Short-circuits on first true.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3}.exists(x | x > 2)} → {@code true}
   *
   * @param ctx The exists operation node
   * @return A singleton boolean value
   */
  Value exists(VitruvOCLParser.ExistsOpContext ctx) {
    Value receiver = context.receiver();

    // Get iterator variables
    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.size() == 2) {
      return evaluateExistsTwoVars(ctx, receiver, iterVars.get(0), iterVars.get(1));
    }

    if (!iterVars.isEmpty()) {
      return evaluateExistsSingleVar(ctx, receiver, iterVars.get(0));
    }
    return Value.boolValue(false);
  }

  /**
   * Evaluates the {@code one()} iterator operation on a collection receiver.
   *
   * <p>Iterates over all elements of the receiver collection, binding each element to the iterator
   * variable in a fresh local scope, and evaluates the body expression. Returns {@code true} if and
   * only if exactly one element satisfies the condition.
   *
   * <p>Example: {@code employees->one(e | e.age > 60)}
   *
   * @param ctx the parse tree node for the {@code one()} operation, including the iterator variable
   *     list and body expression
   * @return a singleton {@code BOOLEAN} value — {@code true} if exactly one element satisfies the
   *     body condition, {@code false} otherwise; or an error value if no iterator variable is
   *     declared
   */
  Value one(VitruvOCLParser.OneOpContext ctx) {
    Value receiver = context.receiver();
    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }
    if (iterVars.isEmpty()) {
      return Value.boolValue(false);
    }

    // Category 2 (early termination, worst case strict): pulls receiver.elementIterator()
    // instead of getElements(), and - new - stops as soon as a *second* match is found, since
    // "exactly one" is already disproven at that point. Previously this loop always ran to
    // completion (no early return at all), so even after switching to elementIterator() the
    // upstream chain would still have been fully drained; both the internal early return and the
    // streaming receiver access are needed together for the abort to actually reduce upstream
    // consumption.
    Type elemType = receiver.getRuntimeType().getElementType();
    Type iterVarType = Type.singleton(elemType);
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVars.get(0), iterVarType, iterScope);
    int count = 0;
    Iterator<OCLElement> it = receiver.elementIterator();
    while (it.hasNext()) {
      OCLElement elem = it.next();
      // evalInScope enters/exits iterScope around exactly this element's body evaluation (not
      // once around the whole loop): context.evaluate(ctx.body) may itself materialize an unrelated
      // lazy
      // Value bound elsewhere, whose own scope dance restores context.symbolTable().currentScope to
      // *its*
      // parent, not to iterScope - re-entering iterScope per element is what keeps that safe.
      Value bodyResult =
          evalInScope(iterScope, iterSymbol, iterVarType, elem, () -> context.evaluate(ctx.body));
      Boolean condition =
          bodyResult.isEmpty() ? null : bodyResult.getElements().get(0).tryGetBool();
      if (condition != null && condition) {
        count++;
        if (count == 2) {
          return Value.boolValue(false); // "exactly one" already disproven - stop early
        }
      }
    }
    return Value.boolValue(count == 1);
  }

  /**
   * Evaluates the {@code any()} iterator operation on a collection receiver.
   *
   * <p>Iterates over the elements of the receiver collection, binding each element to the iterator
   * variable in a fresh local scope, and evaluates the body expression. Returns the first element
   * that satisfies the condition as an {@code Optional} singleton, or an empty value if no element
   * matches.
   *
   * <p>Example: {@code employees->any(e | e.age > 60)}
   *
   * @param ctx the parse tree node for the {@code any()} operation, including the iterator variable
   *     list and body expression
   * @return an {@code Optional} singleton containing the first matching element, or an empty {@code
   *     Optional} if no element satisfies the body condition; or an error value if no iterator
   *     variable is declared
   */
  Value any(VitruvOCLParser.AnyOpContext ctx) {
    Value receiver = context.receiver();
    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }
    if (iterVars.isEmpty()) {
      return Value.empty(Type.optional(Type.ANY));
    }

    // Category 2 (early termination, worst case strict): pulls receiver.elementIterator()
    // instead of getElements() so an upstream lazy chain also stops at the first match.
    Type elemType = receiver.getRuntimeType().getElementType();
    Type iterVarType = Type.singleton(elemType);
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVars.get(0), iterVarType, iterScope);
    Iterator<OCLElement> it = receiver.elementIterator();
    while (it.hasNext()) {
      OCLElement elem = it.next();
      Value bodyResult =
          evalInScope(iterScope, iterSymbol, iterVarType, elem, () -> context.evaluate(ctx.body));
      Boolean condition =
          bodyResult.isEmpty() ? null : bodyResult.getElements().get(0).tryGetBool();
      if (condition != null && condition) {
        return Value.singleton(elem, Type.optional(elemType));
      }
    }
    return Value.empty(Type.singleton(elemType));
  }

  /**
   * Evaluates the {@code isUnique()} iterator operation on a collection receiver.
   *
   * <p>Iterates over all elements of the receiver collection, binding each element to the iterator
   * variable in a fresh local scope, and evaluates the body expression. Returns {@code true} if and
   * only if all body results are mutually distinct, using {@link OCLElement#semanticEquals} for
   * value equality.
   *
   * <p>Example: {@code employees->isUnique(e | e.employeeId)}
   *
   * @param ctx the parse tree node for the {@code isUnique()} operation, including the iterator
   *     variable list and body expression
   * @return a singleton {@code BOOLEAN} value — {@code true} if all projected values are distinct,
   *     {@code false} as soon as a duplicate is detected; or an error value if no iterator variable
   *     is declared
   */
  @SuppressWarnings("java:S3776")
  Value isUnique(VitruvOCLParser.IsUniqueOpContext ctx) {
    Value receiver = context.receiver();
    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }
    if (iterVars.isEmpty()) {
      return Value.boolValue(true);
    }

    // Category 2 (early termination, worst case strict): pulls receiver.elementIterator()
    // instead of getElements() so an upstream lazy chain also stops as soon as the first
    // duplicate is found - the internal early return below was already present, but was
    // previously moot: getElements() forced the whole upstream chain to materialize before this
    // loop (and its early return) ever ran.
    Type elemType = receiver.getRuntimeType().getElementType();
    Type iterVarType = Type.singleton(elemType);
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVars.get(0), iterVarType, iterScope);
    // Singleton body results (the common case, e.g. isUnique(p | p.name)) are tracked in a hash
    // index: O(n) instead of O(n^2). Empty and multi-element results keep the pairwise scan.
    LazyOperations.HashedBuffer seenSingletons = LazyOperations.newSemanticBuffer();
    boolean seenEmpty = false;
    List<Value> seen = new ArrayList<>();
    Iterator<OCLElement> it = receiver.elementIterator();
    while (it.hasNext()) {
      OCLElement elem = it.next();
      Value bodyResult =
          evalInScopeMaterialized(
              iterScope, iterSymbol, iterVarType, elem, () -> context.evaluate(ctx.body));
      List<OCLElement> resultElements = bodyResult.getElements();
      if (resultElements.size() <= 1) {
        boolean duplicate =
            resultElements.isEmpty() ? seenEmpty : seenSingletons.contains(resultElements.get(0));
        if (duplicate) {
          return Value.boolValue(false);
        }
        if (resultElements.isEmpty()) {
          seenEmpty = true;
        } else {
          seenSingletons.add(resultElements.get(0));
        }
        continue;
      }
      for (Value s : seen) {
        if (s.getElements().size() == bodyResult.getElements().size()) {
          boolean allEqual = true;
          for (int i = 0; i < s.getElements().size(); i++) {
            if (!OCLElement.semanticEquals(
                s.getElements().get(i), bodyResult.getElements().get(i))) {
              allEqual = false;
              break;
            }
          }
          if (allEqual) {
            return Value.boolValue(false);
          }
        }
      }
      seen.add(bodyResult);
    }
    return Value.boolValue(true);
  }

  /**
   * Evaluates the {@code sortedBy()} iterator operation on a collection receiver.
   *
   * <p>Projects each element of the receiver collection through the body expression to obtain a
   * sort key, then returns the elements in ascending key order. Key comparison uses {@link
   * OCLElement#compareRelational}. The result is a {@code Sequence} for Bag/Sequence receivers
   * (duplicates are kept) and an {@code OrderedSet} otherwise, see {@link Type#sortedByResult}.
   *
   * <p>Example: {@code employees->sortedBy(e | e.lastName)}
   *
   * @param ctx the parse tree node for the {@code sortedBy()} operation, including the iterator
   *     variable list and body expression
   * @return a {@code Sequence} or {@code OrderedSet} containing all receiver elements sorted in
   *     ascending order of their projected key values; or an empty value if no iterator variable is
   *     declared
   */
  Value sortedBy(VitruvOCLParser.SortedByOpContext ctx) {
    Value receiver = context.receiver();
    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }
    if (iterVars.isEmpty()) {
      return Value.of(List.of(), Type.sortedByResult(receiver.getRuntimeType()));
    }

    Type elemType = receiver.getRuntimeType().getElementType();
    Type iterVarType = Type.singleton(elemType);

    // iterScope MUST be constructed before touching the receiver at all - not just before this
    // operation's own loop body runs. Draining a lazy receiver (e.g. a preceding select()) walks
    // *its own* predicate scope(s), parented off wherever that select() was originally evaluated -
    // an entirely different (sibling) scope from the one active right here. Each such predicate
    // call correctly restores context.symbolTable()'s current scope to *its own* parent when it
    // finishes,
    // not back to whatever was active before the drain started here. So if `new
    // LocalScope(context.symbolTable().getCurrentScope())` ran *after* draining, it would silently
    // capture
    // that unrelated sibling scope as iterScope's parent instead of the correct enclosing one -
    // this was a real, reproducible bug (empirically confirmed by reverting this exact ordering
    // and re-running LazyChainRegressionTest's shadowing test for sortedBy(), which then failed
    // with the exact same wrong result as originally discovered), not just a theoretical concern.
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVars.get(0), iterVarType, iterScope);

    // Streams the (possibly lazy) receiver via elementIterator() into exactly one local list,
    // instead of receiver.getElements() (which would materialize-and-cache its own internal copy
    // via Value.materialize()) followed by a *second*, separate new ArrayList<>(...) copy of that
    // same data. sortedBy() needs its own mutable, randomly-addressable list either way (for the
    // key-based index sort below) - draining straight from elementIterator() into that one list
    // avoids allocating the receiver's own internal cache array for data nobody else will ever
    // read back through `receiver` again.
    List<OCLElement> elements = new ArrayList<>();
    receiver.elementIterator().forEachRemaining(elements::add);
    List<Value> keys = new ArrayList<>();

    for (OCLElement elem : elements) {
      // Keys are compared later, outside this loop, after iterSymbol has been rebound many more
      // times - must materialize now, not defer.
      keys.add(
          evalInScopeMaterialized(
              iterScope, iterSymbol, iterVarType, elem, () -> context.evaluate(ctx.body)));
    }

    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < elements.size(); i++) {
      indices.add(i);
    }
    // An empty key (e.g. from 6.div(0)) is null here and sorts before every other key
    indices.sort(
        (a, b) -> OCLElement.compareRelational(firstOrNull(keys.get(a)), firstOrNull(keys.get(b))));

    List<OCLElement> sorted = new ArrayList<>();
    for (int i : indices) {
      sorted.add(elements.get(i));
    }
    return Value.of(sorted, Type.sortedByResult(receiver.getRuntimeType()));
  }

  /**
   * Evaluates the {@code iterate()} operation on a collection receiver.
   *
   * <p>The most general collection iterator, from which all other iterator operations can be
   * derived. Initializes an accumulator variable to a given seed value, then iterates over each
   * element of the receiver collection, binding it to the iterator variable and evaluating the body
   * expression. The body result becomes the new accumulator value after each step. Returns the
   * final accumulator value.
   *
   * <p>Example: {@code numbers->iterate(n; acc : Integer = 0 | acc + n)}
   *
   * @param ctx the parse tree node for the {@code iterate()} operation, including the {@link
   *     VitruvOCLParser.IterateVarSpecContext} with iterator variable, accumulator variable,
   *     initializer expression, and body
   * @return the final value of the accumulator after all elements have been processed
   */
  Value iterate(VitruvOCLParser.IterateOpContext ctx) {
    Value receiver = context.receiver();
    VitruvOCLParser.IterateVarSpecContext varSpec = ctx.iterateVarSpec();

    Type elemType = receiver.getRuntimeType().getElementType();
    Type iterVarType = Type.singleton(elemType);

    Value accValue = context.evaluate(varSpec.accInit);

    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol accSymbol =
        new VariableSymbol(varSpec.accVar.getText(), accValue.getRuntimeType(), iterScope, false);
    iterScope.defineVariable(accSymbol);
    accSymbol.setValue(accValue);
    VariableSymbol iterSymbol = defineIterVar(varSpec.iterVar.getText(), iterVarType, iterScope);

    // Streams the (possibly lazy) receiver via elementIterator() instead of forcing it into one
    // fully materialized List up front: a preceding select()->collect() chain is pulled one
    // element at a time instead of copied into its own intermediate ArrayList before iterate()
    // even starts. This is safe because evalInScope unconditionally re-enters iterScope as its
    // very first action - regardless of what currentScope drifted to while pulling the next
    // element from an upstream chain with its own, unrelated scope (e.g. a preceding select()'s
    // predicate scope, parented off wherever the receiver was originally evaluated, not off
    // iterScope) - so nothing here ever depends on currentScope already being correct going into
    // an iteration step. The accumulator itself is still forced to materialize each step (see
    // evalInScopeMaterialized) - iterate() remains structurally strict, only its source streams.
    //
    // Verified empirically in both directions (see the LazyChainRegressionTest of an iterate body
    // that resolves a shadowed outer variable on every step while pulling from a lazy select):
    // streaming via elementIterator() and the previous getElements()-based materialization produce
    // identical results for a targeted shadowing/gap construction - this change fixes no
    // correctness bug (there wasn't one), it is a pure memory/streaming optimization.
    VitruvOCLParser.IncludingOpContext including =
        varSpec.iterVar.getText().equals(varSpec.accVar.getText())
            ? null
            : accumulatingIncluding(ctx.body, varSpec.accVar.getText());
    if (including != null) {
      return iterateIncluding(
          receiver, including, accValue, iterScope, iterSymbol, iterVarType);
    }

    Iterator<OCLElement> it = receiver.elementIterator();
    while (it.hasNext()) {
      OCLElement elem = it.next();
      // The accumulator carries into the *next* iteration's body evaluation (e.g. `acc + n`),
      // which rebinds iterSymbol before resolving it - must materialize now, not defer.
      Value bodyResult =
          evalInScopeMaterialized(
              iterScope, iterSymbol, iterVarType, elem, () -> context.evaluate(ctx.body));
      accSymbol.setValue(bodyResult);
    }
    return accSymbol.getValue();
  }

  /**
   * The {@code including} operation if {@code body} is exactly {@code acc.including(e)} and
   * {@code e} does not mention {@code acc}; otherwise {@code null}. Such an iterate only appends to
   * its accumulator, which {@link #iterateIncluding} does in linear instead of quadratic time.
   */
  private static VitruvOCLParser.IncludingOpContext accumulatingIncluding(
      ParserRuleContext body, String accName) {
    VitruvOCLParser.PrimaryWithNavContext primary = soleDescendant(body);
    if (primary == null
        || !primary.getText().equals(body.getText())
        || primary.base == null
        || !primary.base.getText().equals(accName)
        || primary.navigationChainCS().size() != 1) {
      return null;
    }
    VitruvOCLParser.IncludingOpContext including =
        firstOfType(primary.navigationChainCS(0), VitruvOCLParser.IncludingOpContext.class);
    if (including == null
        || !primary.navigationChainCS(0).getText().equals("." + including.getText())
        || mentions(including.arg, accName)) {
      return null;
    }
    return including;
  }

  /** The outermost {@code PrimaryWithNav} node of {@code node}, following single-child chains. */
  private static VitruvOCLParser.PrimaryWithNavContext soleDescendant(ParseTree node) {
    ParseTree current = node;
    while (current != null) {
      if (current instanceof VitruvOCLParser.PrimaryWithNavContext primary) {
        return primary;
      }
      current = current.getChildCount() == 1 ? current.getChild(0) : null;
    }
    return null;
  }

  private static <T extends ParseTree> T firstOfType(ParseTree node, Class<T> type) {
    if (type.isInstance(node)) {
      return type.cast(node);
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      T found = firstOfType(node.getChild(i), type);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** Whether {@code node} contains a variable reference (or any token) named {@code name}. */
  private static boolean mentions(ParseTree node, String name) {
    if (node instanceof TerminalNode terminal) {
      return terminal.getText().equals(name);
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      if (mentions(node.getChild(i), name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Evaluates {@code iterate(x; acc = init | acc.including(e))} by appending each {@code e} to one
   * growing list, deduplicated through a hash index for unique accumulator kinds: the same result
   * as re-evaluating {@code acc.including(e)} (a copy of the whole accumulator) on every step.
   */
  private Value iterateIncluding(
      Value receiver,
      VitruvOCLParser.IncludingOpContext including,
      Value accValue,
      LocalScope iterScope,
      VariableSymbol iterSymbol,
      Type iterVarType) {
    Type accType = accValue.getRuntimeType();
    boolean unique = accType.isUnique();
    List<OCLElement> elements = new ArrayList<>();
    LazyOperations.HashedBuffer seen = LazyOperations.newSemanticBuffer();
    for (OCLElement initial : accValue.getElements()) {
      if (!unique) {
        elements.add(initial);
      } else if (!seen.contains(initial)) {
        seen.add(initial);
        elements.add(initial);
      }
    }
    Iterator<OCLElement> it = receiver.elementIterator();
    while (it.hasNext()) {
      Value added =
          evalInScopeMaterialized(
              iterScope, iterSymbol, iterVarType, it.next(), () -> context.evaluate(including.arg));
      if (added.isEmpty()) {
        continue; // including(<empty>) leaves the accumulator unchanged
      }
      OCLElement element = added.getElements().get(0);
      if (!unique) {
        elements.add(element);
      } else if (!seen.contains(element)) {
        seen.add(element);
        elements.add(element);
      }
    }
    return Value.of(elements, accType);
  }

  /**
   * Creates and registers a fresh iterator-variable symbol directly in {@code scope}'s own symbol
   * map (bypassing {@code context.symbolTable().defineVariable}, which would require {@code scope}
   * to already be the active current scope). Call this exactly once per iteration expression, then
   * use {@link #rebindIterVar} to update the same symbol's value for each subsequent element —
   * allocating a fresh {@link VariableSymbol} (and re-inserting it into the scope's map) per
   * element is pure waste, since the identical name/scope/type is being re-registered every time.
   *
   * @param name the iterator variable's name
   * @param iterVarType the singleton type the variable is bound at
   * @param scope the scope to register the symbol in
   * @return the newly registered, as-yet-unbound symbol
   */
  private VariableSymbol defineIterVar(String name, Type iterVarType, LocalScope scope) {
    VariableSymbol symbol = new VariableSymbol(name, iterVarType, scope, true);
    scope.defineVariable(symbol);
    return symbol;
  }

  /**
   * Creates and registers both iterator-variable symbols of a two-var (Cartesian product) iteration
   * once; use {@link #rebindTwoVars} to update their values for each subsequent pair.
   */
  private VariableSymbol[] defineTwoVars(
      String var1, String var2, Type iterVarType, LocalScope scope) {
    return new VariableSymbol[] {
      defineIterVar(var1, iterVarType, scope), defineIterVar(var2, iterVarType, scope)
    };
  }

  /** Evaluates {@code body} and extracts its result as a boolean predicate (default false). */
  private boolean evalBooleanBody(ParserRuleContext body) {
    Value bodyResult = context.evaluate(body);
    Boolean condition =
        bodyResult.isEmpty() ? Boolean.FALSE : bodyResult.getElements().get(0).tryGetBool();
    return condition != null && condition;
  }

  /**
   * Evaluates {@code body} with {@code iterSymbol} rebound to {@code elem} in {@code scope}.
   *
   * <p>Scope entry/exit is bracketed around exactly this one element's evaluation (not around a
   * whole enclosing loop), so that a lazy consumer which stops pulling early (e.g. {@code exists})
   * never leaves a dangling entered scope: each call is self-contained regardless of how many
   * elements, if any, are pulled afterwards. {@code enterScope}/{@code exitScope} are a single
   * field assignment each in this codebase's {@code SymbolTableImpl}, so bracketing them per
   * element is cheap; {@code iterSymbol} itself is allocated once by the caller (via {@link
   * #defineIterVar}) and only rebound here, avoiding a {@link VariableSymbol} allocation per
   * element.
   *
   * @param scope the iterator variable's scope; may be reused across multiple elements/traversals —
   *     see {@link tools.vitruv.dsls.vitruvocl.evaluator.lazy.LazyOperations} for why this is safe
   *     with lazy chains (each element's full evaluation, including any nested lazy sub-chains,
   *     completes before the next element is pulled)
   * @param iterSymbol the already-registered symbol to rebind (from {@link #defineIterVar})
   */
  private <T> T evalInScope(
      LocalScope scope,
      VariableSymbol iterSymbol,
      Type iterVarType,
      OCLElement elem,
      Supplier<T> body) {
    context.symbolTable().enterScope(scope);
    try {
      rebindIterVar(iterSymbol, iterVarType, elem);
      return body.get();
    } finally {
      exitScope(scope);
    }
  }

  /**
   * Like {@link #evalInScope}, but additionally forces the resulting {@link Value} to materialize
   * (and cache) before returning, while {@code scope} is still correctly bound to {@code elem}.
   *
   * <p>Use this whenever the result will be <em>stored</em> and consumed later — after the loop, or
   * during a later iteration that has since rebound the same iterator-variable symbol — rather than
   * drained immediately within the same element's evaluation. A stored, still-lazy {@link Value}
   * would otherwise resolve its iterator variable against whatever {@code iterSymbol} happens to be
   * bound to at the time it is eventually consumed, not the element it was actually computed for.
   * (This is safe to skip when the result is consumed synchronously and fully within the current
   * element's evaluation — e.g. {@code select}/{@code reject}'s predicates reduce to a plain {@code
   * boolean} immediately, and {@code collect}'s flatMap fully drains one element's expansion before
   * the next upstream pull rebinds the symbol.)
   */
  private Value evalInScopeMaterialized(
      LocalScope scope,
      VariableSymbol iterSymbol,
      Type iterVarType,
      OCLElement elem,
      Supplier<Value> body) {
    Value result = evalInScope(scope, iterSymbol, iterVarType, elem, body);
    result.getElements(); // force + cache materialization now, before the symbol is rebound again
    return result;
  }

  /**
   * Evaluates collect operation with a single iterator variable.
   *
   * <p>Iterator variable is bound as singleton ¡T! per semantics.
   *
   * @param ctx Parser context for collect operation
   * @param receiver Collection to transform
   * @param iterVar Name of iterator variable to bind
   * @return Flattened collection of all transformation results
   */
  private Value evaluateCollectSingleVar(
      VitruvOCLParser.CollectOpContext ctx, Value receiver, String iterVar) {
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVar, iterVarType, iterScope);
    Function<OCLElement, Iterator<OCLElement>> expand =
        elem ->
            evalInScope(iterScope, iterSymbol, iterVarType, elem, () -> context.evaluate(ctx.body))
                .elementIterator();
    OCLElementSource mapped = LazyOperations.flatMap(receiver.asSource(), expand);
    Type resultType = context.nodeType(ctx);
    return strictIfErrorProne(
        Value.lazy(mapped, resultType != null ? resultType : Type.set(Type.ANY)), ctx.body);
  }

  /**
   * Evaluates collect operation with two iterator variables (Cartesian product).
   *
   * <p>Iterator variables are bound as singleton ¡T! per semantics.
   *
   * @param ctx Parser context for collect operation
   * @param receiver Collection to iterate over
   * @param var1 Name of first iterator variable
   * @param var2 Name of second iterator variable
   * @return Flattened collection of all transformation results from each pair
   */
  private Value evaluateCollectTwoVars(
      VitruvOCLParser.CollectOpContext ctx, Value receiver, String var1, String var2) {
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    // iterScope MUST be constructed before touching the receiver - see evaluateForAllTwoVars's
    // comment for why (empirically confirmed as a real bug when this ordering was wrong).
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    List<OCLElement> elements = receiver.getElements();
    context.symbolTable().enterScope(iterScope);
    try {
      VariableSymbol[] symbols = defineTwoVars(var1, var2, iterVarType, iterScope);
      List<OCLElement> results = new ArrayList<>();
      for (OCLElement elem1 : elements) {
        for (OCLElement elem2 : elements) {
          // Re-enter defensively before each pair - see EvaluationVisitor#exitScope(Scope).
          context.symbolTable().enterScope(iterScope);
          rebindTwoVars(symbols, iterVarType, elem1, elem2);
          results.addAll(context.evaluate(ctx.body).getElements());
        }
      }
      Type resultType = context.nodeType(ctx);
      return Value.of(results, resultType != null ? resultType : Type.set(Type.ANY));
    } finally {
      exitScope(iterScope);
    }
  }

  /**
   * Evaluates exists operation with a single iterator variable.
   *
   * <p>Iterator variable is bound as singleton ¡T!.
   *
   * @param ctx Parser context for exists operation
   * @param receiver Collection to iterate over
   * @param iterVar Name of iterator variable to bind
   * @return Singleton Boolean: true if any element satisfies predicate, false if none do
   */
  private Value evaluateExistsSingleVar(
      VitruvOCLParser.ExistsOpContext ctx, Value receiver, String iterVar) {
    // Category 2 (early termination, worst case strict): see evaluateForAllSingleVar.
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVar, iterVarType, iterScope);
    Iterator<OCLElement> it = receiver.elementIterator();
    while (it.hasNext()) {
      OCLElement elem = it.next();
      if (evalInScope(iterScope, iterSymbol, iterVarType, elem, () -> evalBooleanBody(ctx.body))) {
        return Value.boolValue(true); // short-circuit on first true result
      }
    }
    return Value.boolValue(false);
  }

  /**
   * Evaluates exists operation with two iterator variables (Cartesian product).
   *
   * <p>Iterator variables are bound as singleton ¡T!.
   *
   * @param ctx Parser context for exists operation
   * @param receiver Collection to iterate over
   * @param var1 Name of first iterator variable
   * @param var2 Name of second iterator variable
   * @return Singleton Boolean: true if any pair satisfies predicate, false if no pairs do
   */
  private Value evaluateExistsTwoVars(
      VitruvOCLParser.ExistsOpContext ctx, Value receiver, String var1, String var2) {
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    // iterScope MUST be constructed before touching the receiver - see evaluateForAllTwoVars's
    // comment for why (empirically confirmed as a real bug when this ordering was wrong).
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    List<OCLElement> elements = receiver.getElements();
    context.symbolTable().enterScope(iterScope);
    try {
      VariableSymbol[] symbols = defineTwoVars(var1, var2, iterVarType, iterScope);
      for (OCLElement elem1 : elements) {
        for (OCLElement elem2 : elements) {
          // Re-enter defensively before each pair - see EvaluationVisitor#exitScope(Scope).
          context.symbolTable().enterScope(iterScope);
          rebindTwoVars(symbols, iterVarType, elem1, elem2);
          if (evalBooleanBody(ctx.body)) {
            return Value.boolValue(true); // short-circuit on first true result
          }
        }
      }
      return Value.boolValue(false);
    } finally {
      exitScope(iterScope);
    }
  }

  /**
   * Evaluates forAll operation with a single iterator variable.
   *
   * <p>In each iterated element is a singleton ¡T!, so the iterator variable is bound with Value
   * type singleton(elemType) to match the TypeCheckVisitor's normalizeToSingleton() treatment of
   * iterator variables.
   *
   * @param ctx Parser context for forAll operation
   * @param receiver Collection to check
   * @param iterVar Name of iterator variable to bind
   * @return Singleton Boolean: true if all elements satisfy predicate, false if any fails
   */
  private Value evaluateForAllSingleVar(
      VitruvOCLParser.ForAllOpContext ctx, Value receiver, String iterVar) {
    // Category 2 (early termination, worst case strict): pulls from receiver.elementIterator()
    // instead of getElements(), so an upstream lazy chain (e.g. a preceding select()) is also
    // only consumed as far as the first `false` result requires. In the success case every
    // element must still be checked - structurally strict, matching the classification.
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVar, iterVarType, iterScope);
    Iterator<OCLElement> it = receiver.elementIterator();
    while (it.hasNext()) {
      OCLElement elem = it.next();
      if (!evalInScope(iterScope, iterSymbol, iterVarType, elem, () -> evalBooleanBody(ctx.body))) {
        return Value.boolValue(false); // short-circuit on first false result
      }
    }
    return Value.boolValue(true);
  }

  /**
   * Evaluates forAll operation with two iterator variables (Cartesian product).
   *
   * <p>Iterator variables are bound as singleton ¡T! per semantics.
   *
   * @param ctx Parser context for forAll operation
   * @param receiver Collection to iterate over
   * @param var1 Name of first iterator variable
   * @param var2 Name of second iterator variable
   * @return Singleton Boolean: true if predicate holds for all pairs, false if any pair fails
   */
  private Value evaluateForAllTwoVars(
      VitruvOCLParser.ForAllOpContext ctx, Value receiver, String var1, String var2) {
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    // iterScope MUST be constructed before touching the receiver at all, not just before this
    // operation's own scope is entered: draining a lazy receiver (e.g. a preceding select())
    // walks its own predicate scope(s), parented off wherever that select() was originally
    // evaluated - an unrelated sibling scope. Each such predicate call correctly restores
    // context.symbolTable()'s current scope to *its own* parent when done, not back to whatever was
    // active
    // before the drain started here - so constructing iterScope only *after* draining would
    // silently capture that unrelated sibling scope as its parent instead of the correct
    // enclosing one. (Empirically confirmed as a real, reproducible bug for sortedBy()/
    // collectNested() when this ordering was wrong; fixed the same way here.)
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    List<OCLElement> elements = receiver.getElements();
    context.symbolTable().enterScope(iterScope);
    try {
      VariableSymbol[] symbols = defineTwoVars(var1, var2, iterVarType, iterScope);
      for (OCLElement elem1 : elements) {
        for (OCLElement elem2 : elements) {
          // Re-enter defensively before each pair - see EvaluationVisitor#exitScope(Scope).
          context.symbolTable().enterScope(iterScope);
          rebindTwoVars(symbols, iterVarType, elem1, elem2);
          if (!evalBooleanBody(ctx.body)) {
            return Value.boolValue(false); // short-circuit on first false result
          }
        }
      }
      return Value.boolValue(true);
    } finally {
      exitScope(iterScope);
    }
  }

  /**
   * Evaluates reject operation with a single iterator variable.
   *
   * <p>Iterator variable is bound as singleton ¡T! per semantics.
   *
   * @param ctx Parser context for reject operation
   * @param receiver Collection to filter
   * @param iterVar Name of iterator variable to bind
   * @return Collection of elements NOT satisfying the predicate
   */
  private Value evaluateRejectSingleVar(
      VitruvOCLParser.RejectOpContext ctx, Value receiver, String iterVar) {
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVar, iterVarType, iterScope);
    Predicate<OCLElement> predicate =
        elem ->
            !evalInScope(iterScope, iterSymbol, iterVarType, elem, () -> evalBooleanBody(ctx.body));
    OCLElementSource filtered = LazyOperations.filter(receiver.asSource(), predicate);
    return strictIfErrorProne(Value.lazy(filtered, receiver.getRuntimeType()), ctx.body);
  }

  /**
   * Evaluates reject operation with two iterator variables (Cartesian product).
   *
   * <p>Iterator variables are bound as singleton ¡T! per semantics.
   *
   * @param ctx Parser context for reject operation
   * @param receiver Collection to iterate over
   * @param var1 Name of first iterator variable
   * @param var2 Name of second iterator variable
   * @return Collection containing both elements of each non-matching pair
   */
  private Value evaluateRejectTwoVars(
      VitruvOCLParser.RejectOpContext ctx, Value receiver, String var1, String var2) {
    Type elemType = receiver.getRuntimeType().getElementType();
    Type iterVarType = Type.singleton(elemType);
    // iterScope MUST be constructed before touching the receiver - see evaluateForAllTwoVars's
    // comment for why (empirically confirmed as a real bug when this ordering was wrong).
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    List<OCLElement> elements = receiver.getElements();
    context.symbolTable().enterScope(iterScope);

    try {
      VariableSymbol[] symbols = defineTwoVars(var1, var2, iterVarType, iterScope);
      List<OCLElement> results = new ArrayList<>();
      for (OCLElement elem1 : elements) {
        for (OCLElement elem2 : elements) {
          // Re-enter defensively before each pair - see EvaluationVisitor#exitScope(Scope).
          context.symbolTable().enterScope(iterScope);
          rebindTwoVars(symbols, iterVarType, elem1, elem2);
          if (!evalBooleanBody(ctx.body)) {
            results.add(elem1);
            results.add(elem2);
          }
        }
      }
      return Value.of(results, receiver.getRuntimeType());
    } finally {
      exitScope(iterScope);
    }
  }

  /**
   * Evaluates select operation with a single iterator variable.
   *
   * <p>Iterator variable is bound as singleton ¡T! per semantics.
   *
   * @param ctx Parser context for select operation
   * @param receiver Collection to filter
   * @param iterVar Name of iterator variable to bind
   * @return Collection of elements satisfying the predicate
   */
  private Value evaluateSelectSingleVar(
      VitruvOCLParser.SelectOpContext ctx, Value receiver, String iterVar) {
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    VariableSymbol iterSymbol = defineIterVar(iterVar, iterVarType, iterScope);
    Predicate<OCLElement> predicate =
        elem ->
            evalInScope(iterScope, iterSymbol, iterVarType, elem, () -> evalBooleanBody(ctx.body));
    OCLElementSource filtered = LazyOperations.filter(receiver.asSource(), predicate);
    return strictIfErrorProne(Value.lazy(filtered, receiver.getRuntimeType()), ctx.body);
  }

  /**
   * Evaluates select operation with two iterator variables (Cartesian product).
   *
   * <p>Iterator variables are bound as singleton ¡T! per semantics.
   *
   * @param ctx Parser context for select operation
   * @param receiver Collection to iterate over
   * @param var1 Name of first iterator variable
   * @param var2 Name of second iterator variable
   * @return Collection containing both elements of each matching pair
   */
  private Value evaluateSelectTwoVars(
      VitruvOCLParser.SelectOpContext ctx, Value receiver, String var1, String var2) {
    Type iterVarType = Type.singleton(receiver.getRuntimeType().getElementType());
    // iterScope MUST be constructed before touching the receiver - see evaluateForAllTwoVars's
    // comment for why (empirically confirmed as a real bug when this ordering was wrong).
    LocalScope iterScope = new LocalScope(context.symbolTable().getCurrentScope());
    List<OCLElement> elements = receiver.getElements();
    context.symbolTable().enterScope(iterScope);
    try {
      VariableSymbol[] symbols = defineTwoVars(var1, var2, iterVarType, iterScope);
      List<OCLElement> results = new ArrayList<>();
      for (OCLElement elem1 : elements) {
        for (OCLElement elem2 : elements) {
          // Re-enter defensively before each pair - see EvaluationVisitor#exitScope(Scope).
          context.symbolTable().enterScope(iterScope);
          rebindTwoVars(symbols, iterVarType, elem1, elem2);
          if (evalBooleanBody(ctx.body)) {
            results.add(elem1);
            results.add(elem2);
          }
        }
      }
      return Value.of(results, receiver.getRuntimeType());
    } finally {
      exitScope(iterScope);
    }
  }

  private static OCLElement firstOrNull(Value value) {
    List<OCLElement> elements = value.getElements();
    return elements.isEmpty() ? null : elements.get(0);
  }

  /**
   * Rebinds an already-registered iterator-variable symbol (from {@link #defineIterVar}) to {@code
   * elem}, without reallocating the symbol or touching its scope's variable map again.
   */
  private static void rebindIterVar(VariableSymbol symbol, Type iterVarType, OCLElement elem) {
    symbol.setValue(Value.singleton(elem, iterVarType));
  }

  /**
   * Rebinds both iterator-variable symbols of a two-var iteration (from {@link #defineTwoVars}).
   */
  private static void rebindTwoVars(
      VariableSymbol[] symbols, Type iterVarType, OCLElement elem1, OCLElement elem2) {
    rebindIterVar(symbols[0], iterVarType, elem1);
    rebindIterVar(symbols[1], iterVarType, elem2);
  }

  /**
   * {@code lazyResult} (of select, reject or collect), fully evaluated now if {@code body} may
   * report a runtime error: a consumer that stops early (e.g. {@code notEmpty()}) would otherwise
   * skip elements whose body reports an error in eager evaluation.
   */
  private Value strictIfErrorProne(Value lazyResult, ParserRuleContext body) {
    if (body != null && context.mayReportErrors(body)) {
      lazyResult.getElements();
    }
    return lazyResult;
  }
}
