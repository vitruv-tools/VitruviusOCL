package tools.vitruv.dsls.vitruvocl.allinstances.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import tools.vitruv.dsls.vitruvocl.allinstances.cache.CacheConfiguration;

/**
 * Computes, for a given metamodel and {@link CacheConfiguration}, the minimal set of containment
 * references a model traversal must descend into in order to reach every instance that some query
 * actually needs.
 *
 * <p>This is the core contribution of Wei &amp; Kolovos, "An Efficient Computation Strategy for
 * allInstances()" (BigMDE 2015): Algorithm 1 in Section 3.2, generalized here (Section 3.3/3.4) to
 * cover both {@code allInstances()}/{@code allOfKind()} semantics (subtype-inclusive) and {@code
 * allOfType()} semantics (exact type only).
 *
 * <p>A containment reference {@code r} is traversable if and only if either
 *
 * <ul>
 *   <li>its reference type (or one of its subtypes) is itself requested by some query, or
 *   <li>traversing into it can transitively reach an EClass that satisfies the above, through some
 *       chain of further containment references.
 * </ul>
 *
 * <p>References that lead only to irrelevant subtrees (e.g. {@code Department.modules} when no
 * query ever needs a {@code Module} or anything reachable from it) are pruned — a traversal never
 * needs to step into them, which is where the performance win over "traverse everything" comes
 * from.
 */
public class ContainmentReachabilityAnalyzer {

  /**
   * Runs Algorithm 1 over a single-package metamodel.
   *
   * <p>Matches the paper's algorithm signature directly: iterate over every concrete EClass
   * reachable from {@code rootPackage}, and for each of its (inherited) containment references,
   * decide via {@link #shouldBeTraversed} whether it must be kept.
   *
   * @param rootPackage the root of the metamodel to analyze (its classifiers and all classifiers
   *     of its transitively nested subpackages)
   * @param cacheConfig the query analysis result (Section 3.1); its {@code traverse} field is
   *     updated in place with the result
   * @return the pruned set of containment references that must be traversed
   */
  public Set<EReference> computeTraversableReferences(
      EPackage rootPackage, CacheConfiguration cacheConfig) {
    return computeTraversableReferences(List.of(rootPackage), cacheConfig);
  }

  /**
   * Overload of {@link #computeTraversableReferences(EPackage, CacheConfiguration)} for metamodels
   * split across several {@link EPackage}s (e.g. cross-metamodel references) that are not nested
   * as subpackages of one another. Subtype search (see {@link #transitiveClosureOfSubtypes}) scans
   * all classifiers of all given packages, not just {@code rootPackage}'s own tree — subtypes of a
   * reference's declared type can legitimately live in a different package.
   *
   * @param rootPackages every {@link EPackage} that is part of the metamodel under analysis
   * @param cacheConfig the query analysis result; its {@code traverse} field is updated in place
   * @return the pruned set of containment references that must be traversed
   */
  public Set<EReference> computeTraversableReferences(
      Collection<EPackage> rootPackages, CacheConfiguration cacheConfig) {
    List<EClass> allClasses = new ArrayList<>();
    for (EPackage rootPackage : rootPackages) {
      collectClassifiersRecursively(rootPackage, allClasses);
    }
    addRequestedAndContainedClasses(allClasses, cacheConfig);

    Map<EClass, Set<EClass>> subtypeMemo = new HashMap<>();
    Set<EClass> leadsToRelevant =
        classesLeadingToRelevantInstances(allClasses, cacheConfig, subtypeMemo);

    Set<EReference> refs = new LinkedHashSet<>();
    for (EClass eClass : allClasses) {
      if (eClass.isAbstract()) {
        continue;
      }
      for (EReference ref : eClass.getEAllContainments()) {
        if (canHoldAny(ref, leadsToRelevant, allClasses, subtypeMemo)) {
          refs.add(ref);
        }
      }
    }

    cacheConfig.setTraverse(refs);
    return refs;
  }

  /**
   * Overload that additionally consults an {@link EPackage.Registry} to discover subtypes defined
   * in metamodels other than {@code rootPackage} (e.g. metamodels loaded into a {@code
   * ResourceSet}'s package registry). Registry descriptors are resolved defensively — a descriptor
   * that fails to resolve (e.g. a stale/unavailable entry) is skipped rather than propagating the
   * failure, since it does not belong to the metamodel actually being analyzed.
   *
   * @param rootPackage the primary root package of the metamodel under analysis
   * @param cacheConfig the query analysis result; its {@code traverse} field is updated in place
   * @param registry a package registry (e.g. {@code resourceSet.getPackageRegistry()}) to search
   *     for subtypes beyond {@code rootPackage}'s own tree
   * @return the pruned set of containment references that must be traversed
   */
  public Set<EReference> computeTraversableReferences(
      EPackage rootPackage, CacheConfiguration cacheConfig, EPackage.Registry registry) {
    Set<EPackage> packages = new LinkedHashSet<>();
    packages.add(rootPackage);
    packages.addAll(resolveRegistryPackages(registry));
    return computeTraversableReferences(packages, cacheConfig);
  }

  /**
   * Extends {@code classes} by every requested type and, transitively, by the declared type of
   * every containment reference of a known class — types that may live in packages outside the
   * analyzed ones (e.g. an unregistered or unloaded metamodel), but whose instances are still
   * reachable at runtime and must therefore take part in the analysis.
   */
  private static void addRequestedAndContainedClasses(
      List<EClass> classes, CacheConfiguration cacheConfig) {
    Set<EClass> known = new LinkedHashSet<>(classes);
    Deque<EClass> pending = new ArrayDeque<>(classes);
    for (EClass requested : cacheConfig.getAllOfKind()) {
      if (known.add(requested)) {
        pending.add(requested);
      }
    }
    for (EClass requested : cacheConfig.getAllOfType()) {
      if (known.add(requested)) {
        pending.add(requested);
      }
    }
    while (!pending.isEmpty()) {
      for (EReference ref : pending.poll().getEAllContainments()) {
        EClass target = ref.getEReferenceType();
        if (target != null && known.add(target)) {
          pending.add(target);
        }
      }
    }
    classes.clear();
    classes.addAll(known);
  }

