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

package tools.vitruv.dsls.vitruvocl.operations;

import static tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family.CLASSIFIER;
import static tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family.COLLECTION;
import static tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family.ITERATOR;
import static tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family.NUMERIC;
import static tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family.STRING;
import static tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family.TYPE;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family;

/**
 * The single catalog of built-in VitruvOCL operations.
 *
 * <p>Type checker suggestions, LSP completion and the operation documentation all derive their
 * operation lists from here, so they cannot drift apart. {@code OclOperationsGrammarTest} fails if
 * the grammar gains or loses an operation that is not reflected in this catalog.
 */
public final class OclOperations {

  private static final String ELEMENT = "element";
  private static final String OTHER = "other";
  private static final String PATTERN = "pattern";

  private static final Map<String, OclOperation> BY_NAME = new LinkedHashMap<>();

  static {
    // Collection: query
    add(COLLECTION, "includes", ELEMENT);
    add(COLLECTION, "excludes", ELEMENT);
    add(COLLECTION, "includesAll", OTHER);
    add(COLLECTION, "excludesAll", OTHER);
    add(COLLECTION, "count", ELEMENT);
    add(COLLECTION, "size");
    add(COLLECTION, "isEmpty");
    add(COLLECTION, "notEmpty");
    add(COLLECTION, "first");
    add(COLLECTION, "last");
    add(COLLECTION, "at", "index");
    // Collection: modification
    add(COLLECTION, "including", ELEMENT);
    add(COLLECTION, "excluding", ELEMENT);
    add(COLLECTION, "union", OTHER);
    add(COLLECTION, "intersection", OTHER);
    add(COLLECTION, "symmetricDifference", OTHER);
    add(COLLECTION, "append", ELEMENT);
    add(COLLECTION, "prepend", ELEMENT);
    add(COLLECTION, "insertAt", "index", ELEMENT);
    add(COLLECTION, "subSequence", "start", "end");
    add(COLLECTION, "reverse");
    add(COLLECTION, "flatten");
    add(COLLECTION, "lift");
    // Collection: conversion
    add(COLLECTION, "asSet");
    add(COLLECTION, "asBag");
    add(COLLECTION, "asSequence");
    add(COLLECTION, "asOrderedSet");
    // Collection: aggregation
    add(COLLECTION, "sum");
    add(COLLECTION, "max");
    add(COLLECTION, "min");
    add(COLLECTION, "avg");
    // Numeric
    add(NUMERIC, "abs");
    add(NUMERIC, "floor");
    add(NUMERIC, "ceil");
    add(NUMERIC, "ceiling");
    add(NUMERIC, "round");
    add(NUMERIC, "div", "divisor");
    add(NUMERIC, "mod", "divisor");
    // Classifier
    add(CLASSIFIER, "allInstances");
    // Iterators
    add(ITERATOR, "select");
    add(ITERATOR, "reject");
    add(ITERATOR, "collect");
    add(ITERATOR, "collectNested");
    add(ITERATOR, "forAll");
    add(ITERATOR, "exists");
    add(ITERATOR, "one");
    add(ITERATOR, "any");
    add(ITERATOR, "isUnique");
    add(ITERATOR, "sortedBy");
    add(ITERATOR, OclOperation.ITERATE);
    // String
    add(STRING, "concat", OTHER);
    add(STRING, "length");
    add(STRING, "substring", "start", "end");
    add(STRING, "toUpper");
    add(STRING, "toLower");
    add(STRING, "indexOf", "substring");
    add(STRING, "equalsIgnoreCase", OTHER);
    add(STRING, "toInteger");
    add(STRING, "toReal");
    add(STRING, "characters");
    add(STRING, "matches", PATTERN);
    add(STRING, "substituteAll", PATTERN, "replacement");
    add(STRING, "substituteFirst", PATTERN, "replacement");
    add(STRING, "tokenize", "delimiters");
    // Type tests and casts
    add(TYPE, "oclIsKindOf", "type");
    add(TYPE, "oclIsTypeOf", "type");
    add(TYPE, "oclAsType", "type");
  }

  private OclOperations() {}

  private static void add(Family family, String name, String... params) {
    OclOperation operation = new OclOperation(name, family, Arrays.asList(params));
    OclOperation previous = BY_NAME.put(name, operation);
    if (previous != null) {
      throw new IllegalStateException("Duplicate operation in catalog: " + name);
    }
  }

  /**
   * Returns every built-in operation, grouped by family.
   *
   * @return all operations (unmodifiable)
   */
  public static List<OclOperation> all() {
    return List.copyOf(BY_NAME.values());
  }

  /**
   * Returns the names of all built-in operations.
   *
   * @return all operation names (unmodifiable)
   */
  public static List<String> names() {
    return List.copyOf(BY_NAME.keySet());
  }

  /**
   * Looks up an operation by name.
   *
   * @param name the operation name as written in source
   * @return the operation, if it is built in
   */
  public static Optional<OclOperation> lookup(String name) {
    return Optional.ofNullable(BY_NAME.get(name));
  }

  /**
   * Returns the operations of the given families, in catalog order.
   *
   * @param first a family to include
   * @param rest further families to include
   * @return the matching operations (unmodifiable)
   */
  public static List<OclOperation> ofFamilies(Family first, Family... rest) {
    Set<Family> families = EnumSet.of(first, rest);
    return BY_NAME.values().stream().filter(op -> families.contains(op.family())).toList();
  }
}
