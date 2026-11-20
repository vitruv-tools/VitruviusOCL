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

package tools.vitruv.dsls.vitruvocl.typechecker;

import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Type rules of the transaction-related constructs that are only valid in {@code post} blocks (see
 * {@link ConstraintSectionKind}): the {@code @pre} postfix and the {@code OCLisNew}/{@code
 * OCLisModified}/{@code OCLisDeleted} lifecycle predicates. Extracted from {@link
 * TypeCheckVisitor}, which delegates the corresponding {@code visit*} methods here.
 */
final class LifecycleTyping {

  private final OperationTypingContext context;

  LifecycleTyping(OperationTypingContext context) {
    this.context = context;
  }

  /**
   * Type checks the {@code @pre} postfix operator ({@code self.attr@pre}).
   *
   * <p>{@code @pre} is type-neutral: it doesn't change the type of the navigation step it follows,
   * only the point in time the value is read from (transaction start vs. current). Restrictions
   * (both deliberate, not grammar-enforced):
   *
   * <ul>
   *   <li>Only valid inside {@code post} blocks.
   *   <li>Must directly follow a property access — {@code self.attr@pre} is valid, bare {@code
   *       self@pre} is not, since there is no "old state" for a receiver that wasn't itself a
   *       navigated feature.
   * </ul>
   *
   * @param ctx the navigation chain node whose {@code AT_PRE()} terminal is non-null
   * @return the unchanged receiver type, or Type.ERROR if misplaced
   */
  Type atPre(VitruvOCLParser.NavigationChainCSContext ctx) {
    if (context.section() != ConstraintSectionKind.POST) {
      context.error(ctx.AT_PRE().getSymbol(), "'@pre' is only valid inside 'post' blocks");
      context.recordType(ctx, Type.ERROR);
      return Type.ERROR;
    }
    if (!previousNavWasPropertyAccess(ctx)) {
      context.error(
          ctx.AT_PRE().getSymbol(),
          "'@pre' must directly follow a property access, e.g. 'self.attr@pre'");
      context.recordType(ctx, Type.ERROR);
      return Type.ERROR;
    }
    Type receiverType = context.receiver();
    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks {@code OCLisNew} (bare or with a {@code (attr => val, ...)} aggregate).
   *
   * @param ctx The OCLisNew predicate node
   * @return see {@link #checkReceiver}
   */
  Type isNew(VitruvOCLParser.OclIsNewPredContext ctx) {
    Type resultType = checkReceiver(ctx, "OCLisNew");
    if (resultType != Type.ERROR) {
      checkAggregateArgs(ctx.lifecycleAggregateArgs(), context.receiver());
    }
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks {@code OCLisModified} (bare or with a {@code (attr => val, ...)} aggregate). The
   * aggregate values are target/post-state values, not deltas — see grammar documentation.
   *
   * @param ctx The OCLisModified predicate node
   * @return see {@link #checkReceiver}
   */
  Type isModified(VitruvOCLParser.OclIsModifiedPredContext ctx) {
    Type resultType = checkReceiver(ctx, "OCLisModified");
    if (resultType != Type.ERROR) {
      checkAggregateArgs(ctx.lifecycleAggregateArgs(), context.receiver());
    }
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks {@code OCLisDeleted}. No aggregate form exists — the grammar itself rules it out.
   *
   * @param ctx The OCLisDeleted predicate node
   * @return see {@link #checkReceiver}
   */
  Type isDeleted(VitruvOCLParser.OclIsDeletedPredContext ctx) {
    Type resultType = checkReceiver(ctx, "OCLisDeleted");
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Validates a lifecycle predicate's placement (post-only) and receiver type (must be an object
   * type, singular or collection).
   *
   * @param ctx the predicate node, used for error positions
   * @param predName the predicate's display name, for error messages
   * @return {@code !Boolean!} for a singleton/bare receiver, a same-kind Boolean collection for a
   *     multi-valued receiver, or Type.ERROR if misplaced/mistyped
   */
  private Type checkReceiver(ParserRuleContext ctx, String predName) {
    if (context.section() != ConstraintSectionKind.POST) {
      context.error(ctx, "'" + predName + "' is only valid inside 'post' blocks");
      return Type.ERROR;
    }
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!elementType(receiverType).isMetaclassType()) {
      context.error(ctx, "'" + predName + "' requires an object receiver, got " + receiverType);
      return Type.ERROR;
    }
    if (receiverType.isCollection() && !receiverType.isSingleton()) {
      return Type.preserveCollectionKind(receiverType, Type.BOOLEAN);
    }
    return Type.singleton(Type.BOOLEAN);
  }

  /**
   * Type checks the named-argument aggregate call form ({@code OCLisNew(attr => val, ...)}).
   * Resolves each attribute against the receiver's EClass and checks the value expression's type
   * conformance. Deliberately does NOT require every attribute to be listed (no completeness check
   * — partial aggregates are valid per spec).
   *
   * @param argsCtx the aggregate argument list, or {@code null} if the bare (no-parens) form was
   *     used
   * @param receiverType the (already-validated) object receiver type
   */
  private void checkAggregateArgs(
      VitruvOCLParser.LifecycleAggregateArgsContext argsCtx, Type receiverType) {
    if (argsCtx == null) {
      return;
    }
    Type elementType = elementType(receiverType);
    if (!elementType.isMetaclassType()) {
      return; // receiver error already reported by checkReceiver
    }
    EClass eClass = elementType.getEClass();
    for (VitruvOCLParser.LifecycleAggregateArgContext arg : argsCtx.lifecycleAggregateArg()) {
      String attrName = arg.attr.getText();
      EStructuralFeature feature = eClass.getEStructuralFeature(attrName);
      if (feature == null) {
        feature = FeatureTypeMapper.findFeatureInSubtypes(eClass, attrName);
      }
      Type valueType = context.check(arg.value);
      if (feature == null) {
        context.error(arg.attr, TypeCheckVisitor.unknownPropertyMessage(attrName, eClass));
        continue;
      }
      if (valueType == Type.ERROR) {
        continue; // already reported
      }
      Type featureType = FeatureTypeMapper.featureType(feature);
      Type expectedElem =
          (featureType.isSingleton() || featureType.isOptional())
              ? featureType.getElementType()
              : featureType;
      Type actualElem =
          (valueType.isSingleton() || valueType.isOptional())
              ? valueType.getElementType()
              : valueType;
      if (!actualElem.isConformantTo(expectedElem)) {
        context.error(
            arg.value,
            "Type mismatch: attribute '"
                + attrName
                + "' expects "
                + featureType
                + ", got "
                + valueType);
      }
    }
  }

  /** The element type of a singleton, optional or collection receiver, else the type itself. */
  private static Type elementType(Type receiverType) {
    return (receiverType.isSingleton() || receiverType.isOptional() || receiverType.isCollection())
        ? receiverType.getElementType()
        : receiverType;
  }

  /**
   * Returns {@code true} if the navigation-chain step immediately before {@code ctx} is a plain
   * property access.
   */
  private static boolean previousNavWasPropertyAccess(
      VitruvOCLParser.NavigationChainCSContext ctx) {
    List<VitruvOCLParser.NavigationChainCSContext> chain = enclosingNavigationChain(ctx);
    int idx = chain.indexOf(ctx);
    if (idx <= 0) {
      return false; // first step in the chain — no preceding property access
    }
    VitruvOCLParser.NavigationChainCSContext prev = chain.get(idx - 1);
    return prev.navigationTargetCS() instanceof VitruvOCLParser.PropertyNavContext;
  }

  /**
   * Returns the enclosing {@code navigationChainCS} list that {@code navChainCtx} is an element of
   * — either a {@code primaryWithNav}'s or a {@code prefixedQualified}'s navigation chain.
   */
  private static List<VitruvOCLParser.NavigationChainCSContext> enclosingNavigationChain(
      ParserRuleContext navChainCtx) {
    ParseTree parent = navChainCtx.getParent();
    if (parent instanceof VitruvOCLParser.PrimaryWithNavContext p) {
      return p.navigationChainCS();
    }
    if (parent instanceof VitruvOCLParser.PrefixedQualifiedContext p) {
      return p.navigationChainCS();
    }
    return List.of();
  }
}