  /**
   * Computes every class whose instances are either requested themselves or can (transitively,
   * via containment) contain a requested instance.
   *
   * <p>This is the set Algorithm 1's recursion explores, computed as a least fixpoint instead of by
   * recursion. The paper notes that its algorithm is "simplified for presentation purposes" and
   * needs memoisation against cyclic containment; a recursive formulation whose cycle guard
   * memoizes a provisional {@code false} is wrong for mutually containing classes, though: the
   * provisional answer for a reference still in progress gets stored permanently for every other
   * reference that reached it, so later laps around the cycle are pruned. The fixpoint has no
   * provisional answers and is therefore independent of cycles and of classifier order.
   *
   * <p>As in Algorithm 1, a subtype may declare containments its declared supertype does not have
   * (e.g. abstract {@code Member} without containments, its subtype {@code Staff} with {@code
   * webpage}), so a reference is judged by the subtype closure of its declared type.
   */
  private Set<EClass> classesLeadingToRelevantInstances(
      List<EClass> allClasses,
      CacheConfiguration cacheConfig,
      Map<EClass, Set<EClass>> subtypeMemo) {
    Set<EClass> result = new LinkedHashSet<>();
    for (EClass eClass : allClasses) {
      if (isRequested(eClass, cacheConfig)) {
        result.add(eClass);
      }
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (EClass eClass : allClasses) {
        if (result.contains(eClass)) {
          continue;
        }
        for (EReference ref : eClass.getEAllContainments()) {
          if (canHoldAny(ref, result, allClasses, subtypeMemo)) {
            result.add(eClass);
            changed = true;
            break;
          }
        }
      }
    }
    return result;
  }

  /**
   * Whether {@code ref} may hold an instance of one of {@code targets} at runtime, i.e. whether the
   * subtype closure of its declared type intersects {@code targets}.
   */
  private boolean canHoldAny(
      EReference ref,
      Set<EClass> targets,
      List<EClass> allClasses,
      Map<EClass, Set<EClass>> subtypeMemo) {
    Set<EClass> holdable =
        transitiveClosureOfSubtypes(ref.getEReferenceType(), allClasses, subtypeMemo);
    for (EClass type : holdable) {
      if (targets.contains(type)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether instances of exactly {@code type} are requested: {@code allOfType} matches the exact
   * type, {@code allOfKind} matches the requested type and all of its subtypes (OCL's {@code
   * allInstances()} returns direct and indirect instances, so a containment declared with a
   * concrete subtype, e.g. {@code Register.males : Male}, is needed for {@code
   * Person.allInstances()}).
   */
  private static boolean isRequested(EClass type, CacheConfiguration cacheConfig) {
    if (cacheConfig.getAllOfType().contains(type)) {
      return true;
    }
    for (EClass kind : cacheConfig.getAllOfKind()) {
      if (kind.isSuperTypeOf(type)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Computes {@code type} plus every subtype of {@code type} found among {@code allClasses}
   * (Section 3.3: a containment reference declared with an abstract or non-final type may hold
   * instances of any of its subtypes at runtime, so relevance must be checked against the whole
   * subtype closure, not just the declared type).
   *
   * <p>{@link EClass#isSuperTypeOf(EClass)} is reflexive (a class is a supertype of itself), so
   * {@code type} itself is included in the result without needing to be added separately.
   */
  private Set<EClass> transitiveClosureOfSubtypes(
      EClass type, List<EClass> allClasses, Map<EClass, Set<EClass>> subtypeMemo) {
    Set<EClass> cached = subtypeMemo.get(type);
    if (cached != null) {
      return cached;
    }
    Set<EClass> result = new LinkedHashSet<>();
    for (EClass candidate : allClasses) {
      if (type.isSuperTypeOf(candidate)) {
        result.add(candidate);
      }
    }
    result.add(type);
    subtypeMemo.put(type, result);
    return result;
  }

  /**
   * Recursively collects every {@link EClass} classifier of {@code ePackage} and its subpackages.
   */
  private void collectClassifiersRecursively(EPackage ePackage, List<EClass> out) {
    for (EClassifier classifier : ePackage.getEClassifiers()) {
      if (classifier instanceof EClass eClass) {
        out.add(eClass);
      }
    }
    for (EPackage subPackage : ePackage.getESubpackages()) {
      collectClassifiersRecursively(subPackage, out);
    }
  }

  /**
   * Resolves every {@link EPackage} reachable from a registry, tolerating unresolvable descriptors.
   */
  private Set<EPackage> resolveRegistryPackages(EPackage.Registry registry) {
    Set<EPackage> result = new LinkedHashSet<>();
    if (registry == null) {
      return Collections.emptySet();
    }
    for (Object value : registry.values()) {
      try {
        if (value instanceof EPackage ePackage) {
          result.add(ePackage);
        } else if (value instanceof EPackage.Descriptor descriptor) {
          EPackage resolved = descriptor.getEPackage();
          if (resolved != null) {
            result.add(resolved);
          }
        }
      } catch (RuntimeException resolutionFailure) {
        // A stale or unavailable registry entry does not belong to the metamodel being analyzed;
        // skip it rather than letting an unrelated resolution failure abort the whole analysis.
      }
    }
    return result;
  }
}
