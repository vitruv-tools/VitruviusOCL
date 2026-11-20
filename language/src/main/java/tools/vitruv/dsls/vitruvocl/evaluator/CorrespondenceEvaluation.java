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
import java.util.List;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Runtime semantics of the correspondence operations: the {@code ~} operator and the {@code
 * select(~)}/{@code reject(~)}/{@code exists(~)} shorthands with their {@code Type=}/{@code Tag=}
 * filters, all answered by the memoized {@link CorrespondenceLookup}. Extracted from {@link
 * EvaluationVisitor}.
 */
final class CorrespondenceEvaluation {

  private final OperationEvaluationContext context;

  /**
   * Memoized correspondence checks for every form of {@code ~}; lives as long as the visitor, so
   * each distinct object is looked up in the correspondence model at most once per evaluation.
   */
  private final CorrespondenceLookup lookup;

  CorrespondenceEvaluation(OperationEvaluationContext context, CorrespondenceLookup lookup) {
    this.context = context;
    this.lookup = lookup;
  }

  /**
   * Evaluates {@code obj1 ~ obj2}: whether a correspondence relates the two objects, in either
   * direction. {@code false} unless both operands are single model objects.
   *
   * @param ctx Correspondence expression context
   * @return a Boolean value
   */
  Value operator(VitruvOCLParser.CorrespondenceContext ctx) {
    Value leftValue = context.evaluate(ctx.left);
    Value rightValue = context.evaluate(ctx.right);

    if (leftValue.isEmpty() || rightValue.isEmpty()) {
      return Value.boolValue(false);
    }
    if (leftValue.size() != 1 || rightValue.size() != 1) {
      return Value.boolValue(false);
    }

    EObject leftObject = leftValue.getElements().get(0).tryGetInstance();
    EObject rightObject = rightValue.getElements().get(0).tryGetInstance();
    if (leftObject == null || rightObject == null) {
      return Value.boolValue(false);
    }

    return Value.boolValue(lookup.corresponds(leftObject, rightObject));
  }

  /**
   * Evaluates {@code select(~, ...)}: the receiver elements that correspond to {@code self} and
   * pass the filters; empty if {@code self} is not a single model object.
   *
   * @param ctx The selectCorrespondence node
   * @return the selected elements, with the receiver's type
   */
  Value select(VitruvOCLParser.SelectCorrespondenceContext ctx) {
    return filterReceiver(ctx.corrFilter, true);
  }

  /**
   * Evaluates {@code reject(~, ...)}: the receiver elements that do not correspond to {@code self}
   * or fail the filters; empty if {@code self} is not a single model object.
   *
   * @param ctx The rejectCorrespondence node
   * @return the remaining elements, with the receiver's type
   */
  Value reject(VitruvOCLParser.RejectCorrespondenceContext ctx) {
    return filterReceiver(ctx.corrFilter, false);
  }

  /**
   * Evaluates {@code exists(~, ...)}: whether any receiver element corresponds to {@code self} and
   * passes the filters; {@code false} if {@code self} is not a single model object.
   *
   * @param ctx The existsCorrespondence node
   * @return a Boolean value
   */
  Value exists(VitruvOCLParser.ExistsCorrespondenceContext ctx) {
    EObject selfObject = selfObject();
    if (selfObject == null) {
      return Value.boolValue(false);
    }

    String tagFilter = tagFilter(ctx.corrFilter);
    EClass typeFilter = typeFilter(ctx.corrFilter);

    for (OCLElement elem : context.receiver().getElements()) {
      EObject elemObject = elem.tryGetInstance();
      if (elemObject != null && passes(selfObject, elemObject, tagFilter, typeFilter)) {
        return Value.boolValue(true);
      }
    }
    return Value.boolValue(false);
  }

  /**
   * The receiver elements whose correspondence test ({@link #passes}) equals {@code keepPassing},
   * with the receiver's runtime type.
   */
  private Value filterReceiver(
      VitruvOCLParser.CorrespondenceFilterCSContext corrFilter, boolean keepPassing) {
    Value receiver = context.receiver();

    EObject selfObject = selfObject();
    if (selfObject == null) {
      return Value.of(List.of(), receiver.getRuntimeType());
    }

    String tagFilter = tagFilter(corrFilter);
    EClass typeFilter = typeFilter(corrFilter);

    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      EObject elemObject = elem.tryGetInstance();
      boolean passes = elemObject != null && passes(selfObject, elemObject, tagFilter, typeFilter);
      if (passes == keepPassing) {
        results.add(elem);
      }
    }
    return Value.of(results, receiver.getRuntimeType());
  }

  /** The model object bound to {@code self}, or {@code null} if it is not a single object. */
  private EObject selfObject() {
    VariableSymbol selfSymbol = context.symbolTable().resolveVariable("self");
    if (selfSymbol == null) {
      return null;
    }
    Value selfValue = context.environment().valueOf(selfSymbol);
    if (selfValue.isEmpty() || selfValue.size() != 1) {
      return null;
    }
    return selfValue.getElements().get(0).tryGetInstance();
  }

  /** The value of the Tag filter, or {@code null} if none is specified. */
  private String tagFilter(VitruvOCLParser.CorrespondenceFilterCSContext corrFilter) {
    if (corrFilter.correspondenceOptions() == null) {
      return null;
    }
    for (VitruvOCLParser.CorrespondenceOptionContext opt :
        corrFilter.correspondenceOptions().correspondenceOption()) {
      if (opt instanceof VitruvOCLParser.CorrTagFilterContext tagCtx) {
        Value tagVal = context.evaluate(tagCtx.tag);
        if (tagVal.isEmpty()) {
          return null;
        }
        return tagVal.getElements().get(0).tryGetString();
      }
    }
    return null;
  }

  /** The EClass of the Type filter, as resolved by the type checker, or {@code null}. */
  private EClass typeFilter(VitruvOCLParser.CorrespondenceFilterCSContext corrFilter) {
    if (corrFilter.correspondenceOptions() == null) {
      return null;
    }
    for (VitruvOCLParser.CorrespondenceOptionContext opt :
        corrFilter.correspondenceOptions().correspondenceOption()) {
      if (opt instanceof VitruvOCLParser.CorrTypeFilterContext typeCtx) {
        Type resolvedType = context.nodeType(typeCtx);
        if (resolvedType != null && resolvedType.isMetaclassType()) {
          return resolvedType.getEClass();
        }
      }
    }
    return null;
  }

  /**
   * Whether {@code elemObject} is an instance of the type filter and corresponds to {@code
   * selfObject}, with the required tag if one is given. The type filter is a cheap EMF instanceof
   * check and runs first.
   */
  private boolean passes(
      EObject selfObject, EObject elemObject, String tagFilter, EClass typeFilter) {
    if (typeFilter != null && !typeFilter.isInstance(elemObject)) {
      return false;
    }
    if (tagFilter != null) {
      return lookup.corresponds(selfObject, elemObject, tagFilter);
    }
    return lookup.corresponds(selfObject, elemObject);
  }
}
