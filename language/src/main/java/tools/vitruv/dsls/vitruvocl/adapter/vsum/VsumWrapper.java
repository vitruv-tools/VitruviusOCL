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

package tools.vitruv.dsls.vitruvocl.adapter.vsum;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;
import tools.vitruv.change.correspondence.Correspondence;
import tools.vitruv.change.correspondence.view.CorrespondenceModelView;
import tools.vitruv.dsls.vitruvocl.allinstances.AllInstancesEngine;
import tools.vitruv.dsls.vitruvocl.allinstances.cache.AllInstancesCallSite;
import tools.vitruv.dsls.vitruvocl.model.AnalyzedPackages;
import tools.vitruv.dsls.vitruvocl.model.EcoreLookup;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.model.TransactionSource;
import tools.vitruv.framework.views.ViewSource;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.constraints.ConstraintCheckContext;
import tools.vitruv.framework.vsum.constraints.ConstraintChecker;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

/**
 * {@link ModelAccess} backed by a Vitruvius VSUM, for VitruvOCL constraint evaluation.
 *
 * <p>This wrapper provides access to metamodels, model instances, and the correspondence model
 * through the Vitruvius Virtual Single Underlying Model (VSUM). It discovers metamodels from the
 * global {@link EPackage.Registry}, which is populated by the VSUM during initialization — before
 * any model instances are created.
 *
 * <p>The correspondence model is accessible via {@link #getCorrespondingObjects(EObject)} and
 * {@link #getCorrespondingObjects(EObject, EClass)}, enabling evaluation of VitruvOCL's {@code ~}
 * (correspondence) operator for cross-metamodel consistency checking.
 *
 * @see ModelAccess
 * @see VirtualModel
 */
public class VsumWrapper implements ModelAccess, TransactionSource {

  /** Provides the current model resources of the VSUM, read live on every access. */
  private final Supplier<Collection<Resource>> modelResources;

  /**
   * The correspondence model view, used to resolve the {@code ~} operator in VitruvOCL constraints.
   */
  private final CorrespondenceModelView<Correspondence> correspondenceModel;

  /**
   * Internal registry mapping metamodel names (EPackage names) to their corresponding {@link
   * EPackage} instances.
   *
   * <p>Populated from the global {@link EPackage.Registry} at construction time — the VSUM
   * registers all metamodels during {@code buildAndInitialize()}, before any instances exist.
   */
  private final Map<String, EPackage> metamodelRegistry = new HashMap<>();

  /**
   * Every package registered at construction, by identity: {@link #metamodelRegistry} keeps only
   * one package per name, but the pruned {@code allInstances()} traversal must know all of them.
   */
  private final Set<EPackage> knownPackages = Collections.newSetFromMap(new IdentityHashMap<>());

  /**
   * The wrapped virtual model, used to register transaction listeners; {@code null} for wrappers
   * of a single constraint check.
   */
  private final VirtualModel vsum;

  /**
   * Instances per metaclass, only used for wrappers of a single constraint check; {@code null} for
   * wrappers reading the live VSUM.
   */
  private final Map<EClass, List<EObject>> instanceCache;

  /**
   * Every {@link EClass} ever passed to {@link #getAllInstances}, in first-seen order.
   *
   * <p>Same runtime call-site registration as {@code MetamodelWrapper#getAllInstances}: {@link
   * tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor} is the sole caller of this method
   * anywhere in the codebase, so the set of types ever queried through it is exactly the set of
   * types constraints need — no separate AST pre-scan required. Every call site is treated as
   * {@code ALL_OF_KIND} (subtype-inclusive), matching this class's previous {@code
   * isSuperTypeOf}-based filtering exactly.
   */
  private final Set<EClass> queriedAllInstancesTypes = new LinkedHashSet<>();

