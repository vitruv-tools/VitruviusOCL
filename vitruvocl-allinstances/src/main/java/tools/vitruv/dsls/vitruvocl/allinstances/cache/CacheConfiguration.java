package tools.vitruv.dsls.vitruvocl.allinstances.cache;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;

/**
 * Holds the outcome of the static query analysis (Wei &amp; Kolovos, BigMDE 2015, Section 3.1)
 * and, once computed, the pruned set of containment references that a model traversal actually
 * needs to descend into (Section 3.2, Algorithm 1).
 *
 * <p>A {@code CacheConfiguration} is derived purely from a set of {@code allInstances()}-style
 * call sites and the metamodel — it does not depend on any concrete instance model. This is what
 * allows it to be computed once and reused across many {@code compute()} invocations, even as the
 * underlying instance model changes between calls (see {@link
 * tools.vitruv.dsls.vitruvocl.allinstances.AllInstancesEngine}).
 */
public final class CacheConfiguration {

  /**
   * Types for which direct AND indirect instances (i.e. instances of subtypes too) must be
   * collected — this is the semantics of OCL's {@code allInstances()}.
   */
  private final Set<EClass> allOfKind = new LinkedHashSet<>();

  /** Types for which only direct (exact-type) instances must be collected, subtypes excluded. */
  private final Set<EClass> allOfType = new LinkedHashSet<>();

  /**
   * The containment references that a model traversal must descend into, as computed by {@link
   * tools.vitruv.dsls.vitruvocl.allinstances.analysis.ContainmentReachabilityAnalyzer}. Empty until
   * that analysis has run.
   */
  private final Set<EReference> traverse = new LinkedHashSet<>();

  /**
   * The classes the reachability analysis knew about. A traversal that meets an instance of any
   * other class cannot rely on {@link #traverse} for it and descends into all of its containments.
   * Empty until the analysis has run (then every class is treated as analysed).
   *
   * <p>Known limit: a subtype from an unanalysed package held by a pruned reference of an analysed
   * class is still not reached, because the walk never visits it. The analysis therefore needs
   * every package whose instances can occur in the model (the wrappers pass all loaded packages,
   * not only one per name).
   */
  private final Set<EClass> analyzedClasses = new LinkedHashSet<>();

  public Set<EClass> getAllOfKind() {
    return allOfKind;
  }

  public Set<EClass> getAllOfType() {
    return allOfType;
  }

  public Set<EReference> getTraverse() {
    return traverse;
  }

  /** The classes the reachability analysis knew about; see {@link #isAnalyzed}. */
  public Set<EClass> getAnalyzedClasses() {
    return Collections.unmodifiableSet(analyzedClasses);
  }

  /** Replaces the set of analysed classes; called together with {@link #setTraverse}. */
  public void setAnalyzedClasses(Set<EClass> classes) {
    analyzedClasses.clear();
    analyzedClasses.addAll(classes);
  }

  /**
   * Whether the pruning decision of {@link #traverse} covers instances of {@code type}; {@code true}
   * for every type as long as no analysis has recorded its classes.
   */
  public boolean isAnalyzed(EClass type) {
    return analyzedClasses.isEmpty() || analyzedClasses.contains(type);
  }

  /** Registers a type for which subtype-inclusive instance collection is required. */
  public void addAllOfKind(EClass type) {
    allOfKind.add(type);
  }

  /** Registers a type for which exact-type-only instance collection is required. */
  public void addAllOfType(EClass type) {
    allOfType.add(type);
  }

  /**
   * Replaces the traversable-reference set. Called by {@link
   * tools.vitruv.dsls.vitruvocl.allinstances.analysis.ContainmentReachabilityAnalyzer} after
   * running Algorithm 1.
   */
  public void setTraverse(Set<EReference> traversableRefs) {
    traverse.clear();
    traverse.addAll(traversableRefs);
  }

  /** Returns whether any query at all needs instances of {@code type} (kind- or type-wise). */
  public boolean isRelevant(EClass type) {
    return allOfKind.contains(type) || allOfType.contains(type);
  }
}
