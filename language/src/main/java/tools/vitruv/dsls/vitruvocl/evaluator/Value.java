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
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.dsls.vitruvocl.evaluator.lazy.LazyOperations;
import tools.vitruv.dsls.vitruvocl.evaluator.lazy.OCLElementSource;
import tools.vitruv.dsls.vitruvocl.typechecker.Type;

/**
 * OCL Value - The unified value representation.
 *
 * <p>Core OCL Principle: "Everything is a collection"
 *
 * <p>All values are represented as lists of elements: - Singleton: [elem] (¡τ! - exactly 1 element)
 * - Empty: [] (no τ - "no Value") - Multi-valued: [e1, e2, e3] (collections)
 *
 * <p>The Type's Ctype properties determine how the list is interpreted: - {τ} (Set): Unique,
 * unordered - [τ] (Sequence): Non-unique, ordered - {{τ}} (Bag): Non-unique, unordered - ⟨τ⟩
 * (OrderedSet): Unique, ordered
 */
public class Value implements Comparable<Value> {

  /**
   * The actual value - in OCL, this is ALWAYS List&lt;OCLElement&gt;.
   *
   * <p>{@code null} until materialized for a lazily-backed Value (see {@link #lazySource}); every
   * accessor other than {@link #elementIterator()} and {@link #asSource()} goes through {@link
   * #getElements()}, which materializes (and caches) on first access.
   */
  private List<OCLElement> elements;

  /**
   * The lazy pull-based source backing this Value, or {@code null} if {@link #elements} was
   * supplied eagerly. See the {@link tools.vitruv.dsls.vitruvocl.evaluator.lazy package
   * documentation} for the classification of which operations produce a lazy Value.
   *
   * <p>Released ({@code null}) once the source is exhausted and {@link #elements} is set, so that
   * the upstream steps of a lazy chain — and the elements they buffered — become garbage as soon as
   * this Value no longer needs them.
   */
  private OCLElementSource lazySource;

  /**
   * Elements pulled from {@link #lazySource} so far, shared by every traversal of this Value
   * (call-by-need, Tisi et al., "Lazy Evaluation for OCL", 2015: an element is computed at most
   * once, however many consumers traverse the Value). {@code null} until the first pull; becomes
   * {@link #elements} once the source is exhausted.
   *
   * <p>Trade-off: every lazy step that is traversed buffers the elements it produced, i.e. a chain
   * of {@code k} steps over {@code n} elements holds up to {@code k * n} references while it is
   * being evaluated (instead of streaming with constant extra memory). In exchange no element is
   * ever recomputed when a Value is traversed more than once, which call-by-name evaluation did
   * (quadratically, e.g. for {@code includes} on a {@code let}-bound collection inside an
   * iterator).
   */
  private List<OCLElement> pulled;

  /** The single traversal of {@link #lazySource} that fills {@link #pulled}; started on demand. */
  private Iterator<OCLElement> pullSource;

  /**
   * The exception the shared traversal failed with, if any. Rethrown by every later access, so a
   * failed traversal can never be mistaken for a complete (but truncated) collection.
   */
  private RuntimeException pullFailure;

  /** Whether a pull from {@link #pullSource} is in progress (guards against reentrant pulls). */
  private boolean pulling;

  /** The runtime type - includes Ctype information (unique, ordered). */
  private final Type runtimeType;

  // ==================== Constructors ====================

  /** Creates a Value from a list of elements. This is the main constructor for OCL values. */
  public Value(List<OCLElement> elements, Type runtimeType) {
    this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
    this.lazySource = null;
    this.runtimeType = runtimeType;
  }

  /**
   * Legacy constructor for backwards compatibility. If value is already List of OCLElement, use it
   * directly. Otherwise, wrap it as a singleton OCLElement.
   */
  public Value(Object value, Type runtimeType) {
    this.lazySource = null;
    if (value instanceof List) {
      @SuppressWarnings("unchecked")
      List<OCLElement> list = (List<OCLElement>) value;
      this.elements = Collections.unmodifiableList(new ArrayList<>(list));
    } else if (value == null) {
      // null becomes empty collection in OCL
      this.elements = List.of();
    } else {
      // Wrap single value as singleton
      this.elements = List.of(wrapAsElement(value));
    }
    this.runtimeType = runtimeType;
  }