  /**
   * Lazily (re)built {@link AllInstancesEngine}; {@code null} means stale/not-yet-built.
   *
   * <p>Invalidated when {@link #queriedAllInstancesTypes} gains a new entry or a package appears
   * that the engine was not analysed with (registered globally or used by a model root after
   * construction). {@code
   * compute()} is always invoked against a freshly-read root list (see {@link #getAllInstances}),
   * so live changes to the VSUM's instance model are always reflected regardless of how long this
   * cached engine has been reused.
   */
  private AllInstancesEngine allInstancesEngine;

  /** The packages {@link #allInstancesEngine} was analysed with. */
  private Set<EPackage> enginePackages = Set.of();

  /**
   * Creates a new VSUM wrapper and initializes the metamodel registry and correspondence model.
   *
   * <p>The VSUM must be an {@link InternalVirtualModel} to allow access to the correspondence model
   * via {@code getCorrespondenceModel()}.
   *
   * <p>Metamodels are loaded from the global {@link EPackage.Registry} rather than from VSUM
   * resources directly, because the registry is already populated by the VSUM during initialization
   * — before any model instances are added.
   *
   * @param vsum the Vitruvius virtual model to wrap, must be an {@link InternalVirtualModel}
   * @throws IllegalArgumentException if {@code vsum} is not an {@link InternalVirtualModel}
   */
  public VsumWrapper(VirtualModel vsum) {
    if (!(vsum instanceof InternalVirtualModel)) {
      throw new IllegalArgumentException(
          "VsumWrapper requires an InternalVirtualModel to access the correspondence model");
    }
    this.vsum = vsum;
    this.modelResources = ((ViewSource) vsum)::getViewSourceModels;
    this.correspondenceModel = ((InternalVirtualModel) vsum).getCorrespondenceModel();
    this.instanceCache = null;
    loadMetamodelsFromRegistry();
  }

  /**
   * Creates a new wrapper for the VSUM state provided to a Vitruv {@link ConstraintChecker}, valid
   * for a single check. Since the models do not change during a check, the instances of each
   * metaclass are computed only once.
   *
   * <p>Metamodels are taken from the global {@link EPackage.Registry} and from the elements of the
   * models; packages of the models take precedence for equal names.
   *
   * @param context read access to the model resources and correspondences of the VSUM
   */
  public VsumWrapper(ConstraintCheckContext context) {
    this.vsum = null;
    this.modelResources = context::getModelResources;
    this.correspondenceModel = context.getCorrespondenceModel();
    this.instanceCache = new HashMap<>();
    loadMetamodelsFromRegistry();
    for (Resource resource : context.getModelResources()) {
      for (EObject root : resource.getContents()) {
        EPackage pkg = root.eClass().getEPackage();
        metamodelRegistry.put(pkg.getName(), pkg);
        knownPackages.add(pkg);
      }
    }
  }

  /**
   * Discovers and registers all metamodels from the global EMF {@link EPackage.Registry}.
   *
   * <p>The VSUM registers all metamodel packages during {@code buildAndInitialize()} — they are
   * available in the global registry even before any model instances are created. This approach
   * avoids the need to have instances in the VSUM at construction time.
   */
  private void loadMetamodelsFromRegistry() {
    for (Object value : AnalyzedPackages.snapshot(EPackage.Registry.INSTANCE)) {
      if (value instanceof EPackage pkg) {
        metamodelRegistry.put(pkg.getName(), pkg);
        knownPackages.add(pkg);
      }
    }
  }


  /**
   * Resolves a fully qualified metaclass name to its corresponding {@link EClass}.
   *
   * @param metamodelName the name of the metamodel (EPackage name), e.g., "model"
   * @param className the name of the metaclass within the metamodel, e.g., "Component"
   * @return the resolved {@link EClass}, or {@code null} if not found
   */
  @Override
  public EClass resolveEClass(String metamodelName, String className) {
    return EcoreLookup.resolveEClassInRegistry(metamodelRegistry, metamodelName, className);
  }

