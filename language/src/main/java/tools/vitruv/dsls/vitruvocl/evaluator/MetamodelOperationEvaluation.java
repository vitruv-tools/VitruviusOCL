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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Runtime semantics of the operations on metaclasses and runtime types: {@code allInstances()},
 * {@code oclIsKindOf}, {@code oclIsTypeOf} and {@code oclAsType}. Extracted from {@link
 * EvaluationVisitor}.
 */
final class MetamodelOperationEvaluation {

  private final OperationEvaluationContext context;

  /** The types the type checker recorded, to find the receiver metaclass of allInstances(). */
  private final ParseTreeProperty<Type> nodeTypes;

  /** The instances of an EClass in the evaluated models (prescanned by the visitor). */
  private final Function<EClass, List<EObject>> instancesOf;

  /** allInstances() results per EClass, to avoid redundant model queries during evaluation. */
  private final Map<EClass, Value> allInstancesCache = new HashMap<>();

  MetamodelOperationEvaluation(
      OperationEvaluationContext context,
      ParseTreeProperty<Type> nodeTypes,
      Function<EClass, List<EObject>> instancesOf) {
    this.context = context;
    this.nodeTypes = nodeTypes;
    this.instancesOf = instancesOf;
  }

  /**
   * Evaluates {@code allInstances()}: all instances of the receiver metaclass in the loaded models,
   * e.g. {@code spaceMission::Spacecraft.allInstances()}.
   *
   * @param ctx The allInstances operation node
   * @return all instances of the metaclass, or an empty set if the metaclass is unknown
   */
  Value allInstances(VitruvOCLParser.AllInstancesOpContext ctx) {
    // Navigate up the parse tree to the receiver's (metaclass) type
    EClass eClass = AllInstancesTypeScanner.receiverEClass(ctx, nodeTypes);
    if (eClass == null) {
      return Value.empty(Type.set(Type.ANY));
    }

    if (allInstancesCache.containsKey(eClass)) {
      return allInstancesCache.get(eClass);
    }

    List<EObject> instances = instancesOf.apply(eClass);
    List<OCLElement> elements =
        instances.stream().map(obj -> (OCLElement) new OCLElement.MetaclassValue(obj)).toList();

    Type resultType = context.nodeType(ctx);
    Value result =
        Value.of(elements, resultType != null ? resultType : Type.set(Type.metaclassType(eClass)));

    allInstancesCache.put(eClass, result);
    return result;
  }

  /**
   * Evaluates {@code oclIsKindOf(T)}: whether each receiver element is an instance of T or a
   * subtype; for primitive types by value kind, for metaclasses by EMF inheritance.
   *
   * @param ctx The oclIsKindOf operation node
   * @return one Boolean per receiver element
   */
  Value isKindOf(VitruvOCLParser.OclIsKindOfOpContext ctx) {
    Value receiver = context.receiver();
    Type targetType = context.nodeType(ctx.type);
    if (targetType == null) {
      return Value.of(List.of(), Type.set(Type.BOOLEAN));
    }

    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      results.add(new OCLElement.BoolValue(isKindOf(elem, targetType)));
    }

