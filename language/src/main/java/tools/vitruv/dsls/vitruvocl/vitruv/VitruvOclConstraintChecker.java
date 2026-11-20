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

package tools.vitruv.dsls.vitruvocl.vitruv;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.emf.ecore.EPackage;
import tools.vitruv.dsls.vitruvocl.adapter.vsum.VsumWrapper;
import tools.vitruv.dsls.vitruvocl.analysis.OclAnalysis;
import tools.vitruv.dsls.vitruvocl.analysis.OclAnalyzer;
import tools.vitruv.dsls.vitruvocl.api.VitruvOclEngine;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.compiler.ConstraintEvaluations;
import tools.vitruv.dsls.vitruvocl.compiler.DependencyAnalyzer;
import tools.vitruv.dsls.vitruvocl.compiler.PreparedConstraint;
import tools.vitruv.dsls.vitruvocl.result.FileError;
import tools.vitruv.dsls.vitruvocl.result.Violation;
import tools.vitruv.dsls.vitruvocl.source.OclSourceSplitter;
import tools.vitruv.dsls.vitruvocl.vitruv.OclInvariantParser.ParsedInvariant;
import tools.vitruv.framework.vsum.constraints.ConstraintCheckContext;
import tools.vitruv.framework.vsum.constraints.ConstraintChecker;
import tools.vitruv.framework.vsum.constraints.ConstraintDescriptor;
import tools.vitruv.framework.vsum.constraints.ConstraintDiagnostic;
import tools.vitruv.framework.vsum.constraints.ConstraintKind;
import tools.vitruv.framework.vsum.constraints.ConstraintResult;
import tools.vitruv.framework.vsum.constraints.ConstraintViolation;
import tools.vitruv.framework.vsum.constraints.DuplicateConstraintException;
import tools.vitruv.framework.vsum.constraints.EditableConstraintChecker;
import tools.vitruv.framework.vsum.constraints.InvalidConstraintException;
import tools.vitruv.framework.vsum.constraints.UnknownConstraintException;

/**
 * A Vitruv {@link ConstraintChecker} for VitruvOCL constraints. Register it at a virtual model via
 * {@link tools.vitruv.framework.vsum.VirtualModelBuilder#withConstraintChecker(ConstraintChecker)}.
 *
 * <p>Each invariant is a constraint. Its id is the context type and the invariant name, e.g. {@code
 * shop::Item::nonNegativePrice}, so invariant names only need to be unique per context, as in OCL.
 * Unnamed invariants are numbered per context in declaration order ({@code shop::Item#1}); name
 * invariants to get ids that do not change when constraints are added.
 *
 * <p>Constraints can be created, updated and deleted while the virtual model is running, see {@link
 * EditableConstraintChecker}. Such constraints must be named, so their ids are stable.
 *
 * <p>The checker does not register a VSUM globally. Metamodels are still resolved by the name of
 * their package, both from the global {@link org.eclipse.emf.ecore.EPackage.Registry} and from the
 * models of the checked virtual model.
 */
public final class VitruvOclConstraintChecker implements EditableConstraintChecker {

  /** The language identifier of VitruvOCL constraints. */
  public static final String LANGUAGE = "vitruvocl";

  /**
   * The constraints by id. The map is never changed but replaced, so a running check always sees a
   * consistent set of constraints.
   */
  private volatile Map<String, ConstraintDescriptor> constraints;

  /**
   * Serializes the read-check-replace of {@link #constraints} in create/update/delete, so that
   * concurrent edits are neither lost nor duplicated. Parsing and type checking stay outside.
   */
  private final Object editLock = new Object();

  /** Guards {@link #prepared} and {@link #preparedAgainst}. */
  private final Object preparedLock = new Object();

  /** Compiled constraints by source; see {@link #preparedFor}. */
  private final Map<String, PreparedConstraint> prepared = new HashMap<>();

  /** The metamodels (as the wrapper resolves names) {@link #prepared} was compiled against. */
  private Map<String, EPackage> preparedAgainst = Map.of();

