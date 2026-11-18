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

package tools.vitruv.dsls.vitruvocl.symboltable;

import org.antlr.v4.runtime.ParserRuleContext;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Resolves type expressions ({@code Integer}, {@code family::Member}, {@code Set(Real)}, ...) to
 * {@link Type}s, shared by the symbol table builder and the type checker.
 *
 * <p>Primitive keywords map through {@link Type#fromPrimitiveKeyword}, type names through {@link
 * SymbolTable#lookupType}, and collection types wrap their resolved element type ({@code
 * Collection} becomes a set, a missing element type {@code OclAny}). What the resolver cannot
 * resolve goes to the {@link Listener}, which decides the resulting type; a collection whose
 * element type resolves to {@link Type#ERROR} is {@link Type#ERROR} itself.
 */
public final class TypeExpressionResolver {

  /** Decides what unresolvable type expressions become and observes resolved ones. */
  public interface Listener {

    /**
     * Called for a type expression that cannot be resolved.
     *
     * @param ctx the unresolvable node
     * @param message why it cannot be resolved
     * @return the type to use instead
     */
    Type unresolved(ParserRuleContext ctx, String message);

    /**
     * Called for every resolved primitive type, type name and collection type node.
     *
     * @param ctx the resolved node
     * @param type its type
     */
    default void resolved(ParserRuleContext ctx, Type type) {}
  }

  /** Resolves unresolvable type expressions silently to {@link Type#ANY}. */
  public static final Listener LENIENT = (ctx, message) -> Type.ANY;

  private final SymbolTable symbolTable;
  private final Listener listener;

  /**
   * Creates a resolver.
   *
   * @param symbolTable looks up type names
   * @param listener decides about unresolvable type expressions
   */
  public TypeExpressionResolver(SymbolTable symbolTable, Listener listener) {
    this.symbolTable = symbolTable;
    this.listener = listener;
  }

  /**
   * Resolves a type expression: a type name or a type literal.
   *
   * @param ctx the type expression
   * @return its type
   */
  public Type resolve(VitruvOCLParser.TypeExpCSContext ctx) {
    if (ctx.typeNameExpCS() != null) {
      return resolveName(ctx.typeNameExpCS());
    }
    if (ctx.typeLiteralCS() != null) {
      return resolveLiteral(ctx.typeLiteralCS());
    }
    return listener.unresolved(ctx, "Invalid type expression");
  }

  /**
   * Resolves a type literal: a primitive or a collection type.
   *
   * @param ctx the type literal
   * @return its type
   */
  public Type resolveLiteral(VitruvOCLParser.TypeLiteralCSContext ctx) {
    if (ctx.primitiveTypeCS() != null) {
      return resolvePrimitive(ctx.primitiveTypeCS());
    }
    if (ctx.collectionTypeCS() != null) {
      return resolveCollection(ctx.collectionTypeCS());
    }
    return listener.unresolved(ctx, "Invalid type literal");
  }

  /**
   * Resolves a collection type such as {@code Set(Integer)} or {@code Sequence<String>}.
   *
   * @param ctx the collection type
   * @return the collection type, or what the listener decides if the element type or the kind is
   *     unresolvable
   */
  public Type resolveCollection(VitruvOCLParser.CollectionTypeCSContext ctx) {
    String kind = ctx.collectionKind.getText();
    Type elementType = Type.ANY;
    if (ctx.typeExpCS() != null) {
      elementType = resolve(ctx.typeExpCS());
      if (elementType == Type.ERROR) {
        return Type.ERROR;
      }
    }

    Type collectionType =
        switch (kind) {
          case "Set", "Collection" -> Type.set(elementType);
          case "Sequence" -> Type.sequence(elementType);
          case "Bag" -> Type.bag(elementType);
          case "OrderedSet" -> Type.orderedSet(elementType);
          default -> null;
        };
    if (collectionType == null) {
      return listener.unresolved(ctx, "Unknown collection type: " + kind);
    }
    listener.resolved(ctx, collectionType);
    return collectionType;
  }

  /**
   * Resolves a primitive type keyword ({@code Real} becomes {@code Double}, {@code ID} {@code
   * String}, {@code UnlimitedNatural} {@code Integer}, {@code OclAny} {@link Type#ANY}).
   *
   * @param ctx the primitive type
   * @return its type
   */
  public Type resolvePrimitive(VitruvOCLParser.PrimitiveTypeCSContext ctx) {
    String typeName = ctx.getText();
    Type primitiveType = Type.fromPrimitiveKeyword(typeName);
    if (primitiveType == null) {
      return listener.unresolved(ctx, "Unknown primitive type: " + typeName);
    }
    listener.resolved(ctx, primitiveType);
    return primitiveType;
  }

  /**
   * Resolves a qualified ({@code family::Member}) or unqualified ({@code Member}, {@code Double})
   * type name; see {@link SymbolTable#lookupType}.
   *
   * @param ctx the type name
   * @return its type
   */
  public Type resolveName(VitruvOCLParser.TypeNameExpCSContext ctx) {
    String typeName;
    if (ctx.metamodel != null && ctx.className != null) {
      typeName = ctx.metamodel.getText() + "::" + ctx.className.getText();
    } else if (ctx.unqualified != null) {
      typeName = ctx.unqualified.getText();
    } else {
      return listener.unresolved(ctx, "Invalid type name");
    }

    Type resolvedType = symbolTable.lookupType(typeName);
    if (resolvedType == null) {
      return listener.unresolved(ctx, "Unknown type: " + typeName);
    }
    listener.resolved(ctx, resolvedType);
    return resolvedType;
  }
}
