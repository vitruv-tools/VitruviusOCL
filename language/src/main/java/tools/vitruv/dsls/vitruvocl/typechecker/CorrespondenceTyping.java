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
import org.antlr.v4.runtime.Token;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;
import tools.vitruv.dsls.vitruvocl.types.Multiplicity;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Type rules of the correspondence operations: the {@code ~} operator, the {@code select(~)}/{@code
 * reject(~)}/{@code exists(~)} shorthands with their {@code Type=}/{@code Tag=} filter options, and
 * misspelled correspondence operations. Extracted from {@link TypeCheckVisitor}, which delegates
 * the corresponding {@code visit*} methods here.
 */
final class CorrespondenceTyping {

  /** Correspondence iterator operations — candidates for unknown-operation suggestions. */
  static final List<String> CORR_OPS = List.of("select", "reject", "exists");

  private static final List<String> FILTER_OPTIONS = List.of("Tag", "Type");

  private final OperationTypingContext context;

  CorrespondenceTyping(OperationTypingContext context) {
    this.context = context;
  }

  /**
   * Type checks the correspondence operator {@code obj1 ~ obj2}: both operands must be single
   * objects (not multi-valued collections, not primitives).
   *
   * @param ctx Correspondence comparison operation node
   * @return Type.BOOLEAN if operands are valid, Type.ERROR otherwise
   */
  Type operator(VitruvOCLParser.CorrespondenceContext ctx) {
    Type leftType = context.check(ctx.left);
    Type rightType = context.check(ctx.right);

    // Unwrap singletons to get base types
    Type baseLeftType = leftType.isSingleton() ? leftType.getElementType() : leftType;

    // Both sides must be single objects (singleton or object type, not multi-valued collection)
    if (leftType.isCollection() && leftType.getMultiplicity() != Multiplicity.SINGLETON) {
      context.error(
          ctx.left,
          "Correspondence operator ~ requires single object on left side, got collection: "
              + leftType);
      return Type.ERROR;
    }

    if (rightType.isCollection() && rightType.getMultiplicity() != Multiplicity.SINGLETON) {
      context.error(
          ctx.right,
          "Correspondence operator ~ requires single object on right side, got collection: "
              + rightType);
      return Type.ERROR;
    }

    // Both must be metaclass types (not primitives)
    if (!baseLeftType.isMetaclassType() && baseLeftType != Type.ANY) {
      context.error(
          ctx.left,
          "Correspondence operator ~ requires object type on left side, got: " + baseLeftType);
      return Type.ERROR;
    }

    Type baseRightType = rightType.isSingleton() ? rightType.getElementType() : rightType;
    if (!baseRightType.isMetaclassType() && baseRightType != Type.ANY) {
      context.error(
          ctx.right,
          "Correspondence operator ~ requires object type on right side, got: " + baseRightType);
      return Type.ERROR;
    }

    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks {@code select(~)} / {@code select(~, Type=T, Tag='x')}: without a Type filter the
   * receiver collection type unchanged, with one a collection of the same kind narrowed to T.
   *
   * @param ctx The selectCorrespondence node
   * @return Refined or unchanged receiver collection type, or Type.ERROR
   */
  Type select(VitruvOCLParser.SelectCorrespondenceContext ctx) {
    Type receiverType = context.receiver();
    Type filterType = checkCall(ctx, ctx.corrFilter, "select");
    if (filterType == null) {
      return Type.ERROR;
    }
    Type resultType =
        (filterType != Type.ANY && filterType != Type.ERROR)
            ? Type.preserveCollectionKind(receiverType, filterType)
            : receiverType;
    context.recordType(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks {@code reject(~)} / {@code reject(~, Type=T, Tag='x')}. The type filter does not
   * narrow the result: reject keeps the elements that are not a corresponding T, which includes
   * every non-T element.
   *
   * @param ctx The rejectCorrespondence node
   * @return the unchanged receiver collection type, or Type.ERROR
   */
  Type reject(VitruvOCLParser.RejectCorrespondenceContext ctx) {
    Type receiverType = context.receiver();
    if (checkCall(ctx, ctx.corrFilter, "reject") == null) {
      return Type.ERROR;
    }
    context.recordType(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks {@code exists(~)} / {@code exists(~, Type=T, Tag='x')}; the filters are validated
   * but don't change the Boolean result.
   *
   * @param ctx The existsCorrespondence node
   * @return Type.BOOLEAN if validation succeeds, Type.ERROR otherwise
   */
  Type exists(VitruvOCLParser.ExistsCorrespondenceContext ctx) {
    if (checkCall(ctx, ctx.corrFilter, "exists") == null) {
      return Type.ERROR;
    }
    context.recordType(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * The checks shared by {@code select(~)}, {@code reject(~)} and {@code exists(~)}: the receiver
   * type is known, {@code self} is bound to an object, the receiver is a collection of objects, and
   * the filter is valid and parsed without errors.
   *
   * @param ctx the call, for error positions
   * @param filter the correspondence filter
   * @param opName the operation name for messages
   * @return the Type filter's metaclass, {@link Type#ANY} without a Type filter, or {@code null}
   *     after reporting an error
   */
  private Type checkCall(
      ParserRuleContext ctx, VitruvOCLParser.CorrespondenceFilterCSContext filter, String opName) {
    Type receiverType = context.receiver();
    if (receiverType == null || receiverType == Type.ERROR) {
      context.error(ctx, "Cannot determine receiver type for " + opName + "(~)");
      return null;
    }

    VariableSymbol selfSymbol = context.symbolTable().resolveVariable("self");
    if (selfSymbol == null) {
      context.error(ctx, opName + "(~) requires 'self' to be bound in current context");
      return null;
    }

    Type baseSelfType = selfSymbol.getType();
    if (baseSelfType.isSingleton()) {
      baseSelfType = baseSelfType.getElementType();
    }
    if (!baseSelfType.isMetaclassType() && baseSelfType != Type.ANY) {
      context.error(ctx, opName + "(~) requires 'self' to be an object type, got: " + baseSelfType);
      return null;
    }

    Type elemType = receiverType.getElementType();
    if (!elemType.isMetaclassType() && elemType != Type.ANY) {
      context.error(ctx, opName + "(~) requires collection of object types, got: " + elemType);
      return null;
    }

    int errorsBefore = context.errorCount();
    Type filterType = context.check(filter);
    if (reportFilterParseError(filter, ctx, opName, errorsBefore)) {
      return null;
    }
    return filterType;
  }

  /**
   * Checks whether a correspondence filter had parse errors ANTLR recovered from silently (e.g.
   * extra commas like {@code reject(~, , , Type=T)}) or type errors. When there are parse errors
   * but no type error was reported for the filter yet, reports one spanning the whole call.
   *
   * @param filter the parsed correspondence filter
   * @param callCtx the enclosing call, for the error span
   * @param opName operation name for the message (e.g. "reject")
   * @param errorsBefore diagnostic count before checking the filter
   * @return {@code true} if the filter has a parse or type error
   */
  private boolean reportFilterParseError(
      ParserRuleContext filter, ParserRuleContext callCtx, String opName, int errorsBefore) {
    boolean typeError = context.errorCount() > errorsBefore;
    boolean parseError = context.hasParseErrors(filter);
    if (!parseError && !typeError) {
      return false;
    }
    if (!typeError) {
      Token start = callCtx.getStart();
      Token stop = callCtx.getStop();
      int endLine = stop != null ? stop.getLine() : start.getLine();
      int endCol =
          stop != null
              ? stop.getCharPositionInLine() + stop.getText().length()
              : start.getCharPositionInLine() + callCtx.getText().length();
      context.spanError(
          start,
          endLine,
          endCol,
          "'" + opName + "(~, ...)' has invalid arguments — check for extra or misplaced commas");
    }
    return true;
  }

  /**
   * Type checks a correspondence filter ({@code ~} with optional Type/Tag options).
   *
   * @param ctx The correspondence filter node
   * @return The Type filter's metaclass, or Type.ANY without a Type filter
   */
  Type filter(VitruvOCLParser.CorrespondenceFilterCSContext ctx) {
    if (ctx.correspondenceOptions() == null) {
      context.recordType(ctx, Type.ANY);
      return Type.ANY;
    }
    Type result = context.check(ctx.correspondenceOptions());
    context.recordType(ctx, result);
    return result;
  }

  /**
   * Type checks the filter options; the last Type filter wins, with a warning for each further one.
   *
   * @param ctx The correspondence options node
   * @return The Type filter's metaclass, or Type.ANY if there is none
   */
  Type options(VitruvOCLParser.CorrespondenceOptionsContext ctx) {
    Type resolvedType = Type.ANY;
    boolean hadTypeFilter = false;

    for (VitruvOCLParser.CorrespondenceOptionContext option : ctx.correspondenceOption()) {
      Type optType = context.check(option);
      if (optType != Type.ANY) {
        if (hadTypeFilter) {
          context.warning(
              option, "Duplicate Type filter in correspondence — only one Type filter allowed");
        }
        resolvedType = optType;
        hadTypeFilter = true;
      }
    }

    context.recordType(ctx, resolvedType);
    return resolvedType;
  }

  /**
   * Type checks a {@code Type = ...} option: the type must be a metaclass.
   *
   * @param ctx The corrTypeFilter node
   * @return The metaclass, or Type.ERROR if invalid
   */
  Type typeOption(VitruvOCLParser.CorrTypeFilterContext ctx) {
    Type targetType = context.check(ctx.type);
    if (targetType == Type.ERROR) {
      return Type.ERROR;
    }

    // Unwrap singleton if needed (e.g. !Person! → Person)
    Type baseType = targetType.isSingleton() ? targetType.getElementType() : targetType;
    if (!baseType.isMetaclassType() && baseType != Type.ANY) {
      reportOptionError(
          ctx, "Type filter must be a metaclass (e.g. persons::Person), got: " + baseType);
      return Type.ERROR;
    }

    context.recordType(ctx, baseType);
    return baseType;
  }

  /**
   * Type checks a {@code Tag = ...} option: the value must be a String.
   *
   * @param ctx The corrTagFilter node
   * @return Type.ANY (a tag carries no type information)
   */
  Type tagOption(VitruvOCLParser.CorrTagFilterContext ctx) {
    Type tagType = context.check(ctx.tag);
    // Unwrap singleton wrapper to compare bare primitive type
    Type bare = (tagType != null && tagType.isSingleton()) ? tagType.getElementType() : tagType;
    if (bare != null && bare != Type.STRING && bare != Type.ANY && bare != Type.ERROR) {
      reportOptionError(ctx, "Tag filter value must be String, got " + bare);
    }
    context.recordType(ctx, Type.ANY);
    return Type.ANY;
  }

  /**
   * Reports an option other than {@code Type} and {@code Tag}, suggesting the closer one.
   *
   * @param ctx The unknownFilterOption node
   * @return Type.ERROR
   */
  Type unknownOption(VitruvOCLParser.UnknownFilterOptionContext ctx) {
    context.check(ctx.badVal); // so that the value's own errors surface

    String typed = ctx.badKey.getText();
    String best = SuggestionProvider.closest(typed, FILTER_OPTIONS);
    int dist = best == null ? Integer.MAX_VALUE : SuggestionProvider.distance(typed, best);
    String message =
        dist <= 2
            ? "Unknown filter option '" + typed + TypeCheckVisitor.ERR_DID_YOU_MEAN + best + "'?"
            : "Unknown filter option '" + typed + "' — valid options are 'Tag' and 'Type'";

    context.error(ctx.badKey, message, best);
    context.recordType(ctx, Type.ERROR);
    return Type.ERROR;
  }

  /**
   * Reports a filter argument that is not an option at all.
   *
   * @param ctx The invalidFilterArg node
   * @return Type.ERROR
   */
  Type invalidArgument(VitruvOCLParser.InvalidFilterArgContext ctx) {
    Token tok = ctx.badArg;
    context.error(
        tok,
        "Invalid filter argument '"
            + tok.getText()
            + "' — expected 'Type = <type>' or 'Tag = <string>'");
    return Type.ERROR;
  }

  /**
   * Reports a correspondence-style call like {@code selcft(~, ...)} whose operation is not one of
   * {@link #CORR_OPS}; the filter is still checked so that its own errors surface.
   *
   * @param ctx The unknownCorrOp node
   * @return Type.ERROR
   */
  Type unknownOperation(VitruvOCLParser.UnknownCorrOpContext ctx) {
    context.check(ctx.corrFilter);

    String typed = ctx.opName.getText();
    String best = SuggestionProvider.closest(typed, CORR_OPS);
    String message =
        SuggestionProvider.isClose(typed, best)
            ? TypeCheckVisitor.ERR_UNKNOWN_OP
                + typed
                + TypeCheckVisitor.ERR_DID_YOU_MEAN
                + best
                + "'?"
            : TypeCheckVisitor.ERR_UNKNOWN_OP
                + typed
                + TypeCheckVisitor.ERR_OP_DOES_NOT_EXIST
                + " (valid correspondence ops: "
                + String.join(", ", CORR_OPS)
                + ")";

    context.error(ctx.opName, message, best);
    context.recordType(ctx, Type.ERROR);
    return Type.ERROR;
  }

  /** Reports an error spanning the whole filter option. */
  private void reportOptionError(ParserRuleContext ctx, String message) {
    Token start = ctx.getStart();
    Token stop = ctx.getStop();
    int endCol =
        stop != null
            ? stop.getCharPositionInLine() + stop.getText().length()
            : start.getCharPositionInLine() + start.getText().length();
    context.spanError(start, stop != null ? stop.getLine() : start.getLine(), endCol, message);
  }
}
