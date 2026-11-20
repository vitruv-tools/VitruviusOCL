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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Maps EMF structural features and classifiers to VitruvOCL types, and finds features that an
 * abstract class does not declare but its subtypes do.
 */
final class FeatureTypeMapper {

  private FeatureTypeMapper() {}

  /**
   * Maps an EMF structural feature to a VitruvOCL type.
   *
   * <p>Respects EMF ordering and uniqueness annotations:
   *
   * <ul>
   *   <li>ordered + unique → OrderedSet {@code <T>}
   *   <li>ordered + !unique → Sequence {@code [T]}
   *   <li>!ordered + unique → Set {@code {T}}
   *   <li>!ordered + !unique → Bag {@code {{T}}}
   *   <li>single-valued reference with lower bound 0 → Optional {@code ?T?}
   *   <li>other single-valued → Singleton {@code !T!}
   * </ul>
   *
   * @param feature The EMF structural feature
   * @return The corresponding VitruvOCL type
   */
  @SuppressWarnings("java:S125")
  static Type featureType(EStructuralFeature feature) {
    Type baseType = classifierType(feature.getEType());

    if (feature.getUpperBound() > 1 || feature.getUpperBound() == -1) {
      // Multi-valued: respect EMF ordering and uniqueness annotations
      boolean ordered = feature.isOrdered();
      boolean unique = feature.isUnique();
      // EMF's upperBound already uses -1 for "unbounded", matching Type.UNBOUNDED — no
      // translation needed.
      int lowerBound = feature.getLowerBound();
      int upperBound = feature.getUpperBound();

      if (ordered && unique) {
        return Type.orderedSet(baseType, lowerBound, upperBound); // <T>
      } else if (ordered) {
        return Type.sequence(baseType, lowerBound, upperBound); // [T]
      } else if (unique) {
        return Type.set(baseType, lowerBound, upperBound); // {T}
      } else {
        return Type.bag(baseType, lowerBound, upperBound); // {{T}}
      }
    } else {
      // EAttribute with lowerBound=0: primitive always has a default value → ¡T!
      // EReference with lowerBound=0: reference may be absent → ¿T?
      // Any single-valued with lowerBound>=1: always present → ¡T!
      if (feature instanceof EReference && feature.getLowerBound() == 0) {
        return Type.optional(baseType);
      } else {
        return Type.singleton(baseType);
      }
    }
  }

  /**
   * Maps an EMF EClassifier to a VitruvOCL type.
   *
   * <p>Handles:
   *
   * <ul>
   *   <li>EString, EInt, EBoolean, EFloat, EDouble → the corresponding primitive type
   *   <li>EClass → metaclass type
   *   <li>EEnum → enum type
   *   <li>other EDataType → by instance class name (String, Integer, Boolean, Float, Double; String
   *       for any other name)
   *   <li>anything else → {@code Type.ERROR}
   * </ul>
   *
   * @param classifier The EMF classifier
   * @return The corresponding VitruvOCL type
   */
  private static Type classifierType(EClassifier classifier) {
    // Handle primitive types
    switch (classifier.getName()) {
      case "EString":
        return Type.STRING;
      case "EInt":
        return Type.INTEGER;
      case "EBoolean":
        return Type.BOOLEAN;
      case "EFloat":
        return Type.FLOAT;
      case "EDouble":
        return Type.DOUBLE;
      default:
        if (EcorePackage.Literals.ECLASS.equals(classifier.eClass())) {
          return Type.metaclassType((EClass) classifier);
        }
        // Handle EEnum — enum literals are compared by value in OCL
        if (classifier instanceof EEnum eEnum) {
          return Type.enumType(eEnum);
        }
        // Handle custom EDataType (e.g. autosar::Identifier with
        // instanceClassName="java.lang.String")
        if (classifier instanceof EDataType eDataType) {
          String instanceClass = eDataType.getInstanceClassName();
          if (instanceClass != null) {
            return switch (instanceClass) {
              case "java.lang.String", "String" -> Type.STRING;
              case "int", "java.lang.Integer" -> Type.INTEGER;
              case "Boolean", "java.lang.Boolean" -> Type.BOOLEAN;
              case "float", "java.lang.Float" -> Type.FLOAT;
              case "double", "java.lang.Double" -> Type.DOUBLE;
              default -> Type.STRING; // fallback: treat unknown scalar datatypes as String
            };
          }
        }
        return Type.ERROR;
    }
  }

  /**
   * Searches all known subclasses of {@code abstractClass} (reachable via EMF EPackage hierarchy)
   * for a structural feature named {@code propName}. Returns the feature if found unambiguously on
   * one or more subtypes (all with the same type and multiplicity), or {@code null} if not found or
   * ambiguous across incompatible types.
   */
  static EStructuralFeature findFeatureInSubtypes(EClass abstractClass, String propName) {
    // Collect all EPackages reachable from the root package
    EPackage rootPkg = abstractClass.getEPackage();
    while (rootPkg.getESuperPackage() != null) {
      rootPkg = rootPkg.getESuperPackage();
    }
    List<EClass> allClasses = new ArrayList<>();
    collectEClasses(rootPkg, allClasses, new HashSet<>());

    // Find feature on any subtype (abstractClass.isSuperTypeOf(candidate) is true for subtypes)
    EStructuralFeature found = null;
    for (EClass candidate : allClasses) {
      if (candidate == abstractClass || !abstractClass.isSuperTypeOf(candidate)) {
        continue;
      }
      EStructuralFeature f = candidate.getEStructuralFeature(propName);
      if (f != null) {
        if (found == null) {
          found = f;
        } else if (found.getEType() != f.getEType() || found.isMany() != f.isMany()) {
          // Ambiguous: same feature name but different types on different subtypes — reject
          return null;
        }
      }
    }
    return found;
  }

  private static void collectEClasses(EPackage pkg, List<EClass> result, Set<EPackage> visited) {
    if (!visited.add(pkg)) {
      return;
    }
    for (EClassifier c : pkg.getEClassifiers()) {
      if (c instanceof EClass eClass) {
        result.add(eClass);
      }
    }
    for (EPackage sub : pkg.getESubpackages()) {
      collectEClasses(sub, result, visited);
    }
  }
}
