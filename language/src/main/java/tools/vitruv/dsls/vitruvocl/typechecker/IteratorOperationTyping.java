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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.symboltable.Scope;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;

/**
 * Type rules of the iterator operations ({@code select}, {@code collect}, {@code forAll}, {@code
 * iterate}, ...): each enters the scope Pass 1 recorded for it, types its iterator variables and
 * checks the body. Extracted from {@link TypeCheckVisitor}, which delegates the corresponding
 * {@code visit*Op} methods here.
 */
final class IteratorOperationTyping {

  private final OperationTypingContext context;

  IteratorOperationTyping(OperationTypingContext context) {
    this.context = context;
  }

  /**
   * Type checks the {@code select()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton — each element drawn from a
   * collection is a singleton value.
   *
   * @param ctx The select operation node
   * @return Same collection type as receiver
   */
  @SuppressWarnings("java:S3776")
  Type select(VitruvOCLParser.SelectOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'select' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      context.error(ctx, "select requires at least one iterator variable");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the receiver's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "select",
        bodyType -> {
          Type checkType = bodyType.isCollection() ? bodyType.getElementType() : bodyType;

          if (!checkType.isConformantTo(Type.BOOLEAN)) {
            context.error(ctx, "select body must return Boolean");
          }

          // select on ¡T! may filter out the element → result is ¿T?
          Type resultType = receiverType.isSingleton() ? Type.optional(elemType) : receiverType;
          context.recordType(ctx, resultType);
          return resultType;
        });
  }

  /**
   * Type checks the {@code reject()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton)
   *
   * @param ctx The reject operation node
   * @return Same collection type as receiver
   */
  @SuppressWarnings("java:S3776")
  Type reject(VitruvOCLParser.RejectOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'reject' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      context.error(ctx, "reject requires at least one iterator variable");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the receiver's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "reject",
        bodyType -> {
          if (!bodyType.getElementType().equals(Type.BOOLEAN)) {
            context.error(ctx, "reject predicate must return Boolean");
            return Type.ERROR;
          }

          // reject on ¡T! may filter out the element → result is ¿T?
          Type resultType = receiverType.isSingleton() ? Type.optional(elemType) : receiverType;
          context.recordType(ctx, resultType);
          return resultType;
        });
  }

  /**
   * Type checks the {@code collect()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton)
   *
   * @param ctx The collect operation node
   * @return Collection of the body expression type
   */
  @SuppressWarnings("java:S3776")
  Type collect(VitruvOCLParser.CollectOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'collect' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      context.error(ctx, "collect requires at least one iterator variable");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the collection's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "collect",
        bodyType -> {
          if (bodyType == Type.ERROR) {
            return Type.ERROR;
          }

          // element type from the body; ¡T! unwraps to T (collection bodies stay nested and are
          // flattened explicitly with flatten())
          Type resultElemType = bodyType.isSingleton() ? bodyType.getElementType() : bodyType;
          Type resultType;
          if (receiverType.isSingleton()) {
            resultType = Type.singleton(resultElemType); // ¡T! → ¡U!
          } else if (receiverType.isOptional()) {
            resultType = Type.optional(resultElemType); // ¿T? → ¿U?
          } else if (receiverType.isOrdered()) {
            resultType = Type.sequence(resultElemType); // Sequence/OrderedSet → Sequence
          } else {
            resultType = Type.bag(resultElemType); // Set/Bag → Bag: results may repeat
          }
          context.recordType(ctx, resultType);
          return resultType;
        });
  }

  /**
   * Type checks the {@code collectNested()} iterator.
   *
   * <p>Like collect() but does NOT flatten — returns a collection of collections.
   *
   * @param ctx The collectNested operation node
   * @return Collection of body types (not flattened)
   */
  Type collectNested(VitruvOCLParser.CollectNestedOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "'collectNested' requires collection");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "collectNested",
        bodyType -> {
          // collectNested: preserve receiver kind, element type is the raw body type (no unwrap)
          Type resultType = Type.preserveCollectionKind(receiverType, bodyType);
          context.recordType(ctx, resultType);
          return resultType;
        });
  }

  /**
   * Type checks the {@code forAll()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton) — each element drawn from a
   * collection is a singleton value.
   *
   * @param ctx the context of the 'forAll' operation node in the AST
   * @return {@link Type#BOOLEAN} if successful; otherwise {@link Type#ERROR}
   */
  @SuppressWarnings("java:S3776")
  Type forAll(VitruvOCLParser.ForAllOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'forAll' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      context.error(ctx, "forAll requires at least one iterator variable");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the receiver's element type
    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "forAll",
        bodyType -> {
          if (!bodyType.getElementType().equals(Type.BOOLEAN)) {
            context.error(ctx, "forAll predicate must return Boolean");
            return Type.ERROR;
          }

          context.recordType(ctx, Type.BOOLEAN);
          return Type.BOOLEAN;
        });
  }

  /**
   * Type checks the {@code exists()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton) per.
   *
   * @param ctx the context of the 'exists' operation node in the AST
   * @return {@link Type#BOOLEAN} if successful; otherwise {@link Type#ERROR}
   */
  @SuppressWarnings("java:S3776")
  Type exists(VitruvOCLParser.ExistsOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'exists' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      context.error(ctx, "exists requires at least one iterator variable");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the receiver's element type
    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "exists",
        bodyType -> {
          if (!bodyType.getElementType().equals(Type.BOOLEAN)) {
            context.error(ctx, "exists predicate must return Boolean");
            return Type.ERROR;
          }

          context.recordType(ctx, Type.BOOLEAN);
          return Type.BOOLEAN;
        });
  }

  /**
   * Type checks the {@code one()} iterator.
   *
   * <p>Returns true iff exactly one element satisfies the predicate.
   *
   * @param ctx The one operation node
   * @return Type.BOOLEAN
   */
  Type one(VitruvOCLParser.OneOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "'one' requires collection");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "one",
        bodyType -> {
          Type checkType = bodyType.isCollection() ? bodyType.getElementType() : bodyType;
          if (!checkType.isConformantTo(Type.BOOLEAN)) {
            context.error(ctx, "one() predicate must return Boolean");
          }
          context.recordType(ctx, Type.BOOLEAN);
          return Type.BOOLEAN;
        });
  }

  /**
   * Type checks the {@code any()} iterator.
   *
   * <p>Returns an arbitrary element satisfying the predicate — result is a singleton of the element
   * type.
   *
   * @param ctx The any operation node
   * @return Singleton of element type
   */
  Type any(VitruvOCLParser.AnyOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'any' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "any",
        bodyType -> {
          Type checkType = bodyType.isCollection() ? bodyType.getElementType() : bodyType;
          if (!checkType.isConformantTo(Type.BOOLEAN)) {
            context.error(ctx, "any() predicate must return Boolean");
          }
          // any() returns ¿T? — may find no matching element
          Type resultType = Type.optional(receiverType.getElementType());
          context.recordType(ctx, resultType);
          return resultType;
        });
  }

  /**
   * Type checks the {@code isUnique()} iterator.
   *
   * <p>Returns true iff all body results are distinct.
   *
   * @param ctx The isUnique operation node
   * @return Type.BOOLEAN
   */
  Type isUnique(VitruvOCLParser.IsUniqueOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "'isUnique' requires collection");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    Scope iterScope = context.scopes().getScope(ctx);
    if (iterScope == null) {
      context.error(ctx, TypeCheckVisitor.ERR_NO_SCOPE);
      return Type.ERROR;
    }

    context.symbolTable().enterScope(iterScope);
    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = context.symbolTable().resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) {
          symbol.setType(iterVarType);
        }
      }
      context.check(ctx.body); // body type unconstrained — just check it parses
      context.recordType(ctx, Type.BOOLEAN);
      return Type.BOOLEAN;
    } finally {
      context.symbolTable().exitScope();
    }
  }

  /**
   * Type checks the {@code sortedBy()} iterator.
   *
   * <p>Body must return a comparable type. Result is a Sequence for Bag/Sequence receivers and an
   * OrderedSet otherwise (see {@link Type#sortedByResult}).
   *
   * @param ctx The sortedBy operation node
   * @return Sequence or OrderedSet of element type
   */
  Type sortedBy(VitruvOCLParser.SortedByOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "'sortedBy' requires collection");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    return checkInIteratorScope(
        ctx,
        iterVars,
        iterVarType,
        ctx.body,
        "sortedBy",
        bodyType -> {
          // body should return something comparable (numeric or string)
          Type checkType = bodyType.isSingleton() ? bodyType.getElementType() : bodyType;
          if (checkType != Type.ANY
              && !TypeResolver.isNumeric(checkType)
              && !checkType.isConformantTo(Type.STRING)
              && !checkType.isEnumType()
              && checkType != Type.ERROR) {
            context.error(
                ctx,
                "sortedBy() body must return a comparable type (numeric or String), got: "
                    + checkType);
          }
          // Bag/Sequence keep duplicates -> Sequence; Set/OrderedSet -> OrderedSet (OCL standard)
          Type resultType = Type.sortedByResult(receiverType);
          context.recordType(ctx, resultType);
          return resultType;
        });
  }

  /**
   * Type checks the {@code iterate()} operation.
   *
   * <p>Syntax: {@code coll.iterate(elem; acc : T = init | body)} The accumulator type is the result
   * type.
   *
   * @param ctx The iterate operation node
   * @return The accumulator type
   */
  @SuppressWarnings("java:S3776")
  Type iterate(VitruvOCLParser.IterateOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "'iterate' requires collection");
      return Type.ERROR;
    }

    Scope iterScope = context.scopes().getScope(ctx);
    if (iterScope == null) {
      context.error(ctx, TypeCheckVisitor.ERR_NO_SCOPE);
      return Type.ERROR;
    }

    context.symbolTable().enterScope(iterScope);
    try {
      VitruvOCLParser.IterateVarSpecContext spec = ctx.iterateVarSpec();

      // Iterator variable — gets element type of receiver
      String iterVarName = spec.iterVar.getText();
      Type iterVarType = normalizeToSingleton(receiverType.getElementType());
      VariableSymbol iterSym = context.symbolTable().resolveVariable(iterVarName);
      if (iterSym != null && iterSym.getType() == Type.ANY) {
        iterSym.setType(iterVarType);
      }

      // Accumulator type — either explicit or inferred from the init expression, which is
      // checked once and may not refer to the accumulator itself
      String accVarName = spec.accVar.getText();
      VariableSymbol accSym = context.symbolTable().resolveVariable(accVarName);
      // evaluated before the iterator variable and the accumulator are bound
      Type initType = context.checkInitializer(spec.accInit, accSym, iterSym);
      Type accType = spec.accType != null ? context.check(spec.accType) : initType;
      if (accSym != null && accSym.getType() == Type.ANY) {
        accSym.setType(accType);
      }

      // Init expression must conform to accumulator type
      if (!initType.isConformantTo(accType) && !Type.conformsWithUnwrapping(initType, accType)) {
        context.error(
            spec,
            "iterate() accumulator init type "
                + initType
                + " does not conform to declared type "
                + accType);
      }

      // Body must conform to accumulator type
      if (!requireBody(ctx, ctx.body, "iterate")) {
        return Type.ERROR;
      }
      Type bodyType = context.check(ctx.body);
      if (bodyType == null || bodyType == Type.ERROR) {
        return Type.ERROR;
      }
      if (!bodyType.isConformantTo(accType)
          && !Type.conformsWithUnwrapping(bodyType, accType)
          && bodyType != Type.ERROR) {
        context.error(
            ctx,
            "iterate() body type " + bodyType + " does not conform to accumulator type " + accType);
      }

      context.recordType(ctx, accType);
      return accType;
    } finally {
      context.symbolTable().exitScope();
    }
  }

  /**
   * Shared core of the iterator type rules: enters the scope Pass 1 recorded for {@code ctx}, types
   * the still-untyped iterator variables as {@code iterVarType}, checks that the body is present
   * and well-typed, and derives the operation's result type from the body type via {@code
   * resultRule}. The scope is left again in every case.
   *
   * @param ctx the iterator operation node
   * @param iterVars the declared iterator variable names
   * @param iterVarType the type each iterator variable gets
   * @param body the body expression (may be missing after ANTLR error recovery)
   * @param opName operation name for error messages
   * @param resultRule computes (and records) the result type from the body type
   * @return the result type, or {@link Type#ERROR} if any check failed
   */
  private Type checkInIteratorScope(
      ParserRuleContext ctx,
      List<String> iterVars,
      Type iterVarType,
      ParserRuleContext body,
      String opName,
      Function<Type, Type> resultRule) {
    Scope iterScope = context.scopes().getScope(ctx);
    if (iterScope == null) {
      context.error(ctx, TypeCheckVisitor.ERR_NO_SCOPE);
      return Type.ERROR;
    }

    context.symbolTable().enterScope(iterScope);

    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = context.symbolTable().resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) {
          symbol.setType(iterVarType);
        }
      }

      if (!requireBody(ctx, body, opName)) {
        return Type.ERROR;
      }
      Type bodyType = context.check(body);
      if (bodyType == null || bodyType == Type.ERROR) {
        return Type.ERROR;
      }
      return resultRule.apply(bodyType);
    } finally {
      context.symbolTable().exitScope();
    }
  }

  /**
   * Checks that {@code ctx.body} is non-null (i.e., the user wrote the {@code | body} part).
   *
   * <p>When someone writes e.g. {@code exists(A)} instead of {@code exists(A | A.active)}, ANTLR
   * error-recovery produces a tree where {@code ctx.body == null}. Without this guard the visitor
   * would NPE or silently cascade a bogus type error to the surrounding {@code implies}.
   *
   * @param ctx the iterator operation context
   * @param opName operation name for the message (e.g. {@code "exists"})
   * @return {@code true} if the body is present and valid; {@code false} when an error was reported
   */
  private boolean requireBody(
      ParserRuleContext ctx, org.antlr.v4.runtime.ParserRuleContext body, String opName) {
    // A missing-body situation produces a non-null but empty/invalid context:
    // ANTLR error recovery leaves ctx.body with 0 children (empty expCS),
    // or with an exception, or with error nodes.
    boolean invalid =
        body == null
            || body.exception != null
            || TypeCheckVisitor.hasErrorNode(body)
            || body.getChildCount() == 0;
    if (invalid) {
      context.error(
          ctx,
          "'"
              + opName
              + "' requires 'variable | expression' — "
              + "did you forget the '| body' part? "
              + "Example: "
              + opName
              + "(x | x.active)");
      return false;
    }
    return true;
  }

  /**
   * Normalizes a bare type to it singleton ctype ¡T!.
   *
   * <p>every expression has a ctype χ = τ[l,r](μ,ω). Bare primitive types (INTEGER, STRING, etc.)
   * and bare metaclass types are implicitly ¡T![1,1]. This method makes that wrapping explicit so
   * all downstream operations can rely on it. Multi-valued collections ({T}, [T], etc.) are
   * returned unchanged since they are already proper ctypes.
   *
   * @param t the type to normalize
   * @return ¡t! if t is a bare scalar or metaclass, t unchanged if already a proper ctype
   */
  private Type normalizeToSingleton(Type t) {
    if (t == Type.ERROR || t == Type.ANY) {
      return t;
    }
    if (t.isCollection()) {
      return t;
    } // {T}, [T], <T>, {{T}} — already proper ctype
    if (t.isSingleton()) {
      return t;
    } // !T! — already wrapped
    if (t.isOptional()) {
      return t;
    } // ?T? — already wrapped
    return Type.singleton(t); // bare INTEGER, STRING, cad::Sphere → !T!
  }
}
