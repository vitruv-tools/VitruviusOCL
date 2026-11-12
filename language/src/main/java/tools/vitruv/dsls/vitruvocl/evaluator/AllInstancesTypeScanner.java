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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.emf.ecore.EClass;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Static query analysis of the allInstances() computation strategy of Wei &amp; Kolovos (BigMDE
 * 2015, Section 3.2): collects, from a type-checked AST, every EClass whose extension the
 * evaluation will ask for — the context classifiers (whose instances the invariants are evaluated
 * for) and the receivers of all {@code allInstances()} calls.
 *
 * <p>Knowing these types before evaluation lets the evaluator request all of them at once, so that
 * the wrapper can collect them in one model traversal instead of one per type.
 */
public final class AllInstancesTypeScanner {

  private AllInstancesTypeScanner() {}

  /**
   * Returns every EClass the evaluation of {@code tree} may request the extension of.
   *
   * @param tree the type-checked AST (normally a {@code contextDeclCS})
   * @param nodeTypes the types the type checker computed for {@code tree}'s nodes
   * @return the requested EClasses in first-seen order; never {@code null}
   */
  public static Set<EClass> scan(ParseTree tree, ParseTreeProperty<Type> nodeTypes) {
    Set<EClass> types = new LinkedHashSet<>();
    Deque<ParseTree> pending = new ArrayDeque<>();
    pending.push(tree);
    while (!pending.isEmpty()) {
      ParseTree node = pending.pop();
      EClass type = requestedType(node, nodeTypes);
      if (type != null) {
        types.add(type);
      }
      for (int i = node.getChildCount() - 1; i >= 0; i--) {
        pending.push(node.getChild(i));
      }
    }
    return Collections.unmodifiableSet(types);
  }

  private static EClass requestedType(ParseTree node, ParseTreeProperty<Type> nodeTypes) {
    if (node instanceof VitruvOCLParser.ClassifierContextCSContext classifierCtx) {
      Type contextType = nodeTypes.get(classifierCtx);
      return contextType == null || contextType == Type.ERROR ? null : contextType.getEClass();
    }
    if (node instanceof VitruvOCLParser.AllInstancesOpContext allInstancesCtx) {
      return receiverEClass(allInstancesCtx, nodeTypes);
    }
    return null;
  }

  /**
   * Resolves the metaclass an {@code allInstances()} call is invoked on, e.g. {@code Spacecraft}
   * for {@code spaceMission::Spacecraft.allInstances()}.
   *
   * @param ctx the {@code allInstances} operation node
   * @param nodeTypes the type checker's node types
   * @return the receiver's EClass, or {@code null} if the receiver is not a metaclass type
   */
  static EClass receiverEClass(
      VitruvOCLParser.AllInstancesOpContext ctx, ParseTreeProperty<Type> nodeTypes) {
    ParseTree nav = ancestor(ctx, 3);
    if (nav == null) {
      return null;
    }
    Type receiverType = nodeTypes.get(nav);
    if (receiverType == null && nav.getParent() != null) {
      receiverType = nodeTypes.get(nav.getParent());
    }
    if (receiverType != null && receiverType.isCollection()) {
      receiverType = receiverType.getElementType();
    }
    if (receiverType == null || !receiverType.isMetaclassType()) {
      return null;
    }
    return receiverType.getEClass();
  }

  private static ParseTree ancestor(ParseTree node, int levels) {
    ParseTree current = node;
    for (int i = 0; i < levels && current != null; i++) {
      current = current.getParent();
    }
    return current;
  }
}
