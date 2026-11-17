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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.FeatureMapUtil;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.vitruv.dsls.vitruvocl.allinstances.AllInstancesEngine;
import tools.vitruv.dsls.vitruvocl.allinstances.cache.AllInstancesCallSite;
import tools.vitruv.dsls.vitruvocl.model.EcoreLookup;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;

/**
 * Manages metamodel and model instance loading for OCL constraint evaluation.
 *
 * <p>Provides metamodel-to-instance mapping required for constraint evaluation, supporting:
 *
 * <ul>
 *   <li>Loading Ecore metamodels with package name registration
 *   <li>Loading XMI model instances
 *   <li>Resolving qualified names like {@code spacecraft::Spacecraft} to EClass
 *   <li>Querying all instances of a given EClass (including subtypes), via an {@link
 *       tools.vitruv.dsls.vitruvocl.allinstances.AllInstancesEngine} — see {@link #getAllInstances}
 *       for details
 * </ul>
 *
 * <p>Implements {@link ModelAccess} for use across compilation phases, particularly in the
 * evaluation visitor where constraints access model elements.
 */
public class MetamodelWrapper implements ModelAccess {

  private static final String FEAT_LEFT_EOBJECTS = "leftEObjects";
  private static final String FEAT_RIGHT_EOBJECTS = "rightEObjects";

  private static final String REACTIONS_NS_URI =
      "http://vitruv.tools/metamodels/dsls/reactions/runtime/correspondence/1.0";

  /** Default directory for test model files (legacy support). */
  private static Path testModelsPath = Path.of("test-models");

  /** Returns the current test models path. */
  public static Path getTestModelsPath() {
    return testModelsPath;
  }

  /** Sets the test models path (for test setup). */
  public static void setTestModelsPath(Path path) {
    testModelsPath = path;
  }

  /** Maps package names to loaded EPackages. */
  private final Map<String, EPackage> metamodelRegistry = new HashMap<>();

  /**
   * Every {@link EClass} ever passed to {@link #getAllInstances}, in first-seen order: the call
   * sites of the allInstances() computation strategy of Wei &amp; Kolovos (BigMDE 2015).
   *
   * <p>The static query analysis of the paper (Section 3.2) happens in {@link
   * tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor}: before a constraint is evaluated, it
   * collects every type the constraint needs from the type-checked AST and requests all of them at
   * once via {@link #getAllInstances(Collection)}. Types of all constraints evaluated so far
   * accumulate here, so one traversal serves all of them.
   *
   * <p><b>Semantics note:</b> every prior call site is treated as {@code ALL_OF_KIND}
   * (subtype-inclusive), matching the old greedy implementation's behavior exactly — see {@link
   * #getAllInstances} for the detailed semantic-equivalence analysis.
   */
  private final Set<EClass> queriedAllInstancesTypes = new LinkedHashSet<>();

  /**
   * Lazily (re)built {@link AllInstancesEngine}; {@code null} means stale/not-yet-built.
   *
   * <p>Invalidated whenever {@link #queriedAllInstancesTypes} gains a new entry or a new metamodel
   * package is (un)registered — both change the metamodel-level pruning decision the engine's
   * cache configuration is based on.
   */
  private AllInstancesEngine allInstancesEngine;

  /** The packages {@link #allInstancesEngine} was analysed with. */
  private Set<EPackage> enginePackages = Set.of();

  /**
   * Result of the last single-pass traversal: the instances of every type in {@link
   * #queriedAllInstancesTypes}; {@code null} means stale/not-yet-computed.
   *
   * <p>This is the cache of Wei &amp; Kolovos (BigMDE 2015, Section 3.4): one traversal collects
   * all needed extensions, every later request is a lookup. It is discarded whenever an extension
   * may have changed: a new type is requested, a metamodel package is (un)registered, a model is
   * loaded, or a containment feature of a loaded model changes (see {@link #extensionTracker}).
   */
  private Map<EClass, List<EObject>> cachedInstancesByType;

  /**
   * Discards {@link #cachedInstancesByType} as soon as a containment feature anywhere below a
   * loaded model root changes — the only kind of change that can add an object to or remove one
   * from an extension (attribute and cross-reference changes cannot). As an {@link
   * EContentAdapter} it follows newly contained objects automatically. It does not resolve
   * containment proxies, so attaching it never loads further resources.
   *
   * <p>Not observed: changes made with notification delivery switched off ({@code
   * eSetDeliver(false)}); callers doing that must not rely on cached extensions.
   */
  private final EContentAdapter extensionTracker =
      new EContentAdapter() {
        @Override
        public void notifyChanged(Notification notification) {
          super.notifyChanged(notification);
          if (notification.isTouch()) {
            return;
          }
          if (affectsContainment(notification.getFeature())) {
            invalidateAllInstancesCache(false);
            invalidateCorrespondenceIndex(); // fragment URIs may have changed
          } else if (notification.getFeature() instanceof EAttribute attribute
              && attribute.isID()) {
            invalidateCorrespondenceIndex(); // an ID is the URI fragment of its object
          }
        }

        @Override
        protected boolean resolve() {
          return false;
        }
      };

  /**
   * Drops the correspondence index when a resource changes what its URIs resolve to: another URI,
   * other root objects, or a load/unload.
   */
  private final Adapter resourceTracker =
      new AdapterImpl() {
        @Override
        public void notifyChanged(Notification notification) {
          if (notification.isTouch() || !(notification.getNotifier() instanceof Resource)) {
            return;
          }
          int feature = notification.getFeatureID(Resource.class);
          if (feature == Resource.RESOURCE__URI
              || feature == Resource.RESOURCE__CONTENTS
              || feature == Resource.RESOURCE__IS_LOADED) {
            invalidateCorrespondenceIndex();
          }
        }
      };

