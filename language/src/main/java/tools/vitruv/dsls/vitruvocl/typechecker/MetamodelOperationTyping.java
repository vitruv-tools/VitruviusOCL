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

import org.antlr.v4.runtime.ParserRuleContext;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Type rules of the operations on metaclasses and runtime types: {@code allInstances()}, {@code
 * oclIsKindOf}, {@code oclIsTypeOf} and {@code oclAsType}. Extracted from {@link TypeCheckVisitor},
 * which delegates the corresponding {@code visit*Op} methods here.
 */
final class MetamodelOperationTyping {

  private final OperationTypingContext context;

  MetamodelOperationTyping(OperationTypingContext context) {
    this.context = context;
  }

  /**
   * Type checks {@code allInstances()}: requires a metaclass receiver, e.g. {@code
   * Person.allInstances()} is {@code OrderedSet(Person)}.
   *
   * @param ctx The allInstances operation node
   * @return OrderedSet(MetaclassType), or Type.ERROR
   */
  Type allInstances(VitruvOCLParser.AllInstancesOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isMetaclassType()) {
      context.error(ctx, "allInstances() requires metaclass receiver");
      return Type.ERROR;
    }

    Type resultType = Type.orderedSet(receiverType);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks {@code oclIsKindOf(T)}, see {@link #typeTest}.
   *
   * @param ctx The oclIsKindOf operation node
   * @return {@code !Boolean!} or a Boolean collection, see {@link #typeTest}
   */
  Type isKindOf(VitruvOCLParser.OclIsKindOfOpContext ctx) {
    return typeTest(ctx, ctx.type);
  }

  /**
   * Type checks {@code oclIsTypeOf(T)}, see {@link #typeTest}.
   *
   * @param ctx The oclIsTypeOf operation node
   * @return {@code !Boolean!} or a Boolean collection, see {@link #typeTest}
   */
  Type isTypeOf(VitruvOCLParser.OclIsTypeOfOpContext ctx) {
    return typeTest(ctx, ctx.type);
  }

  /**
   * Type checks {@code oclAsType(T)}: casts the receiver to the target type while preserving its
   * multiplicity, only the member type τ is replaced:
   *
   * <ul>
   *   <li>{@code ¡Shape! .oclAsType(Box)} → {@code ¡Box!}
   *   <li>{@code {Shape} .oclAsType(Box)} → {@code {Box}}
   *   <li>{@code [Real] .oclAsType(Set(Integer))} → {@code [Integer]} (a collection target only
   *       contributes its member type, as at runtime)
   * </ul>
   *
   * <p>A cast involving a primitive type whose member types are unrelated (neither conforms to the
   * other) is an error; casts between metaclasses are checked at runtime.
   *
   * @param ctx the oclAsType operation node
   * @return the cast type with preserved multiplicity, or {@link Type#ERROR}
   */
  Type asType(VitruvOCLParser.OclAsTypeOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    Type targetType = context.check(ctx.type);

    if (!isPossibleCast(receiverType, targetType)) {
      context.error(ctx, "Cannot cast " + receiverType + " to unrelated type " + targetType);
      context.recordType(ctx, Type.ERROR);
      return Type.ERROR;
    }
    if (targetType.isCollection() && !targetType.isSingleton()) {
      // the evaluator casts the members and keeps the receiver's kind: only the member counts
      targetType = memberType(targetType);
    }

    // Bare metaclass types are implicitly ¡T! (singleton)
    Type resultType =
        receiverType.isCollection() && !receiverType.isSingleton()
            ? Type.preserveCollectionKind(receiverType, targetType)
            : Type.singleton(targetType);

    context.recordType(ctx, resultType);
    context.recordType(ctx.type, targetType);
    return resultType;
  }

  /**
   * Type checks a type test ({@code oclIsKindOf}/{@code oclIsTypeOf}): a single {@code !Boolean!}
   * for a singleton {@code !T!} or bare metaclass receiver, one Boolean per element (same
   * collection kind) for a multi-valued receiver. A singleton must not be wrapped in a set first,
   * or the result would be {@code {Boolean}} instead of {@code !Boolean!}.
   */
  private Type typeTest(ParserRuleContext ctx, VitruvOCLParser.TypeExpCSContext type) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    Type targetType = context.check(type);

    Type resultType =
        receiverType.isCollection() && !receiverType.isSingleton()
            ? Type.preserveCollectionKind(receiverType, Type.BOOLEAN)
            : Type.singleton(Type.BOOLEAN);

    context.recordType(ctx, resultType);
    context.recordType(type, targetType);
    return resultType;
  }

  /**
   * Whether a value of {@code source} can be an instance of {@code target}. Casts between two
   * metaclasses are always possible statically (EMF has multiple inheritance, and every class is an
   * {@code EObject}), so only casts involving a primitive type are checked: their member types must
   * be related by conformance in either direction, or one of them is untyped.
   */
  private static boolean isPossibleCast(Type source, Type target) {
    Type from = memberType(source);
    Type to = memberType(target);
    if (from == Type.ERROR || to == Type.ERROR || from == Type.ANY || to == Type.ANY) {
      return true;
    }
    if (from.isMetaclassType() && to.isMetaclassType()) {
      return true;
    }
    return from.isConformantTo(to) || to.isConformantTo(from);
  }

  /** The member type of {@code type}: unwraps singleton, optional and collection types. */
  private static Type memberType(Type type) {
    Type member = type;
    while (member.isSingleton() || member.isOptional() || member.isCollection()) {
      Type element = member.getElementType();
      if (element == member) {
        break;
      }
      member = element;
    }
    return member;
  }
}
