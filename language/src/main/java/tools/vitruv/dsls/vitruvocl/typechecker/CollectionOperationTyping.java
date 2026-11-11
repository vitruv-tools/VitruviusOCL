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

/**
 * Type rules of the collection operations: queries ({@code includes}, {@code size}, ...),
 * modifications ({@code including}, {@code union}, ...), conversions ({@code asSet}, ...) and
 * aggregates ({@code sum}, {@code max}, ...). Extracted from {@link TypeCheckVisitor}, which
 * delegates the corresponding {@code visit*Op} methods here.
 */
final class CollectionOperationTyping {

  private final OperationTypingContext context;

  CollectionOperationTyping(OperationTypingContext context) {
    this.context = context;
  }

  /**
   * Visits an 'includes' operation node in the AST.
   *
   * <p>This method performs type checking for the 'includes' operation, which checks whether a
   * collection contains a given element.
   *
   * <ul>
   *   <li>The operation requires that the receiver type (top of {@code receiverStack}) is a
   *       collection.
   *   <li>If the receiver is not a collection, an error is added and {@link Type#ERROR} is
   *       returned.
   *   <li>If the type is valid, the resulting type of the operation is {@link Type#BOOLEAN}.
   * </ul>
   *
   * <p>The resulting type is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the 'includes' operation node in the AST
   * @return {@link Type#BOOLEAN} if the receiver is a collection; otherwise {@link Type#ERROR}
   */
  Type includes(VitruvOCLParser.IncludesOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'includes' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Visits an 'excludes' operation node in the AST.
   *
   * <p>This method performs type checking for the 'excludes' operation, which checks whether a
   * collection does not contain a given element.
   *
   * <ul>
   *   <li>The operation requires that the receiver type (top of {@code receiverStack}) is a
   *       collection.
   *   <li>If the receiver is not a collection, an error is added and {@link Type#ERROR} is
   *       returned.
   *   <li>If the type is valid, the resulting type of the operation is {@link Type#BOOLEAN}.
   * </ul>
   *
   * <p>The resulting type is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the 'excludes' operation node in the AST
   * @return {@link Type#BOOLEAN} if the receiver is a collection; otherwise {@link Type#ERROR}
   */
  Type excludes(VitruvOCLParser.ExcludesOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "'excludes' requires a collection, ¡T!, or ¿T? receiver");
      return Type.ERROR;
    }

    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code includesAll()} operation.
   *
   * @param ctx The includesAll operation node
   * @return Type.BOOLEAN
   */
  Type includesAll(VitruvOCLParser.IncludesAllOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);
    if (!receiverType.isCollection()) {
      context.error(ctx, "'includesAll' requires collection receiver");
      return Type.ERROR;
    }
    if (!argType.isCollection()) {
      context.error(ctx, "'includesAll' argument must be a collection");
    }
    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code excludesAll()} operation.
   *
   * @param ctx The excludesAll operation node
   * @return Type.BOOLEAN
   */
  Type excludesAll(VitruvOCLParser.ExcludesAllOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);
    if (!receiverType.isCollection()) {
      context.error(ctx, "'excludesAll' requires collection receiver");
      return Type.ERROR;
    }
    if (!argType.isCollection()) {
      context.error(ctx, "'excludesAll' argument must be a collection");
    }
    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code count()} operation.
   *
   * <p>Returns how many times an element occurs in the collection.
   *
   * @param ctx The count operation node
   * @return Type.INTEGER
   */
  Type count(VitruvOCLParser.CountOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "'count' requires collection receiver");
      return Type.ERROR;
    }
    Type argType = context.check(ctx.arg);
    Type elemType = receiverType.getElementType();
    if (!argType.isConformantTo(elemType)
        && !Type.conformsWithUnwrapping(argType, elemType)
        && argType != Type.ERROR) {
      context.warning(
          ctx,
          "count() argument type "
              + argType
              + " incompatible with collection element type "
              + elemType);
    }
    context.recordType(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type-checks the {@code size()} operation on a collection receiver.
   *
   * <p>Verifies that the receiver is a collection type, then registers and returns {@link
   * Type#INTEGER} as the result type.
   *
   * @param ctx the parse tree node for the {@code size()} operation
   * @return {@link Type#INTEGER}; or {@link Type#ERROR} if the receiver is not a collection type
   */
  Type size(VitruvOCLParser.SizeOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "size() requires a collection receiver");
      return Type.ERROR;
    }

    context.recordType(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type checks the {@code isEmpty()} operation.
   *
   * @param ctx The isEmpty operation node
   * @return Type.BOOLEAN
   */
  Type isEmpty(VitruvOCLParser.IsEmptyOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "isEmpty() requires a collection receiver");
      return Type.ERROR;
    }
    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code notEmpty()} operation.
   *
   * @param ctx The notEmpty operation node
   * @return Type.BOOLEAN
   */
  Type notEmpty(VitruvOCLParser.NotEmptyOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isExplicitCollectionType()) {
      context.error(ctx, "notEmpty() requires a collection receiver");
      return Type.ERROR;
    }

    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type-checks the {@code first()} operation on a collection receiver.
   *
   * <p>Verifies that the receiver is a collection type, then registers and returns a singleton of
   * the receiver's element type as the result type.
   *
   * @param ctx the parse tree node for the {@code first()} operation
   * @return a singleton of the receiver's element type; or {@link Type#ERROR} if the receiver is
   *     not a collection type
   */
  Type first(VitruvOCLParser.FirstOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      context.error(
          ctx,
          "first() requires an ordered collection (Sequence or OrderedSet), got: " + receiverType);
      return Type.ERROR;
    }
    Type resultType = Type.singleton(receiverType.getElementType());
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type-checks the {@code last()} operation on a collection receiver.
   *
   * <p>Verifies that the receiver is a collection type, then registers and returns a singleton of
   * the receiver's element type as the result type.
   *
   * @param ctx the parse tree node for the {@code last()} operation
   * @return a singleton of the receiver's element type; or {@link Type#ERROR} if the receiver is
   *     not a collection type
   */
  Type last(VitruvOCLParser.LastOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      context.error(
          ctx,
          "last() requires an ordered collection (Sequence or OrderedSet), got: " + receiverType);
      return Type.ERROR;
    }
    Type resultType = Type.singleton(receiverType.getElementType());
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code at()} operation.
   *
   * <p>Requires ordered collection. Index must be Integer. Returns singleton of element type.
   *
   * @param ctx The at operation node
   * @return Singleton of element type
   */
  Type at(VitruvOCLParser.AtOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    // at() also valid on String (returns a character as String)
    if (receiverType.isConformantTo(Type.STRING)) {
      Type indexType = context.check(ctx.index);
      if (!indexType.isConformantTo(Type.INTEGER)) {
        context.error(ctx, "at() index must be Integer, got " + indexType);
      }
      context.recordType(ctx, Type.STRING);
      return Type.STRING;
    }
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      context.error(ctx, "'at' requires ordered collection or String receiver");
      return Type.ERROR;
    }
    Type indexType = context.check(ctx.index);
    if (!indexType.isConformantTo(Type.INTEGER)) {
      context.error(ctx, "at() index must be Integer, got " + indexType);
    }
    Type resultType = Type.singleton(receiverType.getElementType());
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code including} operation, including its result cardinality.
   *
   * <p>The argument may relate to the receiver in one of two ways (OCL#'s χ case system):
   *
   * <ul>
   *   <li><b>Element case</b>: the argument conforms to the receiver's element type τ (a single
   *       element is added). The upper bound grows by one; the lower bound only grows by one if the
   *       receiver is non-unique (a unique collection may already contain the element).
   *   <li><b>Collection case</b>: the argument itself conforms to the receiver's full collection
   *       type (a whole compatible collection is merged in). Bounds combine additively, again gated
   *       on uniqueness for the lower bound.
   * </ul>
   */
  Type including(VitruvOCLParser.IncludingOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);

    if (!receiverType.isCollection()) {
      context.error(ctx, "'including' requires collection");
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    int lowerBound = receiverType.getLowerBound();
    int upperBound = receiverType.getUpperBound();
    boolean unique = receiverType.isUnique();

    int newLowerBound;
    int newUpperBound;
    // The element case (χ <: τ) and the collection case (χ <: receiverType) are not defined as
    // mutually exclusive in the specification — whenever the collection case's premise holds,
    // τ <: τ (and thus the element case) already holds too by the definition of the <:
    // extension to annotated collection types, so both conditions can be satisfied at once.
    // Which case applies then is underdetermined by the specification itself. This
    // if/else-if ordering (element case checked first) is a deliberate implementation choice,
    // taken 1:1 from the VitruviusOCL reference implementation (Prolog, merge_expr /
    // type_check_oclc for merge/2, using ->/; ), not a rule the specification mandates.
    if (argType.isConformantTo(elemType)) {
      // Fall 1/2: element case — a single new element is added
      newUpperBound = addBound(upperBound, 1);
      newLowerBound = unique ? lowerBound : addBound(lowerBound, 1);
    } else if (argType.isConformantTo(receiverType)) {
      // Fall 3/4: collection case — a whole conformant collection is merged in
      newUpperBound = sumBounds(upperBound, argType.getUpperBound());
      newLowerBound = unique ? lowerBound : sumBounds(lowerBound, argType.getLowerBound());
    } else {
      context.error(ctx, "Incompatible argument type");
      context.recordType(ctx, receiverType);
      return receiverType;
    }

    Type resultType = withBounds(receiverType, newLowerBound, newUpperBound);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Visits an 'excluding' operation node in the AST.
   *
   * <p>This method performs type checking for the 'excluding' operation, which removes an element
   * from a collection.
   *
   * <ul>
   *   <li>The operation requires that the receiver type (top of {@code receiverStack}) is a
   *       collection.
   *   <li>If the receiver is not a collection, an error is added and {@link Type#ERROR} is
   *       returned.
   * </ul>
   *
   * <p>The type of the receiver is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the 'excluding' operation node in the AST
   * @return the type of the collection receiver if valid; otherwise {@link Type#ERROR}
   */
  Type excluding(VitruvOCLParser.ExcludingOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isCollection()) {
      context.error(ctx, "'excluding' requires collection");
      return Type.ERROR;
    }

    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code union()} operation.
   *
   * <p>Combines two collections, computing result type based on collection properties.
   *
   * @param ctx The union operation node
   * @return Combined collection type
   */
  Type union(VitruvOCLParser.UnionOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);

    if (!receiverType.isCollection() || !argType.isCollection()) {
      context.error(ctx, "'union' requires collection operands");
      return Type.ERROR;
    }

    Type commonElemType =
        Type.commonSuperType(receiverType.getElementType(), argType.getElementType());

    boolean bothUnique = receiverType.isUnique() && argType.isUnique();
    boolean anyOrdered = receiverType.isOrdered() || argType.isOrdered();

    Type resultType;
    if (bothUnique && anyOrdered) {
      resultType = Type.orderedSet(commonElemType);
    } else if (bothUnique) {
      resultType = Type.set(commonElemType);
    } else if (anyOrdered) {
      resultType = Type.sequence(commonElemType);
    } else {
      resultType = Type.bag(commonElemType);
    }

    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code intersection()} operation.
   *
   * <p>Both operands must be collections with compatible element types. Result preserves the more
   * restrictive kind (Set beats Bag, OrderedSet beats Sequence).
   *
   * @param ctx The intersection operation node
   * @return Collection of common element type
   */
  Type intersection(VitruvOCLParser.IntersectionOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);
    if (!receiverType.isCollection() || !argType.isCollection()) {
      context.error(ctx, "'intersection' requires collection operands");
      return Type.ERROR;
    }
    Type commonElem = Type.commonSuperType(receiverType.getElementType(), argType.getElementType());
    // intersection result: unique if either operand is unique; ordered only if both are ordered
    boolean eitherUnique = receiverType.isUnique() || argType.isUnique();
    boolean bothOrdered = receiverType.isOrdered() && argType.isOrdered();
    Type resultType;
    if (eitherUnique && bothOrdered) {
      resultType = Type.orderedSet(commonElem);
    } else if (eitherUnique) {
      resultType = Type.set(commonElem);
    } else if (bothOrdered) {
      resultType = Type.sequence(commonElem);
    } else {
      resultType = Type.bag(commonElem);
    }
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code symmetricDifference()} operation.
   *
   * <p>Both operands must be Sets. Result is a Set of common element type.
   *
   * @param ctx The symmetricDifference operation node
   * @return Set of element type
   */
  Type symmetricDifference(VitruvOCLParser.SymmetricDifferenceOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);
    if (!receiverType.isCollection() || !argType.isCollection()) {
      context.error(ctx, "'symmetricDifference' requires collection operands");
      return Type.ERROR;
    }
    if (!receiverType.isUnique() || !argType.isUnique()) {
      // WARNING → ERROR
      context.error(ctx, "'symmetricDifference' requires Set operands (unique collections)");
      return Type.ERROR;
    }

    Type commonElem = Type.commonSuperType(receiverType.getElementType(), argType.getElementType());
    Type resultType = Type.set(commonElem);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Visits an 'append' operation node in the AST.
   *
   * <p>This method performs type checking and result type computation for the 'append' operation,
   * which combines two collections by adding all elements of the argument collection to the
   * receiver collection.
   *
   * <p>The steps are as follows:
   *
   * <ol>
   *   <li>Retrieve the receiver type from {@code receiverStack} and the argument type by visiting
   *       {@code ctx.arg}.
   *   <li>Check that both the receiver and argument are collections; if not, an error is added and
   *       {@link Type#ERROR} is returned.
   *   <li>Compute the common element type of the two collections using {@link
   *       Type#commonSuperType}.
   *   <li>Determine the resulting collection type based on uniqueness and ordering:
   *       <ul>
   *         <li>If both are unique and any is ordered → {@link Type#orderedSet}.
   *         <li>If both are unique → {@link Type#set}.
   *         <li>If any is ordered → {@link Type#sequence}.
   *         <li>Otherwise → {@link Type#bag}.
   *       </ul>
   *   <li>The computed result type is stored in {@code nodeTypes}.
   * </ol>
   *
   * @param ctx the context of the 'append' operation node in the AST
   * @return the resulting collection type if operands are valid; otherwise {@link Type#ERROR}
   */
  Type append(VitruvOCLParser.AppendOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type argType = context.check(ctx.arg);

    if (!receiverType.isCollection()) {
      context.error(ctx, "'append' requires a collection receiver");
      return Type.ERROR;
    }

    if (argType.isCollection()) {
      context.error(ctx, "'append' argument must be a singleton, not a collection");
      return Type.ERROR;
    }

    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code prepend()} operation.
   *
   * <p>Requires Sequence or OrderedSet receiver. Returns same collection type.
   *
   * @param ctx The prepend operation node
   * @return Same ordered collection type
   */
  Type prepend(VitruvOCLParser.PrependOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      context.error(ctx, "'prepend' requires ordered collection (Sequence or OrderedSet)");
      return Type.ERROR;
    }
    Type argType = context.check(ctx.arg);
    Type elemType = receiverType.getElementType();
    if (!argType.isConformantTo(elemType)
        && !Type.conformsWithUnwrapping(argType, elemType)
        && argType != Type.ERROR) {
      context.error(
          ctx,
          "prepend() argument type " + argType + " incompatible with element type " + elemType);
    }
    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code insertAt()} operation.
   *
   * <p>Requires ordered collection. Index must be Integer.
   *
   * @param ctx The insertAt operation node
   * @return Same ordered collection type
   */
  Type insertAt(VitruvOCLParser.InsertAtOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      context.error(ctx, "'insertAt' requires ordered collection (Sequence or OrderedSet)");
      return Type.ERROR;
    }
    Type indexType = context.check(ctx.index);
    if (!indexType.isConformantTo(Type.INTEGER)) {
      context.error(ctx, "insertAt() index must be Integer, got " + indexType);
    }
    Type argType = context.check(ctx.arg);
    Type elemType = receiverType.getElementType();
    if (!argType.isConformantTo(elemType)
        && !Type.conformsWithUnwrapping(argType, elemType)
        && argType != Type.ERROR) {
      context.error(
          ctx,
          "insertAt() element type "
              + argType
              + " incompatible with collection element type "
              + elemType);
    }
    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code subSequence()} operation.
   *
   * <p>Requires ordered collection. Both bounds must be Integer.
   *
   * @param ctx The subSequence operation node
   * @return Same ordered collection type
   */
  Type subSequence(VitruvOCLParser.SubSequenceOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      context.error(ctx, "'subSequence' requires ordered collection (Sequence or OrderedSet)");
      return Type.ERROR;
    }
    Type startType = context.check(ctx.start);
    Type endType = context.check(ctx.end);
    if (!startType.isConformantTo(Type.INTEGER)) {
      context.error(ctx, "subSequence() start index must be Integer, got " + startType);
    }
    if (!endType.isConformantTo(Type.INTEGER)) {
      context.error(ctx, "subSequence() end index must be Integer, got " + endType);
    }
    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code reverse()} operation.
   *
   * @param ctx The reverse operation node
   * @return Same collection type as receiver
   */
  Type reverse(VitruvOCLParser.ReverseOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "reverse() requires collection receiver");
      return Type.ERROR;
    }
    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code flatten()} operation.
   *
   * <p>Requires Collection(Collection(T)), returns Collection(T).
   *
   * @param ctx The flatten operation node
   * @return Flattened collection type
   */
  Type flatten(VitruvOCLParser.FlattenOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isCollection()) {
      context.error(ctx, "'flatten' requires collection");
      return Type.ERROR;
    }

    Type innerType = receiverType.getElementType();
    if (!innerType.isCollection()) {
      context.error(ctx, "Expected Collection(Collection(T))");
      return Type.ERROR;
    }

    Type flatElementType = innerType.getElementType();
    Type resultType = Type.preserveCollectionKind(receiverType, flatElementType);

    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code lift()} operation.
   *
   * <p>Wraps collection in another collection: Collection(T) → Collection(Collection(T))
   *
   * @param ctx The lift operation node
   * @return Nested collection type
   */
  Type lift(VitruvOCLParser.LiftOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isCollection()) {
      context.error(ctx, "'lift' requires collection");
      return Type.ERROR;
    }

    Type resultType = Type.preserveCollectionKind(receiverType, receiverType);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code asSet()} operation.
   *
   * @param ctx The asSet operation node
   * @return Set of element type
   */
  Type asSet(VitruvOCLParser.AsSetOpContext ctx) {
    return conversion(ctx, "asSet", Type::set);
  }

  /**
   * Type checks the {@code asBag()} operation.
   *
   * @param ctx The asBag operation node
   * @return Bag of element type
   */
  Type asBag(VitruvOCLParser.AsBagOpContext ctx) {
    return conversion(ctx, "asBag", Type::bag);
  }

  /**
   * Type checks the {@code asSequence()} operation.
   *
   * @param ctx The asSequence operation node
   * @return Sequence of element type
   */
  Type asSequence(VitruvOCLParser.AsSequenceOpContext ctx) {
    return conversion(ctx, "asSequence", Type::sequence);
  }

  /**
   * Type checks the {@code asOrderedSet()} operation.
   *
   * @param ctx The asOrderedSet operation node
   * @return OrderedSet of element type
   */
  Type asOrderedSet(VitruvOCLParser.AsOrderedSetOpContext ctx) {
    return conversion(ctx, "asOrderedSet", Type::orderedSet);
  }

  /**
   * Type checks the {@code sum()} collection operation.
   *
   * <p>Requires a numeric collection receiver (elements must be INTEGER, FLOAT, or DOUBLE). The
   * result type is a singleton of the element type (preserves FLOAT for Float collections rather
   * than always returning INTEGER).
   *
   * @param ctx The sum operation node
   * @return Singleton of the element numeric type (INTEGER / FLOAT / DOUBLE)
   */
  Type sum(VitruvOCLParser.SumOpContext ctx) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isCollection()) {
      context.error(ctx, "'sum' requires collection");
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (elemType != Type.ANY && !TypeResolver.isNumeric(elemType)) {
      context.error(ctx, "'sum' requires numeric collection");
    }

    // Preserve the concrete numeric element type (FLOAT stays FLOAT, not INTEGER)
    Type resultType = Type.singleton(TypeResolver.isNumeric(elemType) ? elemType : Type.INTEGER);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Visits a 'max' operation node in the AST.
   *
   * <p>This method delegates to {@link #aggregate} to perform type checking and result type
   * computation for the 'max' aggregate operation over a collection.
   *
   * @param ctx the context of the 'max' operation node in the AST
   * @return the resulting type of the aggregate operation
   */
  Type max(VitruvOCLParser.MaxOpContext ctx) {
    return aggregate(ctx, "max");
  }

  /**
   * Visits a 'min' operation node in the AST.
   *
   * <p>This method delegates to {@link #aggregate} to perform type checking and result type
   * computation for the 'min' aggregate operation over a collection.
   *
   * @param ctx the context of the 'min' operation node in the AST
   * @return the resulting type of the aggregate operation
   */
  Type min(VitruvOCLParser.MinOpContext ctx) {
    return aggregate(ctx, "min");
  }

  /**
   * Visits an 'avg' operation node in the AST.
   *
   * <p>This method delegates to {@link #aggregate} to perform type checking and result type
   * computation for the 'avg' aggregate operation over a collection.
   *
   * @param ctx the context of the 'avg' operation node in the AST
   * @return the resulting type of the aggregate operation
   */
  Type avg(VitruvOCLParser.AvgOpContext ctx) {
    return aggregate(ctx, "avg");
  }

  /** Helper for asSet/asBag/asSequence/asOrderedSet — all follow the same pattern. */
  private Type conversion(
      ParserRuleContext ctx, String opName, java.util.function.UnaryOperator<Type> factory) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    if (!receiverType.isCollection()) {
      context.error(ctx, "'" + opName + "' requires collection receiver");
      return Type.ERROR;
    }
    Type resultType = factory.apply(receiverType.getElementType());
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Helper for aggregate operation type checking ({@code min}, {@code max}, {@code avg}).
   *
   * <p>Accepts both multi-valued collections and singleton ¡T! receivers. A singleton is a
   * degenerate collection of one element, so min/max/avg are valid on it.
   *
   * @param ctx the operation node
   * @param opName operation name for error messages
   * @return singleton of the element type, or {@link Type#ERROR}
   */
  private Type aggregate(ParserRuleContext ctx, String opName) {
    Type receiverType = context.receiver();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    // Reject singletons — max/min/avg require a collection, not a scalar
    if (receiverType.isSingleton() || !receiverType.isCollection()) {
      context.error(ctx, "'" + opName + "' requires collection receiver");
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (elemType.isSingleton()) {
      elemType = elemType.getElementType();
    }

    if (elemType != Type.ANY && !TypeResolver.isNumeric(elemType)) {
      context.error(ctx, "'" + opName + "' requires numeric collection");
    }

    Type resultType = Type.singleton(TypeResolver.isNumeric(elemType) ? elemType : Type.INTEGER);
    context.recordType(ctx, resultType);
    return resultType;
  }

  /** Adds {@code delta} to {@code bound}, propagating {@link Type#UNBOUNDED} unchanged. */
  private static int addBound(int bound, int delta) {
    return bound == Type.UNBOUNDED ? Type.UNBOUNDED : bound + delta;
  }

  /** Sums two bounds, propagating {@link Type#UNBOUNDED} if either operand is unbounded. */
  private static int sumBounds(int a, int b) {
    return (a == Type.UNBOUNDED || b == Type.UNBOUNDED) ? Type.UNBOUNDED : a + b;
  }

  /** Rebuilds {@code collectionType} with the same element type/multiplicity but new bounds. */
  private static Type withBounds(Type collectionType, int lowerBound, int upperBound) {
    Type elementType = collectionType.getElementType();
    return switch (collectionType.getMultiplicity()) {
      case SET -> Type.set(elementType, lowerBound, upperBound);
      case BAG -> Type.bag(elementType, lowerBound, upperBound);
      case SEQUENCE -> Type.sequence(elementType, lowerBound, upperBound);
      case ORDERED_SET -> Type.orderedSet(elementType, lowerBound, upperBound);
      default -> collectionType;
    };
  }
}