  /**
   * Tracks every resource added to the resource set, also those loaded by resolving a proxy during
   * evaluation: a correspondence whose partner was not loaded when the index was built must be
   * found once it is. ID attributes inside such resources are tracked by {@link
   * #extensionTracker} as well.
   */
  private final Adapter resourceSetTracker =
      new AdapterImpl() {
        @Override
        public void notifyChanged(Notification notification) {
          if (notification.getFeatureID(ResourceSet.class) != ResourceSet.RESOURCE_SET__RESOURCES
              || notification.isTouch()) {
            return;
          }
          invalidateCorrespondenceIndex();
          for (Object added : addedValues(notification)) {
            if (added instanceof Resource resource) {
              track(resource);
            }
          }
        }
      };

  private static List<?> addedValues(Notification notification) {
    return switch (notification.getEventType()) {
      case Notification.ADD -> List.of(notification.getNewValue());
      case Notification.ADD_MANY -> (List<?>) notification.getNewValue();
      default -> List.of();
    };
  }

  /** Attaches the resource and content trackers to {@code resource} (once). */
  private void track(Resource resource) {
    if (!resource.eAdapters().contains(resourceTracker)) {
      resource.eAdapters().add(resourceTracker);
    }
    if (!resource.eAdapters().contains(extensionTracker)) {
      resource.eAdapters().add(extensionTracker);
    }
  }

  /**
   * Whether a change of {@code feature} can change which objects are contained: a containment
   * reference, or a feature map (whose entries may be containment references).
   */
  private static boolean affectsContainment(Object feature) {
    if (feature instanceof EReference reference) {
      return reference.isContainment();
    }
    return feature instanceof EAttribute attribute && FeatureMapUtil.isFeatureMap(attribute);
  }

  /**
   * Maps each registered EObject to its source filename. Uses identity (not equals) so different.
   * objects from same file are tracked separately.
   */
  private final Map<EObject, String> instanceSourceFile = new IdentityHashMap<>();

  /**
   * Correspondences loaded from .correspondence files via DOM (not EMF). Maps an absolute EMF URI
   * string to the set of corresponding absolute URI strings.
   */
  private final Map<String, Set<String>> correspondenceUriMap = new HashMap<>();

  /**
   * Maps a bidirectional key {@code "leftUri|rightUri"} to the set of tags on that correspondence.
   * Both directions are stored so lookups are O(1) regardless of order.
   */
  private final Map<String, Set<String>> correspondenceTagMap = new HashMap<>();

  /**
   * {@link #correspondenceUriMap} and {@link #correspondenceTagMap} resolved to objects: object →
   * partner → tags of their correspondences (an untagged correspondence contributes {@code null}).
   * Built on first use, so URIs are computed once instead of per lookup; {@code null} when stale,
   * i.e. after loading or unloading models or correspondences and after a containment change
   * (which changes fragment URIs).
   */
  private Map<EObject, Map<EObject, Set<String>>> correspondenceIndex;

  /** Ordered list of context-level (root) EObjects for index-based lookup from evaluator. */
  private final List<EObject> contextObjects = new ArrayList<>();

  /** Maps instance index to source filename for error reporting (index matches contextObjects). */
  private final List<String> instanceFilenames = new ArrayList<>();

  /** EMF resource set for loading metamodels. */
  private final ResourceSet resourceSet;

  /** Matches any {@code platform:/plugin/.../<name>.ecore} URI in an ecore file. */
  private static final Pattern PLATFORM_PLUGIN_ECORE_PATTERN =
      Pattern.compile("platform:/plugin/[^\"#\\s]+\\.ecore");

  /**
   * Scans a set of workspace {@code .ecore} files for {@code platform:/plugin/} cross-references
   * and registers URI mappings so EMF can resolve them to local files without any manual
   * configuration.
   *
   * <p>How it works:
   *
   * <ol>
   *   <li>Build a {@code filename → file URI} map from {@code ecorePaths} (e.g. {@code
   *       "stoex.ecore" → file:/C:/…/stoex.ecore}).
   *   <li>For each ecore file, grep its raw text for {@code platform:/plugin/…/name.ecore}.
   *   <li>If the referenced filename is found in the workspace, add the exact mapping to the EMF
   *       {@link org.eclipse.emf.ecore.resource.URIConverter} of this resource set.
   * </ol>
   *
   * <p>Call this <em>before</em> {@link #loadMetamodel(Path)} so that inherited features from
   * cross-ecore supertypes (e.g. {@code stoex::RandomVariable#specification}) are visible to the
   * type checker without touching any project file.
   *
   * @param ecorePaths all {@code .ecore} files found in the workspace
   */
  public synchronized void registerWorkspaceEcoresForPlatformResolution(List<Path> ecorePaths) {
    // filename.ecore -> file URI of the local copy
    Map<String, URI> byFilename = new HashMap<>();
    for (Path p : ecorePaths) {
      String name = p.getFileName().toString();
      byFilename.put(name, URI.createFileURI(p.toAbsolutePath().toString()));
    }

    Map<URI, URI> uriMap = resourceSet.getURIConverter().getURIMap();

    for (Path ecorePath : ecorePaths) {
      try {
        String content = Files.readString(ecorePath);
        Matcher m = PLATFORM_PLUGIN_ECORE_PATTERN.matcher(content);
        while (m.find()) {
          String platformUriStr = m.group();
          URI platformUri = URI.createURI(platformUriStr);
          String filename = platformUri.lastSegment();
          URI localUri = byFilename.get(filename);
          if (localUri != null) {
            uriMap.computeIfAbsent(platformUri, k -> localUri);
          }
        }
      } catch (IOException e) {
        // unreadable file — skip silently
      }
    }
  }

  /**
   * The wrapper's resource set. Resolving a cross-file proxy during evaluation (outside the
   * wrapper's synchronized methods) loads the target resource into it, so its resource lookups and
   * loads take the wrapper's lock: they must not race with loading or unloading models through the
   * wrapper. Such a load therefore also blocks other threads using the wrapper while it reads the
   * file.
   */
  private final class LockedResourceSet extends ResourceSetImpl {
    @Override
    public Resource getResource(URI uri, boolean loadOnDemand) {
      synchronized (MetamodelWrapper.this) {
        return super.getResource(uri, loadOnDemand);
      }
    }

    @Override
    public EObject getEObject(URI uri, boolean loadOnDemand) {
      synchronized (MetamodelWrapper.this) {
        return super.getEObject(uri, loadOnDemand);
      }
    }
  }