  /** Private constructor for a lazily-backed Value; use the {@link #lazy} factory. */
  private Value(OCLElementSource lazySource, Type runtimeType) {
    this.elements = null;
    this.lazySource = lazySource;
    this.runtimeType = runtimeType;
  }

  /**
   * Creates a lazily-evaluated Value backed by {@code source}. No element is pulled from {@code
   * source} until a consumer asks for one, via {@link #elementIterator()} (which may stop early)
   * or {@link #getElements()} (which always drains the source fully, once, and caches the
   * result).
   *
   * @param source the lazy pull-based element source
   * @param runtimeType the runtime type of the resulting collection
   * @return a Value that defers evaluation until consumed
   */
  public static Value lazy(OCLElementSource source, Type runtimeType) {
    return new Value(source, runtimeType);
  }

  /** Wraps a Java object as an OCLElement (for legacy compatibility). */
  private OCLElement wrapAsElement(Object obj) {
    return switch (obj) {
      case Integer i -> new OCLElement.IntValue(i);
      case Boolean b -> new OCLElement.BoolValue(b);
      case String s -> new OCLElement.StringValue(s);
      default ->
          throw new IllegalArgumentException("Cannot wrap " + obj.getClass() + " as OCLElement");
    };
  }

  // ==================== Factory Methods ====================

  /** Creates an empty Value (no τ). */
  public static Value empty(Type type) {
    return new Value(List.of(), type);
  }

  /** Creates a singleton Value (¡τ!). */
  public static Value singleton(OCLElement elem, Type type) {
    return new Value(List.of(elem), type);
  }

  /** Creates a Value from multiple elements. */
  public static Value of(List<OCLElement> elements, Type type) {
    return new Value(elements, type);
  }

  /** Convenience: Creates integer singleton. */
  public static Value intValue(int value) {
    return singleton(new OCLElement.IntValue(value), Type.INTEGER);
  }

  /** Convenience: Creates boolean singleton. */
  public static Value boolValue(boolean value) {
    return singleton(new OCLElement.BoolValue(value), Type.BOOLEAN);
  }

  /** Convenience: Creates string singleton. */
  public static Value stringValue(String value) {
    return singleton(new OCLElement.StringValue(value), Type.STRING);
  }

  // ==================== Accessors ====================

  /**
   * Returns the elements as an immutable list, materializing (and caching) a lazily-backed Value
   * fully if necessary.
   *
   * <p>Structurally strict consumers (aggregations, {@code size()}, {@code sortedBy}, semantic
   * equality, ...) use this. It always drains the whole source exactly once, so an upstream chain
   * of lazy steps is still traversed only once, not copied at every intermediate step.
   */
  public List<OCLElement> getElements() {
    materialize();
    return elements;
  }

  /**
   * Materializes {@link #elements} on first access by pulling the rest of the shared traversal
   * (see {@link #pulled}); elements already pulled by earlier consumers are not recomputed.
   */
  private void materialize() {
    if (elements == null) {
      Iterator<OCLElement> it = elementIterator();
      while (it.hasNext()) {
        it.next();
      }
    }
  }

  /**
   * Returns a fresh, pull-based iterator over this Value's elements without forcing full
   * materialization.
   *
   * <p>For an already-eager or already-materialized Value this simply wraps {@link #elements}. For
   * an unmaterialized lazily-backed Value, the iterator reads the elements already computed by
   * earlier traversals and pulls further ones from the one shared traversal of {@link #lazySource}
   * only when it gets past them (call-by-need). Stopping early (e.g. from {@code exists}/{@code
   * any}/{@code notEmpty()}) never triggers evaluation of the remaining elements; a later traversal
   * continues where the furthest one stopped instead of starting over.
   *
   * @return a fresh iterator over this collection's elements
   */
  public Iterator<OCLElement> elementIterator() {
    return elements != null ? elements.iterator() : new SharedTraversalIterator();
  }

  /**
   * Returns this Value's elements as a replayable {@link OCLElementSource}, for composing further
   * lazy chains (e.g. {@code union}/{@code intersection}) without forcing materialization. Chains
   * built on it share this Value's computed elements (see {@link #elementIterator()}).
   *
   * @return a source that replays this Value's elements on every traversal
   */
  public OCLElementSource asSource() {
    return elements != null ? OCLElementSource.of(elements) : this::elementIterator;
  }

