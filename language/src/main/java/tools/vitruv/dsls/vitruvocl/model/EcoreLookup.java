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

package tools.vitruv.dsls.vitruvocl.model;

import java.util.Map;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EPackage;

/**
 * Name-based lookups of {@link EClass}es and {@link EEnum}s in metamodel packages, shared by the
 * {@link ModelAccess} implementations.
 */
public final class EcoreLookup {

  private EcoreLookup() {}

  /** Resolves a qualified name to its {@link EClass} using a name-keyed metamodel registry. */
  public static EClass resolveEClassInRegistry(
      Map<String, EPackage> registry, String metamodelName, String className) {
    EPackage ePackage = registry.get(metamodelName);
    if (ePackage == null) {
      return null;
    }
    EClassifier classifier = ePackage.getEClassifier(className);
    return (classifier instanceof EClass ec) ? ec : null;
  }

  /** Resolves the first {@link EClass} matching an unqualified short name across a registry. */
  public static EClass resolveEClassByShortNameInRegistry(
      Map<String, EPackage> registry, String shortName) {
    for (EPackage ePackage : registry.values()) {
      EClass found = resolveEClassByShortNameInPackage(ePackage, shortName);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * Searches an {@link EPackage} and its direct subpackages for an {@link EClass} by short name.
   */
  public static EClass resolveEClassByShortNameInPackage(EPackage ePackage, String shortName) {
    EClassifier classifier = ePackage.getEClassifier(shortName);
    if (classifier instanceof EClass eClass) {
      return eClass;
    }
    for (EPackage subPkg : ePackage.getESubpackages()) {
      EClassifier subClassifier = subPkg.getEClassifier(shortName);
      if (subClassifier instanceof EClass eClass) {
        return eClass;
      }
    }
    return null;
  }

  /** Resolves the first {@link EEnum} matching a name across a registry and its subpackages. */
  public static EEnum resolveEEnumInRegistry(Map<String, EPackage> registry, String enumName) {
    for (EPackage ePackage : registry.values()) {
      EEnum found = resolveEEnumInPackage(ePackage, enumName);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** Resolves an {@link EEnum} by name within an {@link EPackage} and its subpackages. */
  public static EEnum resolveEEnumInPackage(EPackage ePackage, String enumName) {
    for (EClassifier classifier : ePackage.getEClassifiers()) {
      if (classifier instanceof EEnum eEnum && eEnum.getName().equals(enumName)) {
        return eEnum;
      }
    }
    for (EPackage subPackage : ePackage.getESubpackages()) {
      EEnum found = resolveEEnumInPackage(subPackage, enumName);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