  /** Creates metamodel wrapper with EMF resource factories configured. */
  public MetamodelWrapper() {
    this.resourceSet = new LockedResourceSet();
    resourceSet.eAdapters().add(resourceSetTracker);
    resourceSet
        .getResourceFactoryRegistry()
        .getExtensionToFactoryMap()
        .put("ecore", new EcoreResourceFactoryImpl());
    resourceSet
        .getResourceFactoryRegistry()
        .getExtensionToFactoryMap()
        .put("xmi", new XMIResourceFactoryImpl());
    ensureReactionsCorrespondenceRegistered();
  }

  /**
   * Dynamically registers the {@code ReactionsCorrespondence} EClass as a concrete subtype of the
   * base {@code Correspondence} class when the Vitruvius reactions runtime JAR is not on the
   * classpath.
   *
   * <p>The correspondence files produced by Vitruvius use {@code
   * xsi:type="correspondence_1:ReactionsCorrespondence"} (namespace URI {@code
   * http://vitruv.tools/metamodels/dsls/reactions/runtime/correspondence/1.0}). When the reactions
   * runtime is absent, EMF cannot resolve this type and silently drops all correspondence entries,
   * causing every {@code ~} operator to evaluate to {@code false}. Registering a minimal dynamic
   * EPackage containing {@code ReactionsCorrespondence} as a subtype of the known {@code
   * Correspondence} class is sufficient to let EMF load the entries correctly; the inherited {@code
   * leftEObjects} / {@code rightEObjects} features are resolved via the base class.
   */
  private static void ensureReactionsCorrespondenceRegistered() {
    if (EPackage.Registry.INSTANCE.containsKey(REACTIONS_NS_URI)) {
      return;
    }

    final String CORR_NS_URI = "http://vitruv.tools/metamodels/change/correspondence/1.0";
    EPackage corrPackage = (EPackage) EPackage.Registry.INSTANCE.get(CORR_NS_URI);
    if (corrPackage == null) {
      corrPackage = forceInitCorrespondencePackage();
    }
    if (corrPackage == null) {
      buildBaseCorrespondencePackage(CORR_NS_URI);
    }

    // ReactionsCorrespondence is self-contained: no cross-package supertype.
    // Cross-package EClass inheritance in dynamic EPackages causes EMF to reject the class
    // as "not a valid classifier" during XMI loading. All required features are added directly.
    EPackage reactionsPackage = EcoreFactory.eINSTANCE.createEPackage();
    reactionsPackage.setName("correspondence_1");
    reactionsPackage.setNsPrefix("correspondence_1");
    reactionsPackage.setNsURI(REACTIONS_NS_URI);

    EClass reactionsCorr = EcoreFactory.eINSTANCE.createEClass();
    reactionsCorr.setName("ReactionsCorrespondence");
    addCorrespondenceFeatures(reactionsCorr);

    reactionsPackage.getEClassifiers().add(reactionsCorr);
    EPackage.Registry.INSTANCE.put(REACTIONS_NS_URI, reactionsPackage);
  }

  /**
   * Builds the base correspondence EPackage dynamically.
   *
   * <p>Creates a minimal structural copy of the real package: a {@code Correspondences} root class
   * (with {@code correspondences} containment using {@code EObject} as element type so that any
   * concrete subtype can be held without type-compatibility issues) and a concrete {@code
   * Correspondence} class carrying {@code leftEObjects}, {@code rightEObjects}, and {@code tag}.
   */
  private static EPackage buildBaseCorrespondencePackage(String nsUri) {
    EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
    pkg.setName("correspondence");
    pkg.setNsPrefix("correspondence");
    pkg.setNsURI(nsUri);

    // Concrete (non-abstract) Correspondence class with the three needed features.
    EClass corrClass = EcoreFactory.eINSTANCE.createEClass();
    corrClass.setName("Correspondence");
    addCorrespondenceFeatures(corrClass);

    // Correspondences root — holds any EObject so xsi:type subtypes are accepted.
    EClass corrsClass = EcoreFactory.eINSTANCE.createEClass();
    corrsClass.setName("Correspondences");

    EReference corrsRef = EcoreFactory.eINSTANCE.createEReference();
    corrsRef.setName("correspondences");
    corrsRef.setEType(EcorePackage.Literals.EOBJECT); // EObject: accepts any concrete subtype
    corrsRef.setUpperBound(-1);
    corrsRef.setContainment(true);
    corrsClass.getEStructuralFeatures().add(corrsRef);

    pkg.getEClassifiers().add(corrsClass);
    pkg.getEClassifiers().add(corrClass);

    EPackage.Registry.INSTANCE.put(nsUri, pkg);
    return pkg;
  }

  /** Adds leftEObjects, rightEObjects, and tag features to the given EClass. */
  private static void addCorrespondenceFeatures(EClass cls) {
    EReference leftRef = EcoreFactory.eINSTANCE.createEReference();
    leftRef.setName(FEAT_LEFT_EOBJECTS);
    leftRef.setEType(EcorePackage.Literals.EOBJECT);
    leftRef.setUpperBound(-1);
    cls.getEStructuralFeatures().add(leftRef);

    EReference rightRef = EcoreFactory.eINSTANCE.createEReference();
    rightRef.setName(FEAT_RIGHT_EOBJECTS);
    rightRef.setEType(EcorePackage.Literals.EOBJECT);
    rightRef.setUpperBound(-1);
    cls.getEStructuralFeatures().add(rightRef);

    EAttribute tagAttr = EcoreFactory.eINSTANCE.createEAttribute();
    tagAttr.setName("tag");
    tagAttr.setEType(EcorePackage.Literals.ESTRING);
    cls.getEStructuralFeatures().add(tagAttr);
  }

