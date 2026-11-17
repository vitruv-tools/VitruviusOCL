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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;

/**
 * Read access to the metamodels, model instances and correspondences that constraints are evaluated
 * against. This is the port between the language core (symbol table, type checker, evaluator,
 * incremental analysis) and a concrete model source such as loaded files or a VSUM.
 */
public interface ModelAccess {

  /**
   * Resolves an EClass by qualified name.
   *
   * @param metamodelName The metamodel name (e.g., "UML")
   * @param className The class name (e.g., "Class")
   * @return The resolved EClass, or null if not found
   */
  EClass resolveEClass(String metamodelName, String className);

  /**
   * Returns all instances of the given EClass.
   *
   * @param eClass The EClass to query
   * @return List of all instances
   */
  List<EObject> getAllInstances(EClass eClass);

  /**
   * Runs {@code work}, during which the models are not changed (e.g. the evaluation of a list of
   * constraints). {@code work} receives the wrapper to evaluate against: a wrapper may hand out a
   * view that reuses results, such as the instances of a type, across the batch's requests. That
   * view must only be used by the batch itself. The default passes this wrapper unchanged.
   *
   * @param work the evaluation to run, given the wrapper to use
   * @return the result of {@code work}
   */
  default <T> T batch(Function<ModelAccess, T> work) {
    return work.apply(this);
  }

  /**
   * Returns all instances of each of the given EClasses (subtype instances included), as needed by
   * one constraint evaluation.
   *
   * <p>This is the entry point of the allInstances() computation strategy of Wei &amp; Kolovos
   * (BigMDE 2015): the caller states up front every type it will need, so that an implementation
   * can collect all of them in a single model traversal instead of one traversal per type. The
   * default implementation simply asks for each type separately.
   *
   * @param types the EClasses whose instances are needed
   * @return a map containing an entry for every requested type (possibly an empty list); it may
   *     contain further types and must not be modified by the caller
   */
  default Map<EClass, List<EObject>> getAllInstances(Collection<EClass> types) {
    Map<EClass, List<EObject>> result = new LinkedHashMap<>();
    for (EClass type : types) {
      result.put(type, getAllInstances(type));
    }
    return result;
  }

  /**
   * Resolves an EEnum by qualified name.
   *
   * @param enumName The qualified name of the enum (e.g., "UML.VisibilityKind")
   * @return The resolved EEnum, or null if not found
   */
  EEnum resolveEEnum(String enumName);

  /**
   * Returns names of all available metamodels.
   *
   * @return Set of metamodel names
   */
  Set<String> getAvailableMetamodels();

  /**
   * The metamodel packages this access knows, including those that are not registered in the global
   * {@link EPackage.Registry} (for example metamodels loaded from files whose nsURI was taken over
   * by another package). Sub-packages need not be listed separately.
   *
   * <p>A decorator must delegate this method; analyses that see only the decorator depend on it to
   * find the metamodels of the wrapped access.
   *
   * @return an immutable snapshot of the packages, which may include globally registered ones;
   *     empty if the access contributes none
   */
  Collection<EPackage> metamodelPackages();

  /**
   * Returns the source filename for an instance at the given index.
   *
   * @param index The instance index (0-based)
   * @return The filename (e.g., "spacecraft-atlas.spacemission"), or null if index out of bounds
   */
  String getInstanceNameByIndex(int index);

  /**
   * Returns all root objects from all loaded model resources.
   *
   * <p>This includes root objects from all metamodels and instances currently loaded in the
   * resource set. Used for accessing correspondence models and other cross-cutting model elements.
   *
   * @return List of all root EObjects from all loaded resources
   */
  List<EObject> getAllRootObjects();

  /**
   * Returns the context EObject at the given evaluation index.
   *
   * @param index The evaluation index (0-based, one per root context object)
   * @return The EObject at that index, or null if out of bounds
   */
  EObject getContextObjectByIndex(int index);