  /**
   * Retrieves all instances of a given metaclass from the VSUM.
   *
   * <p>Always reads live from the VSUM's view source models to reflect the current state, including
   * instances added after construction. Follows EMF subtyping semantics — instances of subclasses
   * are included.
   *
   * <p><b>Implementation:</b> delegates to an {@link AllInstancesEngine} (Wei &amp; Kolovos,
   * "An Efficient Computation Strategy for allInstances()", BigMDE 2015) instead of an
   * unconditional {@code eAllContents()} walk over every view source model on every call. The
   * traversal root list is {@link #getAllRootObjects()} — exactly the same source the previous
   * implementation walked (all view source model roots; the correspondence model is a separate
   * structure never touched by either implementation) — so results are read live just as before;
   * only the engine's query analysis and containment-reachability pruning are cached (see {@link
   * #queriedAllInstancesTypes} and {@link #allInstancesEngine}).
   *
   * @param eClass the metaclass whose instances should be retrieved
   * @return list of all matching model elements; empty if none found
   */
  @Override
  public List<EObject> getAllInstances(EClass eClass) {
    return getAllInstances(List.of(eClass)).getOrDefault(eClass, Collections.emptyList());
  }

  /**
   * Returns the instances of all given types, collected in one traversal (Wei &amp; Kolovos, BigMDE
   * 2015, Section 3.4) together with those of every type requested before.
   *
   * <p>A wrapper of a single constraint check caches the result for the rest of the check, since
   * the models do not change during a check; a traversal happens only when a type not cached yet
   * is requested. A wrapper reading the live VSUM receives no notification of model changes here,
   * so it traverses on every call (once for all requested types); a {@link #batch} view caches.
   *
   * @param types the metaclasses whose instances should be retrieved
   * @return an unmodifiable map with an entry for every requested type (and possibly more)
   */
  @Override
  public Map<EClass, List<EObject>> getAllInstances(Collection<EClass> types) {
    if (instanceCache == null) {
      return Collections.unmodifiableMap(obtainEngine(types).compute(getAllRootObjects()));
    }
    return cachedInstances(instanceCache, types);
  }

  /**
   * For the live VSUM: evaluates {@code work} against a view that caches the instances per type for
   * this batch only, since the models do not change during it. Other callers keep reading the live
   * models.
   */
  @Override
  public <T> T batch(Function<ModelAccess, T> work) {
    return work.apply(instanceCache != null ? this : new BatchCachingWrapper(this));
  }

  private Map<EClass, List<EObject>> cachedInstances(
      Map<EClass, List<EObject>> cache, Collection<EClass> types) {
    synchronized (cache) {
      if (!cache.keySet().containsAll(types)) {
        obtainEngine(types)
            .compute(getAllRootObjects())
            .forEach((type, instances) -> cache.putIfAbsent(type, List.copyOf(instances)));
      }
      Map<EClass, List<EObject>> result = new HashMap<>();
      for (EClass type : types) {
        result.put(type, cache.getOrDefault(type, Collections.emptyList()));
      }
      return Collections.unmodifiableMap(result);
    }
  }

  /**
   * Registers {@code types} as call sites (if not already known) and returns an {@link
   * AllInstancesEngine} whose cache configuration covers them, rebuilding the cached engine first
   * if necessary.
   *
   * <p>Synchronized because {@link #queriedAllInstancesTypes} and {@link #allInstancesEngine} are
   * shared, mutable state read and written by every {@link #getAllInstances} call — including
   * concurrently from multiple threads when constraints are evaluated in parallel.
   */
  private synchronized AllInstancesEngine obtainEngine(Collection<EClass> types) {
    if (queriedAllInstancesTypes.addAll(types)) {
      allInstancesEngine = null; // newly-seen call site -> cache configuration is stale
    }
    Set<EPackage> packages =
        AnalyzedPackages.collect(
            knownPackages,
            getAllRootObjects(),
            AnalyzedPackages.snapshot(EPackage.Registry.INSTANCE));
    if (allInstancesEngine == null || !enginePackages.containsAll(packages)) {
      // a package the pruning does not know may hold instances below a pruned reference
      allInstancesEngine = buildAllInstancesEngine(packages);
      enginePackages = packages;
    }
    return allInstancesEngine;
  }