  /**
   * Forces initialization of the generated {@code CorrespondencePackage} EMF class via reflection.
   *
   * <p>In standalone (non-OSGi) mode the EMF generated classes are in the fat JAR but their static
   * initializers are not automatically called. Accessing {@code CorrespondencePackage.eINSTANCE}
   * triggers {@code CorrespondencePackageImpl.init()}, which registers the package in {@link
   * EPackage.Registry#INSTANCE}.
   *
   * @return the registered {@link EPackage}, or {@code null} if reflection fails
   */
  private static EPackage forceInitCorrespondencePackage() {
    final String CORR_NS_URI = "http://vitruv.tools/metamodels/change/correspondence/1.0";
    // Try generated interface — accessing eINSTANCE triggers static init + package registration.
    String[] candidateClasses = {
      "tools.vitruv.change.correspondence.CorrespondencePackage",
      "tools.vitruv.change.correspondence.impl.CorrespondencePackageImpl"
    };
    for (String className : candidateClasses) {
      try {
        Class<?> cls = Class.forName(className);
        java.lang.reflect.Field f = cls.getField("eINSTANCE");
        f.get(null); // triggers static init
        EPackage pkg = (EPackage) EPackage.Registry.INSTANCE.get(CORR_NS_URI);
        if (pkg != null) {
          return pkg;
        }
      } catch (Exception e) {
        // class not on classpath — expected in standalone mode, fall through to dynamic build
      }
    }
    return null;
  }

  /**
   * Loads metamodel with explicit package name override.
   *
   * @param packageName Name to register metamodel under (for qualified name resolution)
   * @param ecoreFile Path to .ecore metamodel file
   * @throws IOException If file cannot be read or is empty
   */
  public synchronized void loadMetamodel(String packageName, Path ecoreFile) throws IOException {
    EPackage ePackage = loadRootPackage(resourceSet, ecoreFile);
    // packageName may refer to a nested eSubpackage (e.g. "repository" inside pcm.ecore), so
    // don't blindly bind it to the root package here — registerPackageRecursively() resolves
    // the correct (sub-)package for each name found in the file.
    registerPackageRecursively(ePackage);
  }

  /**
   * Loads metamodel using its intrinsic package name from file.
   *
   * @param ecoreFile Path to .ecore metamodel file
   * @throws IOException If file cannot be read or is empty
   */
  public synchronized void loadMetamodel(Path ecoreFile) throws IOException {
    EPackage ePackage = loadRootPackage(resourceSet, ecoreFile);
    String name = ePackage.getName();
    if (name != null) {
      metamodelRegistry.put(name, ePackage);
    }
    registerPackageRecursively(ePackage);
  }

  /**
   * Loads {@code ecoreFile} into {@code target} and returns its root package.
   *
   * <p>EMF reports malformed files with unchecked exceptions and keeps the failed resource in the
   * resource set, where a later {@code getResource} would hand it out again. Every failure is
   * therefore reported as an {@link IOException} and the failed resource is removed again.
   *
   * @throws IOException if the file cannot be read or parsed, is empty, or has no package root
   */
  private static EPackage loadRootPackage(ResourceSet target, Path ecoreFile) throws IOException {
    URI uri = URI.createFileURI(ecoreFile.toAbsolutePath().toString());
    Resource resource;
    try {
      resource = target.getResource(uri, true);
    } catch (RuntimeException e) {
      discardResource(target, uri);
      throw new IOException("Cannot load " + ecoreFile + ": " + e.getMessage(), e);
    }
    if (resource.getContents().isEmpty()) {
      discardResource(target, uri);
      throw new IOException("Empty .ecore file: " + ecoreFile);
    }
    if (!(resource.getContents().get(0) instanceof EPackage ePackage)) {
      discardResource(target, uri);
      throw new IOException("No EPackage at the root of " + ecoreFile);
    }
    return ePackage;
  }

  private static void discardResource(ResourceSet target, URI uri) {
    Resource resource = target.getResource(uri, false);
    if (resource != null) {
      resource.unload();
      target.getResources().remove(resource);
    }
  }

  /**
   * Registers an {@link EPackage} and all its sub-packages recursively in both {@link
   * EPackage.Registry#INSTANCE} (by nsURI, for EMF XMI loading) and {@link #metamodelRegistry} (by
   * package name, for constraint type resolution).
   *
   * <p>Without this, model instance files that reference sub-package types (e.g. {@code
   * xmlns:tires="tires"}) cause a {@code PackageNotFoundException} during loading because only the
   * root package's nsURI is known to EMF.
   */
  private void registerPackageRecursively(EPackage pkg) {
    if (pkg.getNsURI() != null) {
      EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
    }
    // Also register by name so resolveEClass("tires", "Tire") works. Every loaded package changes
    // what computeTraversableReferences() can see, also one whose name is already taken.
    if (pkg.getName() != null) {
      metamodelRegistry.putIfAbsent(pkg.getName(), pkg);
    }
    invalidateAllInstancesCache(true);
    for (EPackage subPkg : pkg.getESubpackages()) {
      registerPackageRecursively(subPkg);
    }
  }

  /**
   * Reloads a previously loaded metamodel from disk, replacing the old version in the registry.
   *
   * <p>If the file was not previously loaded this behaves identically to {@link
   * #loadMetamodel(Path)}. Call this when the {@code .ecore} file has been modified on disk (e.g.
   * triggered by a {@code workspace/didChangeWatchedFiles} event from the language client).
   *
   * <p>The new file is parsed in a separate resource set first. If it cannot be loaded, the
   * previously loaded version stays in place, so a half-saved or broken file does not make every
   * type from that metamodel disappear. On success the parsed resource itself replaces the old one,
   * so the file is read only once and cannot change between the check and the swap.
   *
   * @param ecoreFile path to the modified {@code .ecore} file
   * @throws IOException if the file cannot be read or is empty after modification
   */
  public synchronized void reloadMetamodel(Path ecoreFile) throws IOException {
    ResourceSet probe = new ResourceSetImpl();
    probe
        .getResourceFactoryRegistry()
        .getExtensionToFactoryMap()
        .put("ecore", new EcoreResourceFactoryImpl());
    probe.getURIConverter().getURIMap().putAll(resourceSet.getURIConverter().getURIMap());
    EPackage fresh = loadRootPackage(probe, ecoreFile);
    Resource freshResource = fresh.eResource();

    unloadMetamodel(ecoreFile);
    probe.getResources().remove(freshResource);
    resourceSet.getResources().add(freshResource);
    if (fresh.getName() != null) {
      metamodelRegistry.put(fresh.getName(), fresh);
    }
    registerPackageRecursively(fresh);
  }