  /**
   * Resolves an EClass by its unqualified short name, searching across all loaded metamodels.
   *
   * <p>Used as a fallback when a type annotation in an OCL constraint uses only the class name
   * without the metamodel qualifier (e.g., {@code Coordinate} instead of {@code cad::Coordinate}).
   * The first match across all registered packages is returned; if the same short name exists in
   * multiple metamodels, the result is unspecified and a qualified name should be used instead.
   *
   * @param shortName unqualified class name (e.g., {@code "Coordinate"})
   * @return the matching EClass, or {@code null} if not found in any loaded metamodel
   */
  EClass resolveEClassByShortName(String shortName);

  /**
   * Returns the source filename an instance was loaded from.
   *
   * @param instance the model instance
   * @return the source filename, or {@code null} if unknown
   */
  String getSourceFileForInstance(EObject instance);

  /**
   * Returns all objects corresponding to the given source object.
   *
   * @param source the source object
   * @return set of all corresponding objects; empty if none
   */
  Set<EObject> getCorrespondingObjects(EObject source);

  /**
   * Checks whether a correspondence between obj1 and obj2 carries the given tag.
   *
   * @param obj1 one side of the correspondence
   * @param obj2 other side of the correspondence
   * @param tag the required tag value
   * @return true if a correspondence with that tag exists between obj1 and obj2
   */
  boolean correspondenceHasTag(EObject obj1, EObject obj2, String tag);

  /**
   * Returns all objects corresponding to {@code source} via a correspondence carrying {@code tag}.
   *
   * <p>The default filters {@link #getCorrespondingObjects(EObject)} with {@link
   * #correspondenceHasTag}; implementations with a direct tagged lookup should override it.
   *
   * @param source the source object
   * @param tag the required tag value
   * @return the tagged correspondents of {@code source}; empty if none
   */
  default Set<EObject> getCorrespondingObjectsWithTag(EObject source, String tag) {
    Set<EObject> result = new LinkedHashSet<>();
    Set<EObject> candidates = getCorrespondingObjects(source);
    if (candidates == null) {
      return result;
    }
    for (EObject candidate : candidates) {
      if (correspondenceHasTag(source, candidate, tag)) {
        result.add(candidate);
      }
    }
    return result;
  }

  /**
   * Whether {@link #correspondenceHasTag correspondenceHasTag(a, b, tag)} is exactly {@code b ∈
   * getCorrespondingObjectsWithTag(a, tag) ∨ a ∈ getCorrespondingObjectsWithTag(b, tag)}. When
   * {@code true}, the evaluator answers tagged checks from memoized tagged lookups instead of
   * calling {@code correspondenceHasTag} for every pair.
   *
   * @return {@code false} unless the implementation guarantees the equivalence
   */
  default boolean hasTaggedCorrespondenceLookup() {
    return false;
  }

  /**
   * Every object that currently takes part in a correspondence, if the implementation can enumerate
   * them; used to observe correspondence changes, which no EChange reports. If this returns a
   * value, {@link #getCorrespondingObjectsByTag} must be supported as well.
   *
   * <p>The incremental evaluator skips {@code ~} constraints when these two methods show no change,
   * so they must describe exactly the relation that {@link #getCorrespondingObjects}, {@link
   * #getCorrespondingObjectsWithTag} and {@link #correspondenceHasTag} answer: an implementation
   * that overrides one of these lookups must override the enumeration methods consistently.
   *
   * @return the objects, or empty if enumeration is not supported (the default)
   */
  default Optional<Set<EObject>> getAllObjectsInACorrespondence() {
    return Optional.empty();
  }

  /**
   * The result of {@link #getCorrespondingObjectsWithTag} for every tag at once ({@code null} key
   * for untagged correspondences), in one lookup.
   *
   * @param source the source object
   * @return tag → correspondents of {@code source}; empty if none
   * @throws UnsupportedOperationException if {@link #getAllObjectsInACorrespondence()} is empty
   */
  default Map<String, Set<EObject>> getCorrespondingObjectsByTag(EObject source) {
    throw new UnsupportedOperationException("correspondence enumeration is not supported");
  }
}