  /**
   * Whether the shared traversal has an element beyond those in {@link #pulled}. Only asks {@code
   * hasNext()} of the source — taking the element is left to {@link
   * SharedTraversalIterator#next()}, since a source's {@code next()} may already look ahead (e.g.
   * a filter searching for its following match), which a mere {@code hasNext()} must not cause.
   *
   * @return {@code false} if the source is exhausted, in which case {@link #elements} is set
   */
  private boolean canPull() {
    boolean hasMore = pull(this::sourceHasNext);
    if (!hasMore) {
      elements = Collections.unmodifiableList(pulled);
      pullSource = null;
      lazySource = null;
    }
    return hasMore;
  }

  /**
   * Starts the shared traversal on first use (the iterator is created before {@link #pulled}, so
   * a failing start leaves no half-initialized state) and asks it for a further element.
   */
  private boolean sourceHasNext() {
    if (pullSource == null) {
      pullSource = lazySource.newIterator();
      pulled = new ArrayList<>();
    }
    return pullSource.hasNext();
  }

  /** Takes the next element of the shared traversal into {@link #pulled}. */
  private OCLElement pullNext() {
    OCLElement element = pull(pullSource::next);
    pulled.add(element);
    return element;
  }

  /**
   * Runs one step of the shared traversal: rethrows an earlier failure, refuses reentrant pulls,
   * and records a failure so that every later access rethrows it.
   */
  private <T> T pull(Supplier<T> step) {
    if (pullFailure != null) {
      throw pullFailure;
    }
    if (pulling) {
      throw new IllegalStateException(
          "Reentrant traversal of a lazy collection while one of its elements is being computed");
    }
    pulling = true;
    try {
      return step.get();
    } catch (RuntimeException e) {
      pullFailure = e;
      throw e;
    } finally {
      pulling = false;
    }
  }

  /** Iterates the elements of a lazily-backed Value, computing each one at most once. */
  private final class SharedTraversalIterator implements Iterator<OCLElement> {
    private int index;

    @Override
    public boolean hasNext() {
      if (elements != null) {
        return index < elements.size();
      }
      return (pulled != null && index < pulled.size()) || canPull();
    }

    @Override
    public OCLElement next() {
      if (!hasNext()) {
        throw new java.util.NoSuchElementException();
      }
      if (elements != null) {
        return elements.get(index++);
      }
      if (index < pulled.size()) {
        return pulled.get(index++);
      }
      index++;
      return pullNext();
    }
  }

  /** Returns the runtime type. */
  public Type getRuntimeType() {
    return runtimeType;
  }

  /** Returns the number of elements in this collection. Structurally strict: materializes fully. */
  public int size() {
    return getElements().size();
  }

  /**
   * Checks if this collection is empty. Lazy: stops after pulling at most one element from an
   * unmaterialized source.
   */
  public boolean isEmpty() {
    return elements != null ? elements.isEmpty() : !elementIterator().hasNext();
  }

  /** Checks if this collection is not empty. Lazy; see {@link #isEmpty()}. */
  public boolean notEmpty() {
    return !isEmpty();
  }

  /**
   * In OCL, null doesn't exist - only empty collections. This method returns true if the collection
   * is empty. Lazy; see {@link #isEmpty()}.
   */
  public boolean isNull() {
    return isEmpty();
  }

  // ==================== Monoid Operations ====================

  /**
   * Merges two Values using OCL monoid semantics (⊕χ).
   *
   * <p>Behavior depends on Type's Ctype properties: - Set (unique, unordered): Union with
   * uniqueness - Bag (non-unique, unordered): Concatenation with duplicates - Sequence (non-unique,
   * ordered): Concatenation preserving order - OrderedSet (unique, ordered): Union preserving order
   */
  public Value merge(Value other) {
    boolean isUnique = runtimeType.isUnique();
    OCLElementSource concatenated = LazyOperations.concat(this.asSource(), other.asSource());
    OCLElementSource result =
        isUnique
            ? LazyOperations.dedupeSemantically(concatenated)
            : concatenated;
    return Value.lazy(result, this.runtimeType);
  }

  // ==================== Collection Operations ====================