  /**
   * Removes a previously loaded metamodel from the registry and the EMF resource set.
   *
   * <p>The package and its sub-packages are also removed from {@link EPackage.Registry#INSTANCE} so
   * that subsequent loads start from a clean state. Names and nsURIs that another loaded metamodel
   * also provides are then handed to that metamodel again. No-op if the file was never loaded.
   *
   * @param ecoreFile path to the {@code .ecore} file to remove
   */
  public synchronized void unloadMetamodel(Path ecoreFile) {
    invalidateCorrespondenceIndex(); // removed objects must leave the index
    String targetUri = URI.createFileURI(ecoreFile.toAbsolutePath().toString()).toString();

    // Find the cached Resource in the ResourceSet by URI.
    Resource toRemove = null;
    for (Resource res : new ArrayList<>(resourceSet.getResources())) {
      if (res.getURI().toString().equals(targetUri)) {
        toRemove = res;
        break;
      }
    }

    if (toRemove == null) {
      return; // file was never loaded — nothing to do
    }

    // Remove the EPackage and all its sub-packages from our registry and the global EMF registry.
    if (!toRemove.getContents().isEmpty()
        && toRemove.getContents().get(0) instanceof EPackage pkg) {
      unregisterPackageRecursively(pkg);
      // The known metamodel package set shrank, invalidating any cached engine built from it.
      invalidateCorrespondenceIndex();
      invalidateAllInstancesCache(true);
    }
    // Before unload() turns them into proxies: the file's EClasses must not stay in the engine's
    // type set, where they would keep the whole unloaded package graph alive.
    forgetQueriedTypesOf(toRemove);

    toRemove.unload();
    resourceSet.getResources().remove(toRemove);

    // A package shadowed by the removed one (same name or nsURI) becomes visible again.
    for (Resource remaining : new ArrayList<>(resourceSet.getResources())) {
      if (!remaining.getContents().isEmpty()
          && remaining.getContents().get(0) instanceof EPackage remainingPkg) {
        registerPackageRecursively(remainingPkg);
      }
    }
  }

  private synchronized void forgetQueriedTypesOf(Resource resource) {
    queriedAllInstancesTypes.removeIf(type -> type.eResource() == resource);
  }

  /**
   * Reverses {@link #registerPackageRecursively}: removes {@code pkg} and its sub-packages by name
   * and by nsURI. Entries that meanwhile point to a package from another file are left alone.
   *
   * <p>Sub-packages must be removed too: registration uses {@code putIfAbsent}, so a stale
   * sub-package entry would otherwise survive a reload and shadow the new version.
   */
  private void unregisterPackageRecursively(EPackage pkg) {
    if (pkg.getName() != null) {
      metamodelRegistry.remove(pkg.getName(), pkg);
    }
    if (pkg.getNsURI() != null && EPackage.Registry.INSTANCE.get(pkg.getNsURI()) == pkg) {
      EPackage.Registry.INSTANCE.remove(pkg.getNsURI());
    }
    for (EPackage subPkg : pkg.getESubpackages()) {
      unregisterPackageRecursively(subPkg);
    }
  }

  /**
   * Loads model instance from absolute path, indexing all objects by EClass.
   *
   * @param xmiPath Absolute path to XMI model file
   * @throws IOException If file cannot be read
   */
  public synchronized void loadModelInstance(Path xmiPath) throws IOException {
    // Correspondence files are loaded via DOM to avoid EMF dynamic-EPackage type-validation issues.
    if (xmiPath.getFileName().toString().endsWith(".correspondence")) {
      loadCorrespondenceViaDOM(xmiPath);
      return;
    }

    ResourceSet instanceResourceSet = this.resourceSet;

    String extension = xmiPath.getFileName().toString();
    int dotIndex = extension.lastIndexOf('.');
    if (dotIndex > 0) {
      extension = extension.substring(dotIndex + 1);
    }

    instanceResourceSet
        .getResourceFactoryRegistry()
        .getExtensionToFactoryMap()
        .put(extension, new XMIResourceFactoryImpl());

    Resource resource =
        instanceResourceSet.getResource(
            URI.createFileURI(xmiPath.toAbsolutePath().toString()), true);

    String filename = xmiPath.getFileName().toString();

    for (EObject root : resource.getContents()) {
      registerSourceFileRecursively(root, filename);
      // Register root as context candidate (one entry per root EObject per file)
      contextObjects.add(root);
      instanceFilenames.add(filename);
    }
    // New roots extend the traversal root set, so every cached extension may have grown.
    invalidateAllInstancesCache(false);
    invalidateCorrespondenceIndex(); // stored URIs may resolve to the new objects
  }

  /**
   * Loads model instance from TEST_MODELS_PATH directory (legacy method).
   *
   * @param xmiFileName Filename relative to TEST_MODELS_PATH
   * @throws IOException If file cannot be read
   */
  public synchronized void loadModelInstance(String xmiFileName) throws IOException {
    loadModelInstance(testModelsPath.resolve(xmiFileName));
  }

  /**
   * Returns the context EObject at the given evaluation index.
   *
   * @param index The evaluation index (0-based)
   * @return The EObject at that index, or null if out of bounds
   */
  @Override
  public synchronized EObject getContextObjectByIndex(int index) {
    if (index >= 0 && index < contextObjects.size()) {
      return contextObjects.get(index);
    }
    return null;
  }

  /**
   * Internal recursive helper. All levels add to {@link #instanceSourceFile}. Only top-level roots
   * are registered in contextObjects/instanceFilenames.
   */
  private void registerSourceFileRecursively(EObject instance, String sourceFile) {
    instanceSourceFile.put(instance, sourceFile);

    for (EObject child : instance.eContents()) {
      registerSourceFileRecursively(child, sourceFile);
    }
  }