    Type resultType = context.nodeType(ctx);
    return Value.of(results, resultType != null ? resultType : Type.set(Type.BOOLEAN));
  }

  /**
   * Evaluates {@code oclIsTypeOf(T)}: whether each receiver element is exactly of type T.
   *
   * @param ctx The oclIsTypeOf operation node
   * @return one Boolean per receiver element
   */
  Value isTypeOf(VitruvOCLParser.OclIsTypeOfOpContext ctx) {
    Value receiver = context.receiver();
    Type targetType = context.nodeType(ctx.type);
    if (targetType == null) {
      return Value.of(List.of(), Type.set(Type.BOOLEAN));
    }

    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : receiver.getElements()) {
      results.add(new OCLElement.BoolValue(isTypeOf(elem, targetType)));
    }

    Type resultType = context.nodeType(ctx);
    return Value.of(results, resultType != null ? resultType : Type.set(Type.BOOLEAN));
  }

  /**
   * Evaluates {@code oclAsType(T)}, preserving the receiver's multiplicity. A primitive target only
   * re-annotates the elements; a metaclass target keeps the elements that are instances of it (as
   * casted values) and drops the others.
   *
   * <p>The type checker records {@code ¡T!} for a singleton receiver, so the target is unwrapped
   * through singletons as well as collections; bare types return {@code this} from {@code
   * getElementType()}, which stops the unwrapping.
   *
   * @param ctx The oclAsType operation node
   * @return The receiver elements re-wrapped with the target element type
   */
  @SuppressWarnings("java:S3776")
  Value asType(VitruvOCLParser.OclAsTypeOpContext ctx) {
    Value receiver = context.receiver();
    Type targetType = context.nodeType(ctx);

    if (targetType == null) {
      return Value.of(receiver.getElements(), receiver.getRuntimeType());
    }

    Type targetElemType = targetType;
    while ((targetElemType.isCollection() || targetElemType.isSingleton())
        && targetElemType.getElementType() != targetElemType) {
      targetElemType = targetElemType.getElementType();
    }

    // Primitive type cast: re-annotate with target element type, preserve collection kind
    if (!targetElemType.isMetaclassType()) {
      Type resultType = Type.preserveCollectionKind(receiver.getRuntimeType(), targetElemType);
      return Value.of(receiver.getElements(), resultType);
    }

    // Metaclass cast: validate each element via EMF inheritance
    EClass targetEClass = targetElemType.getEClass();
    List<OCLElement> results = new ArrayList<>();

    for (OCLElement elem : receiver.getElements()) {
      if (elem instanceof OCLElement.MetaclassValue(EObject instance)) {
        EClass elemEClass = instance.eClass();
        if (targetEClass.isSuperTypeOf(elemEClass) || elemEClass.equals(targetEClass)) {
          results.add(new OCLElement.CastedMetaclassValue(instance, targetEClass));
        }
        // else: element fails cast — filter it out
      } else if (elem
          instanceof OCLElement.CastedMetaclassValue(EObject instance, EClass castedTo)) {
        // Already casted — re-validate against new target
        EClass elemEClass = instance.eClass();
        if (targetEClass.isSuperTypeOf(elemEClass) || elemEClass.equals(targetEClass)) {
          results.add(new OCLElement.CastedMetaclassValue(instance, targetEClass));
        }
        // else: element fails cast — filter it out
      }
      // else: non-metaclass element — filter it out
    }

    // Result type: singleton if receiver was singleton or bare metaclass, collection otherwise
    Type resultType;
    if (receiver.getRuntimeType().isSingleton() || !receiver.getRuntimeType().isCollection()) {
      resultType = Type.singleton(targetElemType);
    } else {
      resultType = Type.preserveCollectionKind(receiver.getRuntimeType(), targetElemType);
    }
    return Value.of(results, resultType);
  }

  /** Whether {@code elem} is of {@code targetType} or a subtype (Integer values are Reals). */
  private static boolean isKindOf(OCLElement elem, Type targetType) {
    if (targetType == Type.ANY) {
      return true; // OclAny is the supertype of every value
    }
    if (targetType == Type.INTEGER) {
      return elem.tryGetInt() != null;
    } else if (targetType == Type.STRING) {
      return elem.tryGetString() != null;
    } else if (targetType == Type.BOOLEAN) {
      return elem.tryGetBool() != null;
    } else if (targetType == Type.DOUBLE) {
      // Real: Integer and Float values are kinds of Real (OCL: Integer conforms to Real)
      return elem.tryGetDouble() != null || elem.tryGetFloat() != null || elem.tryGetInt() != null;
    } else if (targetType == Type.FLOAT) {
      return elem.tryGetFloat() != null || elem.tryGetDouble() != null || elem.tryGetInt() != null;
    }

    if (targetType.isMetaclassType()) {
      EClass targetEClass = targetType.getEClass();
      EClass elemEClass = elem.getEClass();
      if (elemEClass == null) {
        return false;
      }
      return targetEClass.isSuperTypeOf(elemEClass) || elemEClass.equals(targetEClass);
    }

    return false;
  }

  /** Whether {@code elem} is exactly of {@code targetType}. */
  private static boolean isTypeOf(OCLElement elem, Type targetType) {
    if (targetType == Type.INTEGER) {
      return elem.tryGetInt() != null;
    } else if (targetType == Type.STRING) {
      return elem.tryGetString() != null;
    } else if (targetType == Type.BOOLEAN) {
      return elem.tryGetBool() != null;
    } else if (targetType == Type.DOUBLE) {
      return elem.tryGetDouble() != null;
    } else if (targetType == Type.FLOAT) {
      return elem.tryGetFloat() != null;
    }

    if (targetType.isMetaclassType()) {
      EClass targetEClass = targetType.getEClass();
      EClass elemEClass = elem.getEClass();
      if (elemEClass == null) {
        return false;
      }
      return elemEClass.equals(targetEClass);
    }

    return false;
  }
}
