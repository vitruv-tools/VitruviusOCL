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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family;

/**
 * Keeps {@link OclOperations} in sync with the grammar: every {@code *Op} alternative of the
 * operation rules must be in the catalog with the right family and argument count, and vice versa.
 */
class OclOperationsGrammarTest {

  private static final Map<Class<?>, String> OPERATION_RULES =
      Map.of(
          VitruvOCLParser.CollectionOpCSContext.class, "collectionOpCS",
          VitruvOCLParser.IteratorOpCSContext.class, "iteratorOpCS",
          VitruvOCLParser.StringOpCSContext.class, "stringOpCS",
          VitruvOCLParser.TypeOpCSContext.class, "typeOpCS");

  /** Grammar operation name → name of the grammar rule that defines it. */
  private static Map<String, String> grammarOperations() {
    Map<String, String> ops = new TreeMap<>();
    for (Class<?> cls : VitruvOCLParser.class.getDeclaredClasses()) {
      String rule = OPERATION_RULES.get(cls.getSuperclass());
      String simple = cls.getSimpleName();
      if (rule != null && simple.endsWith("OpContext")) {
        String stem = simple.substring(0, simple.length() - "OpContext".length());
        ops.put(Character.toLowerCase(stem.charAt(0)) + stem.substring(1), rule);
      }
    }
    return ops;
  }

  private static String ruleOf(Family family) {
    return switch (family) {
      case COLLECTION, NUMERIC, CLASSIFIER -> "collectionOpCS";
      case ITERATOR -> "iteratorOpCS";
      case STRING -> "stringOpCS";
      case TYPE -> "typeOpCS";
    };
  }

  @Test
  void catalogMatchesGrammarOperations() {
    Map<String, String> grammar = grammarOperations();
    assertTrue(grammar.size() > 60, "reflection found too few operations: " + grammar.keySet());
    assertEquals(
        new TreeSet<>(grammar.keySet()),
        new TreeSet<>(OclOperations.names()),
        "grammar operations and OclOperations catalog differ");
  }

  @Test
  void familiesMatchGrammarRules() {
    Map<String, String> grammar = grammarOperations();
    for (OclOperation op : OclOperations.all()) {
      assertEquals(grammar.get(op.name()), ruleOf(op.family()), "grammar rule of " + op.name());
    }
  }

  @Test
  void parameterCountsMatchGrammarArguments() throws ClassNotFoundException {
    for (OclOperation op : OclOperations.all()) {
      if (op.family() == Family.ITERATOR) {
        continue; // (x | body) shape, no positional parameters
      }
      String stem = Character.toUpperCase(op.name().charAt(0)) + op.name().substring(1);
      Class<?> ctx =
          Class.forName(VitruvOCLParser.class.getName() + "$" + stem + "OpContext");
      long arguments =
          Arrays.stream(ctx.getDeclaredFields())
              .filter(f -> Modifier.isPublic(f.getModifiers()))
              .map(Field::getType)
              .filter(
                  t ->
                      t == VitruvOCLParser.ExpCSContext.class
                          || t == VitruvOCLParser.TypeExpCSContext.class)
              .count();
      assertEquals(arguments, op.params().size(), "argument count of " + op.name());
    }
  }

  @Test
  void snippetsFollowArgumentShape() {
    assertEquals("size()", snippet("size"));
    assertEquals("includes($0)", snippet("includes"));
    assertEquals("insertAt(${1:index}, ${2:element})", snippet("insertAt"));
    assertEquals("select(${1:x} | $0)", snippet("select"));
    assertEquals("iterate(${1:x}; ${2:acc} = ${3:init} | $0)", snippet("iterate"));
  }

  private static String snippet(String name) {
    return OclOperations.lookup(name).orElseThrow().snippet();
  }
}
