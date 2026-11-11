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

package tools.vitruv.dsls.vitruvocl.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;

/**
 * The packages the pruned {@code allInstances()} analysis must know. Pruning a containment
 * reference is only sound if every subtype of its type is known, so a package missing here can hide
 * instances. The name-keyed metamodel registries of the wrappers keep one package per name; this
 * class adds, by identity, every package instances can come from: the packages of the model roots
 * and all packages already instantiated in the given EMF registries. Unresolved {@link
 * EPackage.Descriptor}s are skipped: no instance of their classes can exist yet, and resolving one
 * replaces the registry entry with the package, which the next collection then picks up.
 */
final class AnalyzedPackages {

  private static final int REGISTRY_SNAPSHOT_ATTEMPTS = 3;

  private AnalyzedPackages() {}

  /**
   * Collects {@code known}, the packages of the classes of {@code roots}, and every {@link
   * EPackage} among {@code registryValues}; unloaded (proxy) packages are left out.
   */
  static Set<EPackage> collect(
      Collection<EPackage> known,
      Collection<? extends EObject> roots,
      Collection<?> registryValues) {
    Set<EPackage> packages = Collections.newSetFromMap(new IdentityHashMap<>());
    for (EPackage pkg : known) {
      addIfLoaded(pkg, packages);
    }
    for (EObject root : roots) {
      addIfLoaded(root.eClass().getEPackage(), packages);
    }
    for (Object value : registryValues) {
      if (value instanceof EPackage pkg) {
        addIfLoaded(pkg, packages);
      }
    }
    return packages;
  }

  private static void addIfLoaded(EPackage pkg, Set<EPackage> packages) {
    if (pkg != null && !pkg.eIsProxy()) {
      packages.add(pkg);
    }
  }

  /**
   * Copies the values of {@code registry}. The global registry is not thread-safe and may be
   * modified concurrently, e.g., when another virtual model is initialized.
   */
  static List<Object> snapshot(EPackage.Registry registry) {
    ConcurrentModificationException lastFailure = null;
    for (int attempt = 0; attempt < REGISTRY_SNAPSHOT_ATTEMPTS; attempt++) {
      try {
        return new ArrayList<>(registry.values());
      } catch (ConcurrentModificationException e) {
        lastFailure = e;
      }
    }
    throw lastFailure;
  }
}