  /** Checks if this collection includes an element. Lazy: stops at the first match. */
  public boolean includes(OCLElement elem) {
    Iterator<OCLElement> it = elementIterator();
    while (it.hasNext()) {
      if (OCLElement.semanticEquals(it.next(), elem)) {
        return true;
      }
    }
    return false;
  }

  /** Checks if this collection excludes an element. */
  public boolean excludes(OCLElement element) {
    return !includes(element);
  }

  /** Returns a new Value with element included. Immutable - does not modify this Value. */
  public Value including(OCLElement element) {
    return this.merge(singleton(element, runtimeType));
  }

  /**
   * Returns a new Value with element excluded. - Set/OrderedSet: Removes all occurrences -
   * Bag/Sequence: Removes first occurrence only
   */
  public Value excluding(OCLElement element) {
    boolean isUnique = runtimeType.isUnique();
    OCLElementSource source = asSource();
    OCLElementSource filtered;
    if (isUnique) {
      // Set/OrderedSet: remove all occurrences - stateless predicate, safe to reuse across
      // traversals.
      filtered = LazyOperations.filter(source, elem -> !OCLElement.semanticEquals(elem, element));
    } else {
      // Bag/Sequence: remove only the first occurrence. The "already removed" flag is
      // per-traversal state, so it must be created fresh inside the source factory - reusing a
      // single flag across multiple newIterator() calls would corrupt replay.
      filtered =
          LazyOperations.filterStateful(
              source,
              () -> {
                boolean[] removedFirst = {false};
                return elem -> {
                  if (!removedFirst[0] && OCLElement.semanticEquals(elem, element)) {
                    removedFirst[0] = true;
                    return false;
                  }
                  return true;
                };
              });
    }
    return Value.lazy(filtered, runtimeType);
  }

  /** Union of two collections. */
  public Value union(Value other) {
    return this.merge(other);
  }

  /**
   * Intersection of two collections. Lazy: pulls from both sides in alternation via {@link
   * LazyOperations#intersect}, buffering only the elements consumed so far per side, instead of
   * materializing either side up front.
   */
  public Value intersection(Value other) {
    OCLElementSource result =
        LazyOperations.intersectSemantically(this.asSource(), other.asSource());
    return Value.lazy(result, this.runtimeType);
  }

  /** Set difference (this - other). */
  public Value minus(Value other) {
    OCLElementSource result = LazyOperations.filter(this.asSource(), elem -> !other.includes(elem));
    return Value.lazy(result, this.runtimeType);
  }

  /** Symmetric difference (elements in either but not both). */
  public Value symmetricDifference(Value other) {
    OCLElementSource onlyInThis =
        LazyOperations.filter(this.asSource(), elem -> !other.includes(elem));
    OCLElementSource onlyInOther =
        LazyOperations.filter(other.asSource(), elem -> !this.includes(elem));
    return Value.lazy(LazyOperations.concat(onlyInThis, onlyInOther), this.runtimeType);
  }