  private VitruvOclConstraintChecker(List<ParsedInvariant> invariants) {
    Map<String, ConstraintDescriptor> initial = new LinkedHashMap<>();
    Map<String, Integer> unnamedPerContext = new LinkedHashMap<>();
    for (ParsedInvariant invariant : invariants) {
      String id;
      String name;
      if (invariant.name() == null) {
        int index = unnamedPerContext.merge(invariant.contextType(), 1, Integer::sum);
        id = invariant.contextType() + "#" + index;
        name = id;
      } else {
        id = invariant.contextType() + "::" + invariant.name();
        name = invariant.name();
      }
      if (initial.containsKey(id)) {
        throw new IllegalArgumentException(
            "Duplicate OCL invariant " + name + " in context " + invariant.contextType());
      }
      initial.put(id, toDescriptor(id, name, invariant));
    }
    this.constraints = Collections.unmodifiableMap(initial);
  }

  private static ConstraintDescriptor toDescriptor(
      String id, String name, ParsedInvariant invariant) {
    return new ConstraintDescriptor(
        id,
        name,
        invariant.contextType(),
        ConstraintKind.INVARIANT,
        invariant.severity(),
        LANGUAGE,
        invariant.source(),
        metamodelsOf(invariant.source()));
  }

  /**
   * Determines the metamodels an invariant reads from the qualified type names in its source, e.g.
   * {@code shop} and {@code billing} for a navigation from {@code shop::Item} to {@code
   * billing::Invoice}. Enum literals compared with {@code ==} or {@code !=} are not counted; other
   * qualified enum literals may be reported as metamodels. The descriptor puts the metamodel of the
   * context first, the others follow sorted by name.
   */
  private static Set<String> metamodelsOf(String source) {
    return new TreeSet<>(DependencyAnalyzer.analyzeConstraint(source));
  }

  /**
   * Creates a checker for the given constraints.
   *
   * @param content the content of a {@code .ocl} file
   * @return the checker
   * @throws IllegalArgumentException if the content contains syntax errors or duplicate names
   */
  public static VitruvOclConstraintChecker fromString(String content) {
    return new VitruvOclConstraintChecker(OclInvariantParser.parse(content));
  }

  /**
   * Creates a checker for the constraints of the given files.
   *
   * @param files the {@code .ocl} files
   * @return the checker
   * @throws UncheckedIOException if a file cannot be read
   * @throws IllegalArgumentException if a file contains syntax errors or names are duplicated
   */
  public static VitruvOclConstraintChecker fromFiles(Path... files) {
    List<ParsedInvariant> invariants = new ArrayList<>();
    for (Path file : files) {
      try {
        invariants.addAll(OclInvariantParser.parse(Files.readString(file)));
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read OCL constraints from " + file, e);
      }
    }
    return new VitruvOclConstraintChecker(invariants);
  }

