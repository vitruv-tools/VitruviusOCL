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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;

/**
 * A view of a wrapper for one {@link ModelAccess#batch batch}: it caches the instances per type,
 * since the models do not change during the batch, and delegates everything else. Only the batch's
 * own evaluation sees the cache; other callers of the underlying wrapper are unaffected.
 * Thread-safe, so the batch may evaluate constraints in parallel.
 */
final class BatchCachingWrapper implements ModelAccess {

  private final ModelAccess delegate;
  private final Map<EClass, List<EObject>> instances = new HashMap<>();

  BatchCachingWrapper(ModelAccess delegate) {
    this.delegate = delegate;
  }

  @Override
  public <T> T batch(Function<ModelAccess, T> work) {
    return work.apply(this); // nested: share this batch's cache
  }

  @Override
  public List<EObject> getAllInstances(EClass eClass) {
    return getAllInstances(List.of(eClass)).getOrDefault(eClass, Collections.emptyList());
  }

  @Override
  public Map<EClass, List<EObject>> getAllInstances(Collection<EClass> types) {
    synchronized (instances) {
      if (!instances.keySet().containsAll(types)) {
        Set<EClass> missing = new LinkedHashSet<>(types);
        missing.removeAll(instances.keySet());
        delegate
            .getAllInstances(missing)
            .forEach((type, list) -> instances.putIfAbsent(type, List.copyOf(list)));
      }
      Map<EClass, List<EObject>> result = new HashMap<>();
      for (EClass type : types) {
        result.put(type, instances.getOrDefault(type, Collections.emptyList()));
      }
      return Collections.unmodifiableMap(result);
    }
  }

  @Override
  public EClass resolveEClass(String metamodelName, String className) {
    return delegate.resolveEClass(metamodelName, className);
  }

  @Override
  public EEnum resolveEEnum(String enumName) {
    return delegate.resolveEEnum(enumName);
  }

  @Override
  public Set<String> getAvailableMetamodels() {
    return delegate.getAvailableMetamodels();
  }

  @Override
  public String getInstanceNameByIndex(int index) {
    return delegate.getInstanceNameByIndex(index);
  }

  @Override
  public List<EObject> getAllRootObjects() {
    return delegate.getAllRootObjects();
  }

  @Override
  public EObject getContextObjectByIndex(int index) {
    return delegate.getContextObjectByIndex(index);
  }

  @Override
  public EClass resolveEClassByShortName(String shortName) {
    return delegate.resolveEClassByShortName(shortName);
  }

  @Override
  public String getSourceFileForInstance(EObject instance) {
    return delegate.getSourceFileForInstance(instance);
  }

  @Override
  public Set<EObject> getCorrespondingObjects(EObject source) {
    return delegate.getCorrespondingObjects(source);
  }

  @Override
  public boolean correspondenceHasTag(EObject obj1, EObject obj2, String tag) {
    return delegate.correspondenceHasTag(obj1, obj2, tag);
  }

  @Override
  public Set<EObject> getCorrespondingObjectsWithTag(EObject source, String tag) {
    return delegate.getCorrespondingObjectsWithTag(source, tag);
  }

  @Override
  public boolean hasTaggedCorrespondenceLookup() {
    return delegate.hasTaggedCorrespondenceLookup();
  }

  @Override
  public Optional<Set<EObject>> getAllObjectsInACorrespondence() {
    return delegate.getAllObjectsInACorrespondence();
  }

  @Override
  public Map<String, Set<EObject>> getCorrespondingObjectsByTag(EObject source) {
    return delegate.getCorrespondingObjectsByTag(source);
  }
}