  /**
   * Checks if this collection includes all elements of another. Lazy: stops as soon as one element
   * of {@code other} is missing.
   */
  public boolean includesAll(Value other) {
    Iterator<OCLElement> it = other.elementIterator();
    while (it.hasNext()) {
      if (!this.includes(it.next())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if this collection excludes all elements of another. Lazy: stops as soon as one element
   * of {@code other} is found in this collection.
   */
  public boolean excludesAll(Value other) {
    Iterator<OCLElement> it = other.elementIterator();
    while (it.hasNext()) {
      if (this.includes(it.next())) {
        return false;
      }
    }
    return true;
  }

  // ==================== Ordered Collection Operations ====================

  /** Returns the first element. Returns empty if this is empty (OCL semantics). */
  public Value first() {
    Iterator<OCLElement> it = elementIterator();
    if (!it.hasNext()) {
      return empty(runtimeType);
    }
    return singleton(it.next(), runtimeType.getElementType());
  }

  /** Returns the last element. Returns empty if this is empty (OCL semantics). */
  public Value last() {
    List<OCLElement> all = getElements();
    if (all.isEmpty()) {
      return empty(runtimeType);
    }
    return singleton(all.get(all.size() - 1), runtimeType.getElementType());
  }

  /**
   * Returns element at index (1-based OCL indexing). Only valid for ordered collections.
   *
   * <p>Follows OCL#'s "no object" propagation: an unordered receiver or an out-of-bounds index
   * yields an empty value instead of throwing, so callers never need to catch an exception to
   * evaluate an OCL expression.
   */
  public Value at(int index) {
    if (!runtimeType.isOrdered() || index < 1) {
      return empty(runtimeType);
    }
    List<OCLElement> all = getElements();
    if (index > all.size()) {
      return empty(runtimeType);
    }
    return singleton(all.get(index - 1), runtimeType.getElementType());
  }

  /** Returns the index of first occurrence (1-based). Returns 0 if not found. */
  public int indexOf(OCLElement element) {
    List<OCLElement> all = getElements();
    for (int i = 0; i < all.size(); i++) {
      if (OCLElement.semanticEquals(all.get(i), element)) {
        return i + 1; // 1-based
      }
    }
    return 0; // Not found
  }

  /**
   * Reverses the order of elements. Only valid for ordered collections.
   *
   * <p>Follows OCL#'s "no object" propagation: an unordered receiver yields an empty value instead
   * of throwing, so callers never need to catch an exception to evaluate an OCL expression.
   */
  public Value reverse() {
    if (!runtimeType.isOrdered()) {
      return empty(runtimeType);
    }

    List<OCLElement> reversed = new ArrayList<>(getElements());
    Collections.reverse(reversed);
    return new Value(reversed, runtimeType);
  }

  /** Counts occurrences of an element (for Bags). */
  public int count(OCLElement element) {
    int count = 0;
    for (OCLElement elem : getElements()) {
      if (OCLElement.semanticEquals(elem, element)) {
        count++;
      }
    }
    return count;
  }

  // ==================== Normalization & Equality ====================

  /**
   * Normalizes this Value according to its Ctype. Unordered collections (Set, Bag) are sorted for
   * canonical form. Structurally strict (category 4): sorting requires the full collection.
   */
  public Value normalize() {
    if (runtimeType.isOrdered()) {
      return this; // Already canonical
    }

    List<OCLElement> sorted = new ArrayList<>(getElements());
    sorted.sort(OCLElement::compare);
    return new Value(sorted, runtimeType);
  }

  /**
   * Semantic equality (≡χ₁,χ₂). Compares normalized forms. Structurally strict (category 4): both
   * sides must be fully consumed to sort/compare them.
   */
  public static boolean semanticEquals(Value v1, Value v2) {
    if (v1 == null && v2 == null) {
      return true;
    }
    if (v1 == null || v2 == null) {
      return false;
    }

    Value norm1 = v1.normalize();
    Value norm2 = v2.normalize();

    List<OCLElement> elems1 = norm1.getElements();
    List<OCLElement> elems2 = norm2.getElements();
    if (elems1.size() != elems2.size()) {
      return false;
    }

    for (int i = 0; i < elems1.size(); i++) {
      if (!OCLElement.semanticEquals(elems1.get(i), elems2.get(i))) {
        return false;
      }
    }

    return true;
  }

  /**
   * Removes duplicate elements (for Set/OrderedSet creation). Lazy: backed by {@link
   * LazyOperations#dedupe}, so callers that only need the first few results of a subsequent chain
   * still avoid full materialization.
   */
  public Value removeDuplicates() {
    return Value.lazy(LazyOperations.dedupeSemantically(asSource()), runtimeType);
  }

  // ==================== Helper Methods ====================

  /**
   * Compares two Values for ordering (used in nested collections). Required by OCLElement.compare()
   * for NestedCollection comparison.
   */
  public static int compare(Value v1, Value v2) {
    if (v1 == v2) {
      return 0;
    }
    if (v1 == null) {
      return -1;
    }
    if (v2 == null) {
      return 1;
    }

    // First compare by size
    int sizeCompare = Integer.compare(v1.size(), v2.size());
    if (sizeCompare != 0) {
      return sizeCompare;
    }

    // Same size - compare elements pairwise
    List<OCLElement> elems1 = v1.getElements();
    List<OCLElement> elems2 = v2.getElements();
    for (int i = 0; i < elems1.size(); i++) {
      int elemCompare = OCLElement.compare(elems1.get(i), elems2.get(i));
      if (elemCompare != 0) {
        return elemCompare;
      }
    }

    return 0; // Equal
  }

  // ==================== String Representation ====================

  @Override
  public String toString() {
    List<OCLElement> all = getElements();
    if (all.isEmpty()) {
      return getCollectionKind() + "{}";
    }

    StringBuilder sb = new StringBuilder();
    sb.append(getCollectionKind()).append("{");

    for (int i = 0; i < all.size(); i++) {
      sb.append(all.get(i).toString());
      if (i < all.size() - 1) {
        sb.append(", ");
      }
    }

    sb.append("}");
    return sb.toString();
  }

  /** Returns the collection kind based on Ctype properties. */
  private String getCollectionKind() {
    if (runtimeType.isUnique() && !runtimeType.isOrdered()) {
      return "Set";
    }
    if (!runtimeType.isUnique() && runtimeType.isOrdered()) {
      return "Sequence";
    }
    if (!runtimeType.isUnique() && !runtimeType.isOrdered()) {
      return "Bag";
    }
    if (runtimeType.isUnique() && runtimeType.isOrdered()) {
      return "OrderedSet";
    }
    return "Collection";
  }

  /**
   * Flattens nested collections: Collection(Collection(T)) → Collection(T).
   *
   * <p>Example: Set{Set{1,2}, Set{3,4}}.flatten() → Set{1,2,3,4}
   *
   * <p>Lazy (category 1): the outer level is unpacked one element at a time via {@link
   * LazyOperations#flatMap}. A nested {@link OCLElement.NestedCollection}'s own {@code Value}
   * stays lazy — its {@link #elementIterator()} is used directly instead of forcing it through
   * {@link #getElements()}, so an inner collection is only consumed as far as the outer consumer
   * actually asks.
   *
   * @return Flattened collection
   */
  public Value flatten() {
    Function<OCLElement, Iterator<OCLElement>> expand =
        elem -> {
          if (elem instanceof OCLElement.NestedCollection(Value nestedValue)) {
            return nestedValue.elementIterator();
          }
          return List.of(elem).iterator();
        };
    OCLElementSource expanded = LazyOperations.flatMap(asSource(), expand);
    OCLElementSource result =
        runtimeType.isUnique()
            ? LazyOperations.dedupeSemantically(expanded)
            : expanded;
    return Value.lazy(result, runtimeType);
  }

  /** Convenience: Creates a metaclass singleton wrapping an EObject instance. */
  public static Value metaclassValue(EObject instance, Type type) {
    return singleton(new OCLElement.MetaclassValue(instance), type);
  }

  /** Convenience: Creates double singleton. */
  public static Value doubleValue(double value) {
    return singleton(new OCLElement.DoubleValue(value), Type.DOUBLE);
  }

  /** Convenience: Creates float singleton. */
  public static Value floatValue(float value) {
    return singleton(new OCLElement.FloatValue(value), Type.FLOAT);
  }

  /**
   * Compares this value to another by size then element-wise via {@link OCLElement#compare}.
   *
   * <p>Null is ordered before any non-null value.
   */
  @Override
  public int compareTo(Value other) {
    if (this == other) {
      return 0;
    }
    if (other == null) {
      return 1;
    }
    int sizeCompare = Integer.compare(this.size(), other.size());
    if (sizeCompare != 0) {
      return sizeCompare;
    }
    List<OCLElement> elems1 = this.getElements();
    List<OCLElement> elems2 = other.getElements();
    for (int i = 0; i < elems1.size(); i++) {
      int elemCompare = OCLElement.compare(elems1.get(i), elems2.get(i));
      if (elemCompare != 0) {
        return elemCompare;
      }
    }
    return 0;
  }

  /**
   * Compares this value to {@code obj} for equality, consistent with {@link #compareTo}.
   *
   * @param obj the object to compare to
   * @return {@code true} if {@code obj} is a {@link Value} whose {@link #compareTo} result is zero
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Value other)) {
      return false;
    }
    return compareTo(other) == 0;
  }

  /**
   * Returns a hash code consistent with {@link #equals(Object)}. Numeric elements are hashed by
   * their {@code double} value so that cross-numeric-type equal values hash identically.
   *
   * @return the hash code
   */
  @Override
  public int hashCode() {
    List<OCLElement> all = getElements();
    int result = all.size();
    for (OCLElement elem : all) {
      int elemHash =
          OCLElement.isNumeric(elem) ? Double.hashCode(elem.toDoubleValue()) : elem.hashCode();
      result = 31 * result + elemHash;
    }
    return result;
  }
}