  /**
   * Builds a fresh {@link AllInstancesEngine} from the currently known metamodels and call sites.
   */
  private AllInstancesEngine buildAllInstancesEngine(Set<EPackage> packages) {
    List<AllInstancesCallSite> callSites = new ArrayList<>(queriedAllInstancesTypes.size());
    for (EClass type : queriedAllInstancesTypes) {
      callSites.add(new AllInstancesCallSite(type, AllInstancesCallSite.Kind.ALL_OF_KIND));
    }
    return new AllInstancesEngine(packages, callSites);
  }


  /**
   * The metamodel packages by name, exactly as this wrapper resolves type names (including which of
   * several same-named packages wins). Two wrappers with equal maps type-check constraints alike.
   *
   * @return an unmodifiable snapshot
   */
  public Map<String, EPackage> metamodelsByName() {
    return Collections.unmodifiableMap(new HashMap<>(metamodelRegistry)); // keys may be null
  }

  /**
   * Returns the names of all metamodels currently registered in this wrapper.
   *
   * @return unmodifiable set of metamodel names (EPackage names)
   */
  @Override
  public Set<String> getAvailableMetamodels() {
    return Collections.unmodifiableSet(metamodelRegistry.keySet());
  }

  @Override
  public Collection<EPackage> metamodelPackages() {
    return List.copyOf(knownPackages);
  }

  /**
   * Returns the source resource URI for the root object at the given index.
   *
   * <p>Always reads live from the VSUM to reflect current state.
   *
   * @param index 0-based index into the flat list of all root objects
   * @return the last segment of the resource URI (filename), or {@code null} if out of bounds
   */
  @Override
  public String getInstanceNameByIndex(int index) {
    List<EObject> roots = getAllRootObjects();
    if (index < 0 || index >= roots.size()) {
      return null;
    }
    EObject root = roots.get(index);
    Resource resource = root.eResource();
    if (resource == null || resource.getURI() == null) {
      return null;
    }
    return resource.getURI().lastSegment();
  }

  /**
   * Returns all root objects from all loaded model resources in the VSUM.
   *
   * <p>Always reads live from the VSUM to reflect current state.
   *
   * @return list of all root EObjects across all VSUM resources
   */
  @Override
  public List<EObject> getAllRootObjects() {
    return modelResources.get().stream().flatMap(r -> r.getContents().stream()).toList();
  }

  // ---------------------------------------------------------------------------
  // Transaction hook (incremental evaluation)
  // ---------------------------------------------------------------------------

  /**
   * Registers {@code listener} for the change propagations of the wrapped VSUM. Used only by an
   * incremental evaluation session ({@code tools.vitruv.dsls.vitruvocl.incremental}); nothing is
   * registered unless such a session is attached, so plain (full) evaluation is unaffected.
   *
   * @param listener receives one {@code finishedChangePropagation} callback per committed change
   * @throws IllegalStateException if this wrapper serves a single constraint check
   */
  @Override
  public void addTransactionListener(ChangePropagationListener listener) {
    requireVsum().addChangePropagationListener(listener);
  }

  /** Removes a listener registered with {@link #addTransactionListener}. */
  @Override
  public void removeTransactionListener(ChangePropagationListener listener) {
    requireVsum().removeChangePropagationListener(listener);
  }

  private VirtualModel requireVsum() {
    if (vsum == null) {
      throw new IllegalStateException(
          "Transactions are only available for wrappers of a virtual model");
    }
    return vsum;
  }