  /**
   * Resolves fully qualified name to EClass.
   *
   * @param metamodelName Package name (e.g., "spacecraft")
   * @param className Class name (e.g., "Spacecraft")
   * @return Resolved EClass, or null if not found
   */
  @Override
  public synchronized EClass resolveEClass(String metamodelName, String className) {
    return EcoreLookup.resolveEClassInRegistry(metamodelRegistry, metamodelName, className);
  }

  /**
   * Returns all instances of given EClass, including subtype instances.
   *
   * <p><b>Implementation:</b> delegates to an {@link AllInstancesEngine} (Wei &amp; Kolovos,
   * "An Efficient Computation Strategy for allInstances()", BigMDE 2015) instead of indexing every
   * loaded object by its exact type up front. The engine's query analysis is built lazily from
   * {@link #queriedAllInstancesTypes} — the set of EClasses ever passed to this method — since this
   * method is the only place in the codebase constraints ever ask for instances through (see {@link
   * tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor}, its sole caller); the set of types
   * this method has ever been called with is therefore exactly the set of types constraints need,
   * with no separate AST pre-scan required.
   *
   * <p><b>Semantic equivalence with the previous greedy implementation:</b> the old code indexed
   * every loaded object by its exact {@link EClass} (via an unconditional {@code eContents()}
   * walk at load time) and, on every call, returned {@code index[eClass]} unioned with
   * {@code index[T]} for every recorded type {@code T} with {@code eClass.isSuperTypeOf(T)} — i.e.
   * subtype-inclusive ("allInstances()"/allOfKind semantics), never exact-type-only. This method
   * reproduces that exactly: every call site is registered as {@code ALL_OF_KIND}, and {@link
   * tools.vitruv.dsls.vitruvocl.allinstances.collect.SinglePassInstanceCollector} matches instances
   * via the same {@code kindType.isSuperTypeOf(actualType)} relation. The traversal root set is
   * {@link #contextObjects} — precisely the roots the old {@code instances} index was ever
   * populated from (via {@link #loadModelInstance}) — not {@link #getAllRootObjects()}, which would
   * also include metamodel (.ecore) and correspondence resources the old index never touched.
   *
   * <p><b>Caching:</b> one traversal collects the instances of every type requested so far; its
   * result is cached and every further request is a lookup (Section 3.4 of the paper). The cache is
   * discarded when a new type is requested, a metamodel package is (un)registered, a model is
   * loaded, or a containment feature of a loaded model changes, so changes to the instance model
   * between calls are always reflected (see {@link #cachedInstancesByType}).
   *
   * @param eClass EClass to query
   * @return List of all direct and indirect instances (unmodifiable)
   */
  @Override
  public synchronized List<EObject> getAllInstances(EClass eClass) {
    return getAllInstances(List.of(eClass)).getOrDefault(eClass, Collections.emptyList());
  }

  /**
   * Returns the instances of all given types, collected together with those of every type
   * requested before in a single traversal (see {@link #getAllInstances(EClass)}).
   *
   * <p>Synchronized because the call-site set, engine, and cache are shared, mutable state —
   * including across threads when constraints are evaluated in parallel. Computing the cache under
   * the lock is intended: concurrent first requests wait for the one traversal instead of each
   * performing their own.
   *
   * @param types the EClasses whose instances are needed
   * @return an unmodifiable map with an entry for every requested type (and possibly more)
   */
  @Override
  public synchronized Map<EClass, List<EObject>> getAllInstances(Collection<EClass> types) {
    if (queriedAllInstancesTypes.addAll(types)) {
      invalidateAllInstancesCache(true); // newly-seen call site -> cache configuration is stale
    }
    Set<EPackage> packages = analyzedPackages();
    if (!enginePackages.containsAll(packages)) {
      // a package the pruning does not know may hold instances below a pruned reference
      invalidateAllInstancesCache(true);
    }
    if (cachedInstancesByType == null) {
      if (allInstancesEngine == null) {
        allInstancesEngine = buildAllInstancesEngine(packages);
        enginePackages = packages;
      }
      trackExtensionChanges();
      cachedInstancesByType = freeze(allInstancesEngine.compute(contextObjects));
    }
    return cachedInstancesByType;
  }

  /**
   * Discards the cached instances, and with {@code engineToo} also the engine (whose pruning
   * depends on the call sites and the metamodel, not on the instance model).
   */
  private synchronized void invalidateAllInstancesCache(boolean engineToo) {
    cachedInstancesByType = null;
    if (engineToo) {
      allInstancesEngine = null;
    }
  }

  /**
   * Attaches {@link #extensionTracker} to every model root it is not attached to (any more) — also
   * re-attaching it if something removed the root's adapters in the meantime.
   */
  private void trackExtensionChanges() {
    for (EObject root : contextObjects) {
      if (!root.eAdapters().contains(extensionTracker)) {
        root.eAdapters().add(extensionTracker);
      }
      Resource resource = root.eResource();
      if (resource != null) {
        track(resource);
      }
    }
  }