  /**
   * Creates a checker for the constraints of the given classpath resources, loaded with the
   * context class loader of the current thread.
   *
   * @param resourceNames the names of the {@code .ocl} resources, e.g. {@code constraints.ocl}
   * @return the checker
   * @throws IllegalArgumentException if a resource does not exist, contains syntax errors or names
   *     are duplicated
   */
  public static VitruvOclConstraintChecker fromClasspath(String... resourceNames) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) {
      classLoader = VitruvOclConstraintChecker.class.getClassLoader();
    }
    List<ParsedInvariant> invariants = new ArrayList<>();
    for (String resourceName : resourceNames) {
      try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
        if (stream == null) {
          throw new IllegalArgumentException(
              "OCL constraints not found on classpath: " + resourceName);
        }
        invariants.addAll(
            OclInvariantParser.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read OCL constraints " + resourceName, e);
      }
    }
    return new VitruvOclConstraintChecker(invariants);
  }

  @Override
  public List<ConstraintDescriptor> getConstraints() {
    return List.copyOf(constraints.values());
  }

  @Override
  public String getLanguage() {
    return LANGUAGE;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The source must contain exactly one named invariant, e.g. {@code context shop::Item inv
   * positive: self.price > 0}. It is type-checked against the metamodels registered in the global
   * {@link org.eclipse.emf.ecore.EPackage.Registry}, which contains the metamodels of a running
   * virtual model.
   */
  @Override
  public ConstraintDescriptor create(String source) {
    ConstraintDescriptor created = parseSingle(source);
    if (constraints.containsKey(created.id())) {
      throw new DuplicateConstraintException(created.id());
    }
    typeCheck(source);
    synchronized (editLock) {
      if (constraints.containsKey(created.id())) {
        throw new DuplicateConstraintException(created.id()); // created concurrently
      }
      Map<String, ConstraintDescriptor> changed = new LinkedHashMap<>(constraints);
      changed.put(created.id(), created);
      constraints = Collections.unmodifiableMap(changed);
    }
    return created;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The source must contain exactly one named invariant with the same context type and name.
   *
   * @throws java.util.ConcurrentModificationException if the constraint was replaced (deleted and
   *     created again) while the update was being checked
   */
  @Override
  public ConstraintDescriptor update(String id, String source) {
    ConstraintDescriptor before = constraints.get(id);
    if (before == null) {
      throw new UnknownConstraintException(id);
    }
    ConstraintDescriptor updated = parseSingle(source);
    if (!updated.id().equals(id)) {
      throw new InvalidConstraintException(
          List.of(
              new ConstraintDiagnostic(
                  1,
                  0,
                  "The source defines the constraint "
                      + updated.id()
                      + ", but "
                      + id
                      + " is updated; context type and invariant name cannot be changed")));
    }
    typeCheck(source);
    replaceIfUnchanged(id, before, updated);
    return updated;
  }

  /**
   * Replaces the constraint {@code id} by {@code updated}, provided it is still {@code expected},
   * the version the update started from. Parsing and type checking run without the lock, so the
   * constraint may have been deleted, or deleted and created again, meanwhile; overwriting that
   * newer version with the update's would silently lose it.
   *
   * @throws UnknownConstraintException if the constraint was deleted meanwhile
   * @throws ConcurrentModificationException if it was replaced meanwhile
   */
  void replaceIfUnchanged(
      String id, ConstraintDescriptor expected, ConstraintDescriptor updated) {
    synchronized (editLock) {
      ConstraintDescriptor current = constraints.get(id);
      if (current == null) {
        throw new UnknownConstraintException(id); // deleted concurrently
      }
      if (current != expected) {
        throw new ConcurrentModificationException(
            "The constraint " + id + " was changed concurrently; the update was not applied");
      }
      Map<String, ConstraintDescriptor> changed = new LinkedHashMap<>(constraints);
      changed.put(id, updated);
      constraints = Collections.unmodifiableMap(changed);
    }
  }

  @Override
  public void delete(String id) {
    synchronized (editLock) {
      if (!constraints.containsKey(id)) {
        throw new UnknownConstraintException(id);
      }
      Map<String, ConstraintDescriptor> changed = new LinkedHashMap<>(constraints);
      changed.remove(id);
      constraints = Collections.unmodifiableMap(changed);
    }
  }

  /** Parses a source that must contain exactly one named invariant. */
  private static ConstraintDescriptor parseSingle(String source) {
    List<ParsedInvariant> invariants = OclInvariantParser.parse(source == null ? "" : source);
    if (invariants.size() != 1) {
      throw new InvalidConstraintException(
          List.of(
              new ConstraintDiagnostic(
                  1, 0, "Expected exactly one invariant, found " + invariants.size())));
    }
    ParsedInvariant invariant = invariants.get(0);
    if (invariant.name() == null) {
      throw new InvalidConstraintException(
          List.of(
              new ConstraintDiagnostic(
                  1, 0, "Name the invariant, e.g. inv positive: ..., to give it a stable id")));
    }
    return toDescriptor(
        invariant.contextType() + "::" + invariant.name(), invariant.name(), invariant);
  }

  /**
   * Type-checks the constraint against the registered metamodels, reporting unknown types, features
   * and other compile errors with their position in the given source; nothing is evaluated.
   */
  private static void typeCheck(String source) {
    OclAnalysis analysis =
        OclAnalyzer.analyze(
            OclSourceSplitter.withoutImports(source),
            new VsumWrapper(EmptyCheckContext.INSTANCE),
            OclAnalyzer.Mode.STRICT);
    if (analysis.compiled()) {
      return; // warnings alone do not reject a constraint
    }
    List<ConstraintDiagnostic> diagnostics = new ArrayList<>();
    for (CompileError error : analysis.allErrors()) {
      diagnostics.add(
          new ConstraintDiagnostic(error.getLine(), error.getColumn(), error.getMessage()));
    }
    if (diagnostics.isEmpty()) {
      // the source was split from a syntactically valid document, so this is not expected
      diagnostics.add(new ConstraintDiagnostic(1, 0, "Syntax error in constraint"));
    }
    throw new InvalidConstraintException(diagnostics);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Unknown ids are ignored. A failure while evaluating one constraint is reported as an error
   * of that constraint only.
   */
  @Override
  public List<ConstraintResult> check(
      ConstraintCheckContext context, Collection<String> constraintIds) {
    // a fresh wrapper per check sees all metamodels registered up to now
    VsumWrapper wrapper = new VsumWrapper(context);
    Map<String, ConstraintDescriptor> current = constraints;
    List<ConstraintDescriptor> checked = new ArrayList<>();
    for (String id : constraintIds) {
      ConstraintDescriptor descriptor = current.get(id);
      if (descriptor != null) {
        checked.add(descriptor);
      }
    }
    Map<String, PreparedConstraint> compiled = preparedFor(checked, context, wrapper);
    // one traversal for the instances of all checked constraints instead of one per new type
    PreparedConstraint.prefetchInstances(
        compiled.values().stream().filter(Objects::nonNull).toList(), wrapper);
    List<ConstraintResult> results = new ArrayList<>();
    for (ConstraintDescriptor descriptor : checked) {
      results.add(evaluate(descriptor, compiled.get(descriptor.source()), wrapper));
    }
    return results;
  }

  /**
   * The compiled form of every checked constraint, compiled once and reused across checks (a
   * check would otherwise lex, parse and type-check every constraint again). The cache is dropped
   * when the metamodels change, i.e. when the wrapper resolves some metamodel name to a different
   * package than when the constraints were compiled; entries of constraints that no longer exist
   * are dropped too. Not observed: a package changed in place (classes or features edited).
   */
  private Map<String, PreparedConstraint> preparedFor(
      List<ConstraintDescriptor> checked, ConstraintCheckContext context, VsumWrapper wrapper) {
    Map<String, EPackage> metamodels = wrapper.metamodelsByName();
    Map<String, PreparedConstraint> result = new LinkedHashMap<>();
    synchronized (preparedLock) {
      if (!metamodels.equals(preparedAgainst)) {
        prepared.clear();
        preparedAgainst = metamodels;
      }
      Set<String> sources = new HashSet<>();
      constraints.values().forEach(d -> sources.add(d.source()));
      prepared.keySet().retainAll(sources);
      for (ConstraintDescriptor descriptor : checked) {
        String source = descriptor.source();
        result.put(source, prepared.computeIfAbsent(source, s -> prepare(s, wrapper)));
      }
    }
    return result;
  }

  private static PreparedConstraint prepare(String source, VsumWrapper wrapper) {
    try {
      return PreparedConstraint.prepare(source, wrapper);
    } catch (RuntimeException | StackOverflowError e) {
      return null; // evaluated from scratch, which reports the failure for this constraint only
    }
  }

  private static ConstraintResult evaluate(
      ConstraintDescriptor descriptor, PreparedConstraint compiled, VsumWrapper wrapper) {
    try {
      tools.vitruv.dsls.vitruvocl.result.ConstraintResult oclResult =
          compiled == null
              ? VitruvOclEngine.forModel(wrapper).evaluate(descriptor.source())
              : ConstraintEvaluations.render(
                  descriptor.source(),
                  wrapper,
                  List.of(),
                  compiled.evaluate(wrapper, List.of(), false, null));
      return toVitruvResult(descriptor, oclResult);
    } catch (RuntimeException | StackOverflowError e) {
      String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      return ConstraintResult.error(descriptor, List.of("Evaluation failed: " + reason));
    }
  }

  private static ConstraintResult toVitruvResult(
      ConstraintDescriptor descriptor,
      tools.vitruv.dsls.vitruvocl.result.ConstraintResult oclResult) {
    if (!oclResult.isSuccess()) {
      List<String> errors = new ArrayList<>();
      for (CompileError error : oclResult.getCompilerErrors()) {
        errors.add(error.getLine() + ":" + error.getColumn() + " " + error.getMessage());
      }
      for (FileError error : oclResult.getFileErrors()) {
        errors.add(error.toString());
      }
      return ConstraintResult.error(descriptor, errors);
    }
    List<ConstraintViolation> violations = new ArrayList<>();
    for (Violation violation : oclResult.getViolations()) {
      violations.add(
          new ConstraintViolation(
              violation.instance(),
              OclInvariantParser.toSeverity(violation.severity()),
              violation.message()));
    }
    if (!oclResult.isSatisfied() && violations.isEmpty()) {
      return ConstraintResult.violated(descriptor, List.of());
    }
    return ConstraintResult.of(descriptor, violations);
  }
}