  // ---------------------------------------------------------------------------
  // Correspondence model access (for the ~ operator)
  // ---------------------------------------------------------------------------

  /**
   * Returns all objects corresponding to the given source object.
   *
   * <p>Used to evaluate the VitruvOCL {@code ~} (correspondence) operator.
   *
   * @param source the source object to look up correspondences for
   * @return set of all corresponding objects; empty if none exist
   */
  public Set<EObject> getCorrespondingObjects(EObject source) {
    return correspondenceModel.getCorrespondingEObjects(source);
  }

  /**
   * Returns all objects corresponding to the given source object that are instances of the given
   * target type.
   *
   * @param source the source object to look up correspondences for
   * @param targetType the EClass to filter corresponding objects by
   * @return set of corresponding objects that are instances of {@code targetType}; empty if none
   */
  public Set<EObject> getCorrespondingObjects(EObject source, EClass targetType) {
    return correspondenceModel.getCorrespondingEObjects(source).stream()
        .filter(obj -> targetType.isSuperTypeOf(obj.eClass()))
        .collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   *
   * <p>One tagged lookup in the correspondence model instead of one tag check per correspondent.
   */
  @Override
  public Set<EObject> getCorrespondingObjectsWithTag(EObject source, String tag) {
    return correspondenceModel.getCorrespondingEObjects(source, tag);
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@link #correspondenceHasTag} is defined as exactly that two-way tagged lookup.
   */
  @Override
  public boolean hasTaggedCorrespondenceLookup() {
    return true;
  }

  @Override
  public Optional<Set<EObject>> getAllObjectsInACorrespondence() {
    return Optional.of(correspondenceModel.getAllEObjectsInACorrespondence());
  }

  @Override
  public Map<String, Set<EObject>> getCorrespondingObjectsByTag(EObject source) {
    return correspondenceModel.getCorrespondingEObjectsWithTag(source);
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  @Override
  public String getSourceFileForInstance(EObject instance) {
    if (instance == null) {
      return null;
    }
    Resource resource = instance.eResource();
    if (resource == null || resource.getURI() == null) {
      return null;
    }
    return resource.getURI().lastSegment();
  }

  @Override
  public EObject getContextObjectByIndex(int index) {
    List<EObject> roots = getAllRootObjects();
    if (index < 0 || index >= roots.size()) {
      return null;
    }
    return roots.get(index);
  }

  @Override
  public EClass resolveEClassByShortName(String shortName) {
    return EcoreLookup.resolveEClassByShortNameInRegistry(metamodelRegistry, shortName);
  }

  @Override
  public EEnum resolveEEnum(String enumName) {
    return EcoreLookup.resolveEEnumInRegistry(metamodelRegistry, enumName);
  }

  /**
   * Checks whether a correspondence between obj1 and obj2 carries the given tag.
   *
   * <p>The Vitruvius CorrespondenceModelView does not expose tags directly — tags are an internal
   * attribute of the underlying Correspondence EMF objects. We therefore iterate the raw
   * correspondences via the correspondence model's internal stream.
   *
   * <p>Bidirectional: obj1 may appear in leftEObjects or rightEObjects.
   *
   * @param obj1 one side of the correspondence
   * @param obj2 other side of the correspondence
   * @param tag the required tag value
   * @return true if a tagged correspondence with that value relates obj1 and obj2
   */
  @Override
  public boolean correspondenceHasTag(EObject obj1, EObject obj2, String tag) {
    // getCorrespondingEObjects(obj1, tag) returns only objects linked via a
    // correspondence that carries exactly that tag
    Set<EObject> taggedCorrespondents = correspondenceModel.getCorrespondingEObjects(obj1, tag);
    if (taggedCorrespondents.contains(obj2)) {
      return true;
    }
    // bidirectional
    return correspondenceModel.getCorrespondingEObjects(obj2, tag).contains(obj1);
  }
}