  private static Map<EClass, List<EObject>> freeze(Map<EClass, List<EObject>> instancesByType) {
    Map<EClass, List<EObject>> frozen = new HashMap<>();
    instancesByType.forEach((type, instances) -> frozen.put(type, List.copyOf(instances)));
    return Collections.unmodifiableMap(frozen);
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
   * Every package instances can come from (see {@link AnalyzedPackages}): the name-keyed registry
   * keeps only one package per name, so the root packages of all loaded {@code .ecore} resources,
   * the packages of the model roots and the instantiated packages of the global and the resource
   * set's package registry are added.
   */
  private Set<EPackage> analyzedPackages() {
    List<EPackage> known = new ArrayList<>(metamodelRegistry.values());
    for (Resource resource : resourceSet.getResources()) {
      for (EObject content : resource.getContents()) {
        if (content instanceof EPackage pkg) {
          known.add(pkg);
        }
      }
    }
    List<Object> registered =
        new ArrayList<>(AnalyzedPackages.snapshot(EPackage.Registry.INSTANCE));
    registered.addAll(resourceSet.getPackageRegistry().values());
    return AnalyzedPackages.collect(known, contextObjects, registered);
  }

  /**
   * Returns all registered metamodel package names.
   *
   * @return Unmodifiable set of package names
   */
  @Override
  public synchronized Set<String> getAvailableMetamodels() {
    return Set.copyOf(metamodelRegistry.keySet()); // a snapshot, see getContextObjects()
  }

  /**
   * Returns source filename for the context object at the given evaluation index. Index corresponds
   * to the i-th root EObject loaded (one per root per XMI file).
   *
   * @param index The evaluation index (0-based, one per root context object)
   * @return The filename (e.g., "spacecraft-atlas.spacemission"), or null if out of bounds
   */
  @Override
  public synchronized String getInstanceNameByIndex(int index) {
    if (index >= 0 && index < instanceFilenames.size()) {
      return instanceFilenames.get(index);
    }
    return null;
  }

  /**
   * Returns the source filename for a specific EObject instance (by identity). More reliable than
   * index-based lookup.
   */
  public synchronized String getSourceFileForInstance(EObject instance) {
    return instanceSourceFile.get(instance);
  }

  /** Returns all registered context (root) objects in load order. */
  public synchronized List<EObject> getContextObjects() {
    return List.copyOf(contextObjects); // a snapshot: the list changes when models are loaded
  }

  /**
   * Returns all root objects from all loaded model resources.
   *
   * <p>Iterates through all resources in the resource set and collects their root contents. This
   * includes metamodel packages, model instances, and correspondence models.
   *
   * @return List of all root EObjects from all loaded resources
   */
  @Override
  public synchronized List<EObject> getAllRootObjects() {
    List<EObject> roots = new ArrayList<>();

    // Iterate through all resources in the resource set
    for (Resource resource : resourceSet.getResources()) {
      // Add all root contents from this resource
      roots.addAll(resource.getContents());
    }
    return roots;
  }

  /**
   * Resolves an EClass by its unqualified short name across all registered metamodel packages.
   *
   * <p>Iterates all loaded {@link EPackage}s and returns the first {@link EClassifier} whose name
   * equals {@code shortName} and which is an {@link EClass}. Subpackages are also searched one
   * level deep.
   *
   * @param shortName the unqualified class name (e.g., {@code "Coordinate"})
   * @return the first matching {@link EClass}, or {@code null} if not found
   */
  @Override
  public synchronized EClass resolveEClassByShortName(String shortName) {
    return EcoreLookup.resolveEClassByShortNameInRegistry(metamodelRegistry, shortName);
  }

  @Override
  public synchronized EEnum resolveEEnum(String enumName) {
    return EcoreLookup.resolveEEnumInRegistry(metamodelRegistry, enumName);
  }

  /**
   * Returns the EPackage registered under the given metamodel name.
   *
   * <p>Used by the language server completion provider to enumerate all EClass names within a
   * package (e.g., for {@code JavaMM::} prefix completion).
   *
   * @param metamodelName the package name (e.g., {@code "JavaMM"})
   * @return the registered {@link EPackage}, or {@code null} if not found
   */
  public synchronized EPackage getEPackage(String metamodelName) {
    return metamodelRegistry.get(metamodelName);
  }

  /**
   * Loads a Vitruvius {@code .correspondence} file via DOM (not EMF) and populates {@link
   * #correspondenceUriMap}.
   *
   * <p>EMF cannot reliably load correspondence XMI files in standalone mode because the {@code
   * ReactionsCorrespondence} type is only available as a dynamic EClass with no Java backing class,
   * which triggers {@code IllegalValueException} during containment validation. Parsing the XML
   * directly with a DOM parser is simpler and avoids all type-checking issues.
   *
   * <p>Each {@code <correspondences>} element's {@code leftEObjects} and {@code rightEObjects}
   * child hrefs are resolved to absolute EMF URIs relative to the correspondence file location and
   * stored bidirectionally in {@link #correspondenceUriMap}.
   */
  @SuppressWarnings("java:S3776")
  private void loadCorrespondenceViaDOM(Path corrPath) throws IOException {
    URI baseUri = URI.createFileURI(corrPath.toAbsolutePath().toString());
    try {
      DocumentBuilder builder = newSecureDocumentBuilder();
      builder.setErrorHandler(null); // suppress SAX warnings
      Document doc = builder.parse(corrPath.toFile());

      NodeList corrNodes = doc.getElementsByTagName("correspondences");
      for (int i = 0; i < corrNodes.getLength(); i++) {
        Element corrEl = (Element) corrNodes.item(i);
        String tag = corrEl.getAttribute("tag");
        // Support two XMI serialisation styles:
        //   (a) inline attributes:  <correspondences leftEObjects="..." rightEObjects="..."/>
        //   (b) child elements:     <correspondences><leftEObjects href="..."/></correspondences>
        List<String> lefts = collectHrefsFromAttr(corrEl, FEAT_LEFT_EOBJECTS, baseUri);
        if (lefts.isEmpty()) {
          lefts = collectHrefsFromChildElements(corrEl, FEAT_LEFT_EOBJECTS, baseUri);
        }
        List<String> rights = collectHrefsFromAttr(corrEl, FEAT_RIGHT_EOBJECTS, baseUri);
        if (rights.isEmpty()) {
          rights = collectHrefsFromChildElements(corrEl, FEAT_RIGHT_EOBJECTS, baseUri);
        }
        for (String l : lefts) {
          for (String r : rights) {
            correspondenceIndex = null;
            correspondenceUriMap.computeIfAbsent(l, k -> new LinkedHashSet<>()).add(r);
            correspondenceUriMap.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(l);
            if (tag != null && !tag.isEmpty()) {
              correspondenceTagMap
                  .computeIfAbsent(l + "|" + r, k -> new LinkedHashSet<>())
                  .add(tag);
              correspondenceTagMap
                  .computeIfAbsent(r + "|" + l, k -> new LinkedHashSet<>())
                  .add(tag);
            }
          }
        }
      }
    } catch (Exception e) {
      // Reported to the caller (SmartLoader turns it into a FileError): ignoring it would evaluate
      // correspondence constraints against an empty correspondence model without any hint.
      throw new IOException(
          "Malformed or unreadable correspondence file "
              + corrPath.getFileName()
              + ": "
              + e.getMessage(),
          e);
    }
  }

  /** Collects href values from child elements (format: {@code <leftEObjects href="..."/>}). */
  private List<String> collectHrefsFromChildElements(Element parent, String childTag, URI baseUri) {
    List<String> result = new ArrayList<>();
    NodeList children = parent.getElementsByTagName(childTag);
    for (int i = 0; i < children.getLength(); i++) {
      Element child = (Element) children.item(i);
      String href = child.getAttribute("href");
      if (href != null && !href.isEmpty()) {
        URI resolved = URI.createURI(href).resolve(baseUri);
        result.add(resolved.toString());
      }
    }
    return result;
  }

  /**
   * Collects href values from a space-separated attribute on a DOM element.
   *
   * <p>The Vitruvius correspondence XMI format stores {@code leftEObjects} and {@code
   * rightEObjects} as space-separated attribute values (one URI per token), not as child elements.
   */
  private List<String> collectHrefsFromAttr(Element parent, String attrName, URI baseUri) {
    List<String> result = new ArrayList<>();
    String attrValue = parent.getAttribute(attrName);
    if (attrValue == null || attrValue.isEmpty()) {
      return result;
    }
    for (String token : attrValue.trim().split("\\s+")) {
      if (!token.isEmpty()) {
        URI resolved = URI.createURI(token).resolve(baseUri);
        result.add(resolved.toString());
      }
    }
    return result;
  }

  @Override
  public synchronized Set<EObject> getCorrespondingObjects(EObject source) {
    Map<EObject, Set<String>> partners = correspondenceIndex().get(source);
    return partners == null ? Collections.emptySet() : new LinkedHashSet<>(partners.keySet());
  }

  @Override
  public synchronized boolean correspondenceHasTag(EObject obj1, EObject obj2, String tag) {
    if (tag == null) {
      return false; // as before: there is no correspondence with the tag null
    }
    Map<EObject, Set<String>> partners = correspondenceIndex().get(obj1);
    Set<String> tags = partners == null ? null : partners.get(obj2);
    return tags != null && tags.contains(tag);
  }

  @Override
  public synchronized Set<EObject> getCorrespondingObjectsWithTag(EObject source, String tag) {
    Set<EObject> result = new LinkedHashSet<>();
    Map<EObject, Set<String>> partners = correspondenceIndex().get(source);
    if (partners != null && tag != null) {
      partners.forEach(
          (partner, tags) -> {
            if (tags.contains(tag)) {
              result.add(partner);
            }
          });
    }
    return result;
  }

  /** The index answers tagged lookups symmetrically, exactly like {@link #correspondenceHasTag}. */
  @Override
  public boolean hasTaggedCorrespondenceLookup() {
    return true;
  }

  @Override
  public synchronized Optional<Set<EObject>> getAllObjectsInACorrespondence() {
    Set<EObject> participants = Collections.newSetFromMap(new IdentityHashMap<>());
    participants.addAll(correspondenceIndex().keySet());
    return Optional.of(participants);
  }

  @Override
  public synchronized Map<String, Set<EObject>> getCorrespondingObjectsByTag(EObject source) {
    Map<String, Set<EObject>> byTag = new HashMap<>();
    Map<EObject, Set<String>> partners = correspondenceIndex().get(source);
    if (partners != null) {
      partners.forEach(
          (partner, tags) -> {
            for (String tag : tags) {
              byTag.computeIfAbsent(tag, k -> new LinkedHashSet<>()).add(partner);
            }
          });
    }
    return byTag;
  }

  /** Builds {@link #correspondenceIndex} if it is stale; see there. */
  private Map<EObject, Map<EObject, Set<String>>> correspondenceIndex() {
    if (correspondenceIndex != null) {
      return correspondenceIndex;
    }
    trackExtensionChanges(); // containment changes (which change fragment URIs) invalidate it
    Map<String, EObject> resolved = new HashMap<>();
    Map<EObject, Map<EObject, Set<String>>> index = new IdentityHashMap<>();
    correspondenceUriMap.forEach(
        (sourceUri, targetUris) -> {
          // like the URI-based lookup: a source is found under its current URI, a partner is
          // whatever object its stored URI resolves to now
          EObject source = resolveCorrespondent(sourceUri, resolved);
          if (source == null || !sourceUri.equals(String.valueOf(EcoreUtil.getURI(source)))) {
            return;
          }
          for (String targetUri : targetUris) {
            EObject target = resolveCorrespondent(targetUri, resolved);
            if (target != null) {
              Set<String> tags = correspondenceTagMap.get(sourceUri + "|" + targetUri);
              // both directions, so the relation is symmetric even for non-canonical hrefs
              addCorrespondence(index, source, target, tags);
              addCorrespondence(index, target, source, tags);
            }
          }
        });
    correspondenceIndex = index;
    return index;
  }

  private static void addCorrespondence(
      Map<EObject, Map<EObject, Set<String>>> index,
      EObject from,
      EObject to,
      Set<String> tags) {
    Set<String> entry =
        index
            .computeIfAbsent(from, k -> new LinkedHashMap<>())
            .computeIfAbsent(to, k -> new HashSet<>());
    if (tags == null || tags.isEmpty()) {
      entry.add(null); // untagged
    } else {
      entry.addAll(tags);
    }
  }

  /** The loaded object {@code uri} resolves to, or {@code null}. */
  private EObject resolveCorrespondent(String uri, Map<String, EObject> resolved) {
    if (resolved.containsKey(uri)) {
      return resolved.get(uri);
    }
    EObject object;
    try {
      object = resourceSet.getEObject(URI.createURI(uri), false);
    } catch (RuntimeException e) {
      object = null; // unresolvable reference: skipped, as before
    }
    if (object != null && object.eIsProxy()) {
      object = null;
    }
    resolved.put(uri, object);
    return object;
  }

  /** Marks {@link #correspondenceIndex} stale. */
  private synchronized void invalidateCorrespondenceIndex() {
    correspondenceIndex = null;
  }

  private static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder();
  }
}
