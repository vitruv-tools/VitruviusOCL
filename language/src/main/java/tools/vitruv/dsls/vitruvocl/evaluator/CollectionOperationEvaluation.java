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
import java.util.Collections;
import java.util.List;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.lazy.LazyOperations;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;

/**
 * Runtime semantics of the collection operations: queries ({@code includes}, {@code size}, ...),
 * modifications ({@code including}, {@code union}, ...), conversions ({@code asSet}, ...) and
 * aggregates ({@code sum}, {@code max}, ...). Extracted from {@link EvaluationVisitor}, which
 * delegates the corresponding {@code visit*Op} methods here.
 */
final class CollectionOperationEvaluation {

  private final OperationEvaluationContext context;

  CollectionOperationEvaluation(OperationEvaluationContext context) {
    this.context = context;
  }

  /**
   * Evaluates the {@code includes()} operation.
   *
   * <p>Returns true if the receiver collection contains an element semantically equal to the
   * argument.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3}.includes(2)} → {@code true}
   *
   * @param ctx The includes operation node
   * @return A singleton boolean value
   */
  Value includes(VitruvOCLParser.IncludesOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return Value.boolValue(false);
    }

    OCLElement searchElem = arg.getElements().get(0);
    boolean result = receiver.includes(searchElem);

    return Value.boolValue(result);
  }

  /**
   * Evaluates the {@code excludes()} operation.
   *
   * <p>Returns true if the receiver collection does NOT contain the argument element.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3}.excludes(4)} → {@code true}
   *
   * @param ctx The excludes operation node
   * @return A singleton boolean value
   */
  Value excludes(VitruvOCLParser.ExcludesOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return Value.boolValue(true);
    }
    return Value.boolValue(receiver.excludes(arg.getElements().get(0)));
  }

  /**
   * Evaluates the {@code includesAll()} operation on a collection receiver.
   *
   * <p>Checks whether the receiver collection contains every element of the argument collection,
   * using {@link Value#includes} for element membership tests.
   *
   * <p>Example: {@code bag->includesAll(Set{1, 2, 3})}
   *
   * @param ctx the parse tree node for the {@code includesAll()} operation, including the argument
   *     expression
   * @return a singleton {@code BOOLEAN} value — {@code true} if every element of the argument
   *     collection is contained in the receiver, {@code false} as soon as any element is missing
   */
  Value includesAll(VitruvOCLParser.IncludesAllOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    return Value.boolValue(receiver.includesAll(arg));
  }

  /**
   * Evaluates the {@code excludesAll()} operation on a collection receiver.
   *
   * <p>Checks whether the receiver collection contains none of the elements of the argument
   * collection, using {@link Value#includes} for membership tests.
   *
   * <p>Example: {@code bag->excludesAll(Set{1, 2, 3})}
   *
   * @param ctx the parse tree node for the {@code excludesAll()} operation, including the argument
   *     expression
   * @return a singleton {@code BOOLEAN} value — {@code true} if none of the argument's elements are
   *     contained in the receiver, {@code false} as soon as any argument element is found in the
   *     receiver
   */
  Value excludesAll(VitruvOCLParser.ExcludesAllOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    return Value.boolValue(receiver.excludesAll(arg));
  }

  /**
   * Evaluates the {@code count()} operation on a collection receiver.
   *
   * <p>Counts how many times the given singleton argument occurs in the receiver collection, using
   * {@link OCLElement#semanticEquals} for element equality.
   *
   * <p>Example: {@code Bag{1, 2, 2, 3}->count(2)}
   *
   * @param ctx the parse tree node for the {@code count()} operation, including the argument
   *     expression
   * @return a singleton {@code INTEGER} value representing the number of occurrences of the
   *     argument in the receiver collection; or an error value if the argument is not a singleton
   */
  Value count(VitruvOCLParser.CountOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return Value.intValue(0);
    }
    OCLElement searchElem = arg.getElements().get(0);
    int count = 0;
    for (OCLElement elem : receiver.getElements()) {
      if (OCLElement.semanticEquals(elem, searchElem)) {
        count++;
      }
    }
    return Value.intValue(count);
  }

  /**
   * Evaluates the {@code size()} operation.
   *
   * <p>Returns the number of elements in the receiver collection.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3}.size()} → {@code 3}
   *
   * @param ctx The size operation node
   * @return A singleton integer value
   */
  Value size(VitruvOCLParser.SizeOpContext ctx) {
    Value receiver = context.receiver();
    return Value.intValue(receiver.size());
  }

  /**
   * Evaluates the {@code isEmpty()} operation.
   *
   * <p>Returns true if the receiver collection contains zero elements.
   *
   * <p><b>Example:</b> {@code Bag{}.isEmpty()} → {@code true}
   *
   * @param ctx The isEmpty operation node
   * @return A singleton boolean value
   */
  Value isEmpty(VitruvOCLParser.IsEmptyOpContext ctx) {
    Value receiver = context.receiver();
    return Value.boolValue(receiver.isEmpty());
  }

  /**
   * Evaluates the {@code notEmpty()} operation.
   *
   * <p>Returns true if the receiver collection contains at least one element.
   *
   * <p><b>Example:</b> {@code Bag{1}.notEmpty()} → {@code true}
   *
   * @param ctx The notEmpty operation node
   * @return A singleton boolean value
   */
  Value notEmpty(VitruvOCLParser.NotEmptyOpContext ctx) {
    Value receiver = context.receiver();
    return Value.boolValue(!receiver.isEmpty());
  }

  /**
   * Evaluates the {@code first()} operation.
   *
   * <p>Returns the first element of an ordered collection, or empty if the collection is empty.
   *
   * <p><b>Example:</b> {@code Sequence{1,2,3}.first()} → {@code 1}
   *
   * @param ctx The first operation node
   * @return A singleton containing the first element, or empty
   */
  Value first(VitruvOCLParser.FirstOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.empty(Type.optional(Type.ANY));
    }
    return new Value(List.of(receiver.getElements().get(0)), Type.optional(Type.ANY));
  }

  /**
   * Evaluates the {@code last()} operation.
   *
   * <p>Returns the last element of an ordered collection, or empty if the collection is empty.
   *
   * <p><b>Example:</b> {@code Sequence{1,2,3}.last()} → {@code 3}
   *
   * @param ctx The last operation node
   * @return A singleton containing the last element, or empty
   */
  Value last(VitruvOCLParser.LastOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.empty(Type.optional(Type.ANY));
    }
    int lastIndex = receiver.getElements().size() - 1;
    return new Value(List.of(receiver.getElements().get(lastIndex)), Type.optional(Type.ANY));
  }

  /**
   * Evaluates the {@code at()} operation on a String or ordered collection receiver.
   *
   * <p>Retrieves the element at the given 1-based index. Two receiver kinds are supported:
   *
   * <ul>
   *   <li><b>String</b> — returns the character at position {@code index} as a singleton {@code
   *       STRING}. Valid range: {@code [1, length]}.
   *   <li><b>Ordered collection</b> — returns the element at position {@code index} as a singleton
   *       of the collection's element type. Valid range: {@code [1, size]}.
   * </ul>
   *
   * <p>Example: {@code Sequence{10, 20, 30}->at(2)} yields {@code 20}<br>
   * Example: {@code 'hello'->at(1)} yields {@code 'h'}
   *
   * @param ctx the parse tree node for the {@code at()} operation, including the index argument
   *     expression
   * @return a singleton value containing the element or character at the given position; or an
   *     error value if the index is not a singleton {@code Integer}, or the index is out of bounds
   *     for the receiver
   */
  Value at(VitruvOCLParser.AtOpContext ctx) {
    Value receiver = context.receiver();
    Value indexVal = context.evaluate(ctx.index);

    if (indexVal.isEmpty()) {
      return Value.empty(Type.ANY);
    }
    Integer index = indexVal.getElements().get(0).tryGetInt();
    if (index == null) {
      return Value.empty(Type.ANY);
    }

    // String case: only a single String receiver, never a collection that happens to hold Strings
    String str =
        receiver.getRuntimeType().isCollection() || receiver.isEmpty()
            ? null
            : receiver.getElements().get(0).tryGetString();
    if (str != null) {
      if (index < 1 || index > str.length()) {
        return Value.empty(Type.STRING);
      }
      return Value.stringValue(String.valueOf(str.charAt(index - 1)));
    }

    // Collection case (1-based)
    if (index < 1 || index > receiver.size()) {
      return Value.empty(Type.ANY);
    }
    OCLElement elem = receiver.getElements().get(index - 1);
    Type elemType = receiver.getRuntimeType().getElementType();
    return new Value(List.of(elem), Type.singleton(elemType));
  }

  /**
   * Evaluates the {@code including()} operation.
   *
   * <p>Returns a new collection with the argument element added.
   *
   * <p><b>Example:</b> {@code Bag{1,2}.including(3)} → {@code Bag{1,2,3}}
   *
   * @param ctx The including operation node
   * @return A new collection with the element added
   */
  Value including(VitruvOCLParser.IncludingOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return receiver;
    }
    return receiver.including(arg.getElements().get(0));
  }

  /**
   * Evaluates the {@code excluding()} operation.
   *
   * <p>Returns a new collection with all occurrences of the argument element removed.
   *
   * <p><b>Example:</b> {@code Bag{1,2,2,3}.excluding(2)} → {@code Bag{1,3}}
   *
   * @param ctx The excluding operation node
   * @return A new collection with the element removed
   */
  Value excluding(VitruvOCLParser.ExcludingOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return receiver;
    }
    return receiver.excluding(arg.getElements().get(0));
  }

  /**
   * Evaluates the {@code union()} operation.
   *
   * <p>Returns a new collection containing all elements from both the receiver and argument
   * collections.
   *
   * <p><b>Example:</b> {@code Bag{1,2}.union(Bag{2,3})} → {@code Bag{1,2,2,3}}
   *
   * @param ctx The union operation node
   * @return A new collection with combined elements
   */
  Value union(VitruvOCLParser.UnionOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    return receiver.union(arg);
  }

  /**
   * Evaluates the {@code intersection()} operation on a collection receiver.
   *
   * <p>Category 3 (lazy, incremental buffer reconciliation): delegates to {@link
   * Value#intersection}, which pulls from both sides in alternation via {@link
   * tools.vitruv.dsls.vitruvocl.evaluator.lazy.LazyOperations#intersect} instead of fully
   * materializing either side first. The result type is taken from the type-checker annotation on
   * the node if available, falling back to the receiver's runtime type. If the result type enforces
   * uniqueness (e.g. {@code Set}), duplicates are removed afterward (also lazily, see {@link
   * Value#removeDuplicates}).
   *
   * <p>Example: {@code Set{1, 2, 3}->intersection(Set{2, 3, 4})} yields {@code Set{2, 3}}
   *
   * <p><b>Note:</b> the alternating dual-buffer algorithm matches elements pairwise (multiset
   * intersection), which is equivalent to the receiver-membership-filter semantics used here
   * previously whenever at least one side has no internal duplicates — true for every {@code
   * Set}/{@code OrderedSet} receiver or argument, i.e. the standard case. It can differ from that
   * prior behavior for a {@code Bag}/{@code Sequence} receiver <em>and</em> argument that both
   * contain duplicates of the same value; a non-unique result type is then no longer guaranteed to
   * reproduce every receiver occurrence that has some match, only up to the argument's matching
   * multiplicity. A unique ({@code Set}/{@code OrderedSet}) result type is unaffected either way.
   *
   * @param ctx the parse tree node for the {@code intersection()} operation, including the argument
   *     expression
   * @return a collection containing all elements present in both the receiver and the argument,
   *     deduplicated if the result type requires uniqueness
   */
  Value intersection(VitruvOCLParser.IntersectionOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);

    Type resultType = context.nodeType(ctx);
    Value result =
        Value.lazy(
            receiver.intersection(arg).asSource(),
            resultType != null ? resultType : receiver.getRuntimeType());

    if (result.getRuntimeType().isUnique()) {
      result = result.removeDuplicates();
    }
    return result;
  }

  /**
   * Evaluates the {@code symmetricDifference()} operation on a collection receiver.
   *
   * <p>Returns a collection containing all elements that are in either the receiver or the argument
   * collection, but not in both — i.e. the set-theoretic symmetric difference. Membership is tested
   * via {@link Value#includes}. The result type is taken from the type-checker annotation on the
   * node if available, falling back to the receiver's runtime type. If the result type enforces
   * uniqueness (e.g. {@code Set}), duplicates are removed after construction.
   *
   * <p>Example: {@code Set{1, 2, 3}->symmetricDifference(Set{2, 3, 4})} yields {@code Set{1, 4}}
   *
   * @param ctx the parse tree node for the {@code symmetricDifference()} operation, including the
   *     argument expression
   * @return a collection containing all elements exclusive to either operand, deduplicated if the
   *     result type requires uniqueness
   */
  Value symmetricDifference(VitruvOCLParser.SymmetricDifferenceOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    // Index each side once: O(n + m) instead of O(n * m) membership scans
    LazyOperations.HashedBuffer receiverIndex =
        LazyOperations.semanticIndex(receiver.elementIterator());
    LazyOperations.HashedBuffer argIndex = LazyOperations.semanticIndex(arg.elementIterator());
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      if (!argIndex.contains(elem)) {
        results.add(elem);
      }
    }
    for (OCLElement elem : arg.getElements()) {
      if (!receiverIndex.contains(elem)) {
        results.add(elem);
      }
    }
    Type resultType = context.nodeType(ctx);
    Value result = Value.of(results, resultType != null ? resultType : receiver.getRuntimeType());
    if (result.getRuntimeType().isUnique()) {
      result = result.removeDuplicates();
    }
    return result;
  }

  /**
   * Evaluates the {@code append()} operation.
   *
   * <p>Appends an element to the end of an ordered collection. Currently implemented as union.
   *
   * <p><b>Example:</b> {@code Sequence{1,2}.append(3)} → {@code Sequence{1,2,3}}
   *
   * @param ctx The append operation node
   * @return A new collection with the element appended
   */
  Value append(VitruvOCLParser.AppendOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    return receiver.union(arg);
  }

  /**
   * Evaluates the {@code prepend()} operation on a sequence receiver.
   *
   * <p>Inserts the given singleton argument at the front of the receiver collection. The result
   * type is taken from the type-checker annotation on the node if available, falling back to the
   * receiver's runtime type. If the result type enforces uniqueness (e.g. {@code OrderedSet}),
   * duplicates are removed after insertion.
   *
   * <p>Example: {@code Sequence{2, 3}->prepend(1)} yields {@code Sequence{1, 2, 3}}
   *
   * @param ctx the parse tree node for the {@code prepend()} operation, including the argument
   *     expression
   * @return a collection of the same kind as the receiver with the argument prepended as the first
   *     element, deduplicated if the type requires uniqueness; or an error value if the argument is
   *     not a singleton
   */
  Value prepend(VitruvOCLParser.PrependOpContext ctx) {
    Value receiver = context.receiver();
    Value arg = context.evaluate(ctx.arg);
    if (arg.isEmpty()) {
      return receiver;
    }
    List<OCLElement> elements = new ArrayList<>();
    elements.add(arg.getElements().get(0));
    elements.addAll(receiver.getElements());
    Type resultType = context.nodeType(ctx);
    Value result = Value.of(elements, resultType != null ? resultType : receiver.getRuntimeType());
    if (result.getRuntimeType().isUnique()) {
      result = result.removeDuplicates();
    }
    return result;
  }

  /**
   * Evaluates the {@code insertAt()} operation on a sequence receiver.
   *
   * <p>Inserts the given singleton element at the specified 1-based index position into the
   * receiver collection. Valid index range is {@code [1, size+1]}. The result type is taken from
   * the type-checker annotation on the node if available, falling back to the receiver's runtime
   * type. If the result type enforces uniqueness (e.g. {@code OrderedSet}), duplicates are removed
   * after insertion.
   *
   * <p>Example: {@code Sequence{1, 3}->insertAt(2, 2)} yields {@code Sequence{1, 2, 3}}
   *
   * @param ctx the parse tree node for the {@code insertAt()} operation, including the index
   *     expression and the element argument expression
   * @return a collection of the same kind as the receiver with the argument inserted at the given
   *     position, deduplicated if the type requires uniqueness; or an error value if the index or
   *     argument is not a singleton, the index is not an {@code Integer}, or the index is out of
   *     bounds
   */
  Value insertAt(VitruvOCLParser.InsertAtOpContext ctx) {
    Value receiver = context.receiver();
    Value indexVal = context.evaluate(ctx.index);
    Value arg = context.evaluate(ctx.arg);
    if (indexVal.isEmpty()) {
      return receiver;
    }
    Integer index = indexVal.getElements().get(0).tryGetInt();
    if (index == null) {
      return receiver;
    }
    if (arg.isEmpty()) {
      return receiver;
    }
    if (index < 1 || index > receiver.size() + 1) {
      return receiver;
    }
    List<OCLElement> elements = new ArrayList<>(receiver.getElements());
    elements.add(index - 1, arg.getElements().get(0));
    Type resultType = context.nodeType(ctx);
    Value result = Value.of(elements, resultType != null ? resultType : receiver.getRuntimeType());
    if (result.getRuntimeType().isUnique()) {
      result = result.removeDuplicates();
    }
    return result;
  }

  /**
   * Evaluates the {@code subSequence()} operation on an ordered collection receiver.
   *
   * <p>Returns a contiguous sub-collection from the receiver using 1-based, inclusive bounds {@code
   * [start, end]}. The result preserves the receiver's collection kind and element type.
   *
   * <p>Example: {@code Sequence{10, 20, 30, 40}->subSequence(2, 3)} yields {@code Sequence{20, 30}}
   *
   * @param ctx the parse tree node for the {@code subSequence()} operation, including the start and
   *     end bound expressions
   * @return a collection of the same kind as the receiver containing the elements at positions
   *     {@code start} through {@code end} inclusive; or an error value if either bound is not a
   *     singleton {@code Integer}, or the bounds are out of range ({@code start < 1}, {@code end >
   *     size}, or {@code start > end})
   */
  Value subSequence(VitruvOCLParser.SubSequenceOpContext ctx) {
    Value receiver = context.receiver();
    Value startVal = context.evaluate(ctx.start);
    Value endVal = context.evaluate(ctx.end);

    if (startVal.isEmpty() || endVal.isEmpty()) {
      return Value.of(List.of(), receiver.getRuntimeType());
    }
    if (startVal.size() != 1 || endVal.size() != 1) {
      return Value.of(List.of(), receiver.getRuntimeType());
    }

    Integer start = startVal.getElements().get(0).tryGetInt();
    Integer end = endVal.getElements().get(0).tryGetInt();

    if (start == null || end == null) {
      return Value.of(List.of(), receiver.getRuntimeType());
    }
    if (start < 1 || end > receiver.size() || start > end) {
      return Value.of(List.of(), receiver.getRuntimeType());
    }

    // 1-based, inclusive on both ends
    List<OCLElement> sub = receiver.getElements().subList(start - 1, end);
    return Value.of(new ArrayList<>(sub), receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code reverse()} operation.
   *
   * <p>Returns a new collection with elements in reverse order.
   *
   * <p><b>Example:</b> {@code Sequence{1,2,3}.reverse()} → {@code Sequence{3,2,1}}
   *
   * @param ctx The reverse operation node
   * @return A new collection with reversed element order
   */
  Value reverse(VitruvOCLParser.ReverseOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> reversed = new ArrayList<>(receiver.getElements());
    Collections.reverse(reversed);
    return Value.of(reversed, receiver.getRuntimeType());
  }

  /**
   * Evaluates the {@code flatten()} operation.
   *
   * <p>Flattens nested collections into a single-level collection.
   *
   * <p><b>Example:</b> {@code Bag{Bag{1,2}, Bag{3}}.flatten()} → {@code Bag{1,2,3}}
   *
   * @param ctx The flatten operation node
   * @return A flattened collection
   */
  Value flatten(VitruvOCLParser.FlattenOpContext ctx) {
    Value receiver = context.receiver();
    return receiver.flatten();
  }

  /**
   * Evaluates the {@code lift()} operation.
   *
   * <p>Wraps the receiver collection in a nested collection structure. Used for creating
   * collections of collections.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3}.lift()} → {@code Bag{Bag{1,2,3}}}
   *
   * @param ctx The lift operation node
   * @return A singleton containing the receiver as a nested collection
   */
  Value lift(VitruvOCLParser.LiftOpContext ctx) {
    Value receiver = context.receiver();
    OCLElement.NestedCollection wrapped = new OCLElement.NestedCollection(receiver);
    Type outerType = context.nodeType(ctx);
    return Value.of(
        List.of(wrapped), outerType != null ? outerType : Type.set(receiver.getRuntimeType()));
  }

  /**
   * Evaluates the {@code asSet()} conversion operation on a collection receiver.
   *
   * <p>Converts the receiver collection to a {@code Set} by wrapping its elements in a new {@code
   * Set}-typed value and removing duplicates via {@link Value#removeDuplicates()}. The element type
   * is preserved.
   *
   * <p>Example: {@code Sequence{1, 2, 2, 3}->asSet()} yields {@code Set{1, 2, 3}}
   *
   * @param ctx the parse tree node for the {@code asSet()} operation
   * @return a {@code Set} containing the receiver's elements with duplicates removed
   */
  Value asSet(VitruvOCLParser.AsSetOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> elements = new ArrayList<>(receiver.getElements());
    Type elemType = receiver.getRuntimeType().getElementType();
    Value result = Value.of(elements, Type.set(elemType));
    return result.removeDuplicates();
  }

  /**
   * Evaluates the {@code asBag()} conversion operation on a collection receiver.
   *
   * <p>Converts the receiver collection to a {@code Bag} by wrapping its elements in a new {@code
   * Bag}-typed value. All elements including duplicates are preserved; no deduplication is
   * performed. The element type is preserved.
   *
   * <p>Example: {@code Set{1, 2, 3}->asBag()} yields {@code Bag{1, 2, 3}}
   *
   * @param ctx the parse tree node for the {@code asBag()} operation
   * @return a {@code Bag} containing all elements of the receiver, including duplicates
   */
  Value asBag(VitruvOCLParser.AsBagOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> elements = new ArrayList<>(receiver.getElements());
    Type elemType = receiver.getRuntimeType().getElementType();
    return Value.of(elements, Type.bag(elemType));
  }

  /**
   * Evaluates the {@code asSequence()} conversion operation on a collection receiver.
   *
   * <p>Converts the receiver collection to a {@code Sequence} by wrapping its elements in a new
   * {@code Sequence}-typed value. Element order and duplicates are preserved as-is from the
   * receiver. The element type is preserved.
   *
   * <p>Example: {@code Set{3, 1, 2}->asSequence()} yields {@code Sequence{3, 1, 2}}
   *
   * @param ctx the parse tree node for the {@code asSequence()} operation
   * @return a {@code Sequence} containing all elements of the receiver in their current order,
   *     including any duplicates
   */
  Value asSequence(VitruvOCLParser.AsSequenceOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> elements = new ArrayList<>(receiver.getElements());
    Type elemType = receiver.getRuntimeType().getElementType();
    return Value.of(elements, Type.sequence(elemType));
  }

  /**
   * Evaluates the {@code asOrderedSet()} conversion operation on a collection receiver.
   *
   * <p>Converts the receiver collection to an {@code OrderedSet} by wrapping its elements in a new
   * {@code OrderedSet}-typed value and removing duplicates via {@link Value#removeDuplicates()},
   * preserving the relative order of first occurrences. The element type is preserved.
   *
   * <p>Example: {@code Sequence{3, 1, 2, 1}->asOrderedSet()} yields {@code OrderedSet{3, 1, 2}}
   *
   * @param ctx the parse tree node for the {@code asOrderedSet()} operation
   * @return an {@code OrderedSet} containing the receiver's elements in their original order with
   *     duplicates removed
   */
  Value asOrderedSet(VitruvOCLParser.AsOrderedSetOpContext ctx) {
    Value receiver = context.receiver();
    List<OCLElement> elements = new ArrayList<>(receiver.getElements());
    Type elemType = receiver.getRuntimeType().getElementType();
    Value result = Value.of(elements, Type.orderedSet(elemType));
    return result.removeDuplicates();
  }

  /**
   * Evaluates the {@code sum()} operation.
   *
   * <p>Returns the sum of all numeric elements. If all elements are integers, returns an Integer;
   * if any element is Float or Double, returns a Double.
   *
   * <p><b>Example:</b> {@code Bag{1,2,3}.sum()} → {@code 6}, {@code Set{1.5,2.5}.sum()} → {@code
   * 4.0}
   *
   * @param ctx The sum operation node
   * @return A singleton integer or double value
   */
  Value sum(VitruvOCLParser.SumOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.intValue(0);
    }
    boolean hasFloating = false;
    double sum = 0.0;
    for (OCLElement elem : receiver.getElements()) {
      if (!OCLElement.isNumeric(elem)) {
        continue;
      }
      if (elem.tryGetFloat() != null || elem.tryGetDouble() != null) {
        hasFloating = true;
      }
      sum += elem.toDoubleValue();
    }
    if (hasFloating) {
      return Value.doubleValue(sum);
    }
    return Value.intValue((int) sum);
  }

  /**
   * Evaluates the {@code max()} operation.
   *
   * <p>Returns the maximum integer value in the collection, or empty if the collection is empty.
   *
   * <p><b>Example:</b> {@code Bag{1,5,3}.max()} → {@code 5}
   *
   * @param ctx The max operation node
   * @return A singleton integer value, or empty
   */
  Value max(VitruvOCLParser.MaxOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }

    // Unwrap nested collections/singletons to get primitive values
    List<OCLElement> elements =
        receiver.getElements().stream().map(e -> OCLElement.unwrapNestedCollection(e)).toList();

    double max = Double.NEGATIVE_INFINITY;
    for (OCLElement elem : elements) {
      if (!OCLElement.isNumeric(elem)) {
        continue;
      }
      double val = elem.toDoubleValue();
      if (val > max) {
        max = val;
      }
    }
    boolean allInt = elements.stream().allMatch(e -> e instanceof OCLElement.IntValue);
    return allInt ? Value.intValue((int) max) : Value.doubleValue(max);
  }

  /**
   * Evaluates the {@code min()} operation.
   *
   * <p>Returns the minimum integer value in the collection, or empty if the collection is empty.
   *
   * <p><b>Example:</b> {@code Bag{1,5,3}.min()} → {@code 1}
   *
   * @param ctx The min operation node
   * @return A singleton integer value, or empty
   */
  Value min(VitruvOCLParser.MinOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }

    // Unwrap nested collections/singletons to get primitive values
    List<OCLElement> elements =
        receiver.getElements().stream().map(e -> OCLElement.unwrapNestedCollection(e)).toList();

    double min = Double.POSITIVE_INFINITY;
    for (OCLElement elem : elements) {
      if (!OCLElement.isNumeric(elem)) {
        continue;
      }
      double val = elem.toDoubleValue();
      if (val < min) {
        min = val;
      }
    }

    boolean allInt = elements.stream().allMatch(e -> e instanceof OCLElement.IntValue);
    return allInt ? Value.intValue((int) min) : Value.doubleValue(min);
  }

  /**
   * Evaluates the {@code avg()} operation.
   *
   * <p>Returns the arithmetic mean of all elements as a Double. Supports Integer, Float, and Double
   * element types. Uses double arithmetic to avoid integer division truncation.
   *
   * <p><b>Example:</b> {@code Sequence{1,2,3,4}.avg()} → {@code 2.5}
   *
   * @param ctx The avg operation node
   * @return A singleton Double value, or empty if the collection is empty
   */
  Value avg(VitruvOCLParser.AvgOpContext ctx) {
    Value receiver = context.receiver();
    if (receiver.isEmpty()) {
      return Value.empty(Type.DOUBLE);
    }
    double sum = 0.0;
    for (OCLElement elem : receiver.getElements()) {
      if (!OCLElement.isNumeric(elem)) {
        continue;
      }
      sum += elem.toDoubleValue();
    }
    return Value.doubleValue(sum / receiver.size());
  }
}
