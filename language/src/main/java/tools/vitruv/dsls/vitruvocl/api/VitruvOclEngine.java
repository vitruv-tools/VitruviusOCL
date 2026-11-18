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

package tools.vitruv.dsls.vitruvocl.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.dsls.vitruvocl.adapter.emf.SmartLoader;
import tools.vitruv.dsls.vitruvocl.adapter.vsum.VsumWrapper;
import tools.vitruv.dsls.vitruvocl.compiler.ConstraintEvaluations;
import tools.vitruv.dsls.vitruvocl.compiler.ConstraintListEvaluator;
import tools.vitruv.dsls.vitruvocl.compiler.PreparedConstraint;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalConfig;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalResult;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalSession;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.model.TransactionSource;
import tools.vitruv.dsls.vitruvocl.result.BatchValidationResult;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;
import tools.vitruv.dsls.vitruvocl.result.FileError;
import tools.vitruv.dsls.vitruvocl.result.Warning;
import tools.vitruv.framework.vsum.VirtualModel;

/**
 * Evaluates VitruvOCL constraints against one model source: a {@link ModelAccess}, a Vitruvius
 * virtual model, or metamodel and instance files.
 *
 * <p>An engine holds no mutable state; configuration methods such as {@link #withThreadPoolSize}
 * return a new engine. Concurrent calls are as safe as the model they evaluate against: an engine
 * over a given {@link ModelAccess} shares it between calls. File-based engines load, for every
 * call, those of their metamodel and instance files that the evaluated constraints need; the set of
 * files is fixed when the engine is created. The {@code evaluate*} methods report load errors in
 * their results rather than throwing them.
 *
 * <p>Evaluating without a transaction skips {@code pre}/{@code post} blocks (and reports that as a
 * warning); passing a transaction, even an empty one, evaluates them against it.
 */
public final class VitruvOclEngine {

  private static final int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

  private final ModelSource source;
  private final int threadPoolSize;

  private VitruvOclEngine(ModelSource source, int threadPoolSize) {
    this.source = source;
    this.threadPoolSize = threadPoolSize;
  }

  // ------------------------------------------------------------------ creation

  /** An engine that evaluates against {@code model}. */
  public static VitruvOclEngine forModel(ModelAccess model) {
    return new VitruvOclEngine(
        new GivenModel(Objects.requireNonNull(model, "model")), DEFAULT_THREAD_POOL_SIZE);
  }

  /** An engine that evaluates against the current state of {@code vsum}. */
  public static VitruvOclEngine forVsum(VirtualModel vsum) {
    return forModel(new VsumWrapper(Objects.requireNonNull(vsum, "vsum")));
  }

  /** An engine that evaluates against metamodels and instances loaded from files. */
  public static VitruvOclEngine forFiles(Path[] ecoreFiles, Path[] xmiFiles) {
    return new VitruvOclEngine(
        new ModelFiles(
            Objects.requireNonNull(ecoreFiles, "ecoreFiles").clone(),
            Objects.requireNonNull(xmiFiles, "xmiFiles").clone()),
        DEFAULT_THREAD_POOL_SIZE);
  }

  /**
   * An engine over the metamodels and instances of a project with the conventional layout ({@code
   * model/src/main/ecore} and {@code model/src/main/instances}), as listed now; see {@link
   * ConstraintFiles#projectConstraints} for its constraints file.
   *
   * @throws IOException if the project directories cannot be listed
   */
  public static VitruvOclEngine forProject(Path projectDir) throws IOException {
    Objects.requireNonNull(projectDir, "projectDir");
    return forFiles(
        ConstraintFiles.projectMetamodels(projectDir),
        ConstraintFiles.projectInstances(projectDir));
  }

  /**
   * This engine, evaluating constraint lists with {@code threadPoolSize} worker threads (default:
   * the number of available processors).
   *
   * @throws IllegalArgumentException if {@code threadPoolSize} is less than 1
   */
  public VitruvOclEngine withThreadPoolSize(int threadPoolSize) {
    if (threadPoolSize < 1) {
      throw new IllegalArgumentException("threadPoolSize must be at least 1: " + threadPoolSize);
    }
    return new VitruvOclEngine(source, threadPoolSize);
  }

  // ------------------------------------------------------------------ single constraint

  /** Evaluates one constraint; {@code pre}/{@code post} blocks are skipped. */
  public ConstraintResult evaluate(String constraint) {
    return evaluate(constraint, List.of(), false);
  }

  /**
   * Evaluates one constraint with a transaction, against which {@code @pre}, {@code OCLisNew},
   * {@code OCLisModified}, {@code OCLisDeleted} and {@code pre}/{@code post} blocks are evaluated.
   *
   * @param transaction the atomic changes between the pre-state and the current state (may be
   *     empty)
   */
  public ConstraintResult evaluate(String constraint, List<EChange<EObject>> transaction) {
    return evaluate(constraint, Objects.requireNonNull(transaction, "transaction"), true);
  }

  private ConstraintResult evaluate(
      String constraint, List<EChange<EObject>> transaction, boolean transactionSupported) {
    Loaded loaded = source.load(List.of(constraint));
    if (loaded.failed()) {
      return loaded.failure(constraint);
    }
    return ConstraintEvaluations.render(
        constraint,
        loaded.model(),
        loaded.warnings(),
        ConstraintEvaluations.evaluateFresh(
            constraint, loaded.model(), transaction, transactionSupported));
  }

  // ------------------------------------------------------------------ constraint lists

  /**
   * Evaluates several constraints, in parallel; results are in input order. A constraint that
   * occurs again is reported as a duplicate instead of being evaluated twice.
   */
  public BatchValidationResult evaluateAll(List<String> constraints) {
    return evaluateAll(constraints, List.of(), false);
  }

  /** Same as {@link #evaluateAll(List)}, with a transaction shared by all constraints. */
  public BatchValidationResult evaluateAll(
      List<String> constraints, List<EChange<EObject>> transaction) {
    return evaluateAll(constraints, Objects.requireNonNull(transaction, "transaction"), true);
  }

  /**
   * Evaluates the constraints of an {@code .ocl} file (see {@link ConstraintFiles#read}).
   *
   * @throws IOException if the file cannot be read
   */
  public BatchValidationResult evaluateFile(Path oclFile) throws IOException {
    return evaluateAll(ConstraintFiles.read(oclFile));
  }

  /**
   * Same as {@link #evaluateFile(Path)}, with a transaction shared by all constraints.
   *
   * @throws IOException if the file cannot be read
   */
  public BatchValidationResult evaluateFile(Path oclFile, List<EChange<EObject>> transaction)
      throws IOException {
    Objects.requireNonNull(transaction, "transaction");
    return evaluateAll(ConstraintFiles.read(oclFile), transaction);
  }

  private BatchValidationResult evaluateAll(
      List<String> constraints, List<EChange<EObject>> transaction, boolean transactionSupported) {
    if (constraints.isEmpty()) {
      return new BatchValidationResult(List.of());
    }
    Loaded loaded = source.load(constraints);
    if (loaded.failed()) {
      return new BatchValidationResult(constraints.stream().map(loaded::failure).toList());
    }

    int size = constraints.size();
    List<ConstraintResult> results = new ArrayList<>(Collections.nCopies(size, null));
    Set<String> seen = new HashSet<>();
    List<String> distinct = new ArrayList<>();
    List<Integer> distinctIndices = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      String constraint = constraints.get(i);
      if (seen.add(constraint)) {
        distinct.add(constraint);
        distinctIndices.add(i);
      } else {
        results.set(i, ConstraintEvaluations.duplicateResult(constraint));
      }
    }

    List<ConstraintResult> evaluated =
        loaded
            .model()
            .batch(
                view -> {
                  // each constraint is compiled once: its types are prefetched, then evaluated
                  List<PreparedConstraint> prepared =
                      ConstraintListEvaluator.map(
                          distinct, c -> PreparedConstraint.prepare(c, view), threadPoolSize);
                  PreparedConstraint.prefetchInstances(prepared, view);
                  return ConstraintListEvaluator.map(
                      prepared,
                      p ->
                          ConstraintEvaluations.render(
                              p.text(),
                              view,
                              List.of(),
                              p.evaluate(view, transaction, transactionSupported, null)),
                      threadPoolSize);
                });
    for (int i = 0; i < evaluated.size(); i++) {
      results.set(distinctIndices.get(i), evaluated.get(i));
    }
    return new BatchValidationResult(results);
  }

  // ------------------------------------------------------------------ incremental evaluation

  /**
   * Opens and initialises an incremental session for {@code constraints}; its initial full
   * evaluation is {@link IncrementalSession#latestResult()}. Pass each later transaction to {@link
   * IncrementalSession#onTransaction}.
   *
   * @return the initialised session; close it when done
   * @throws IllegalStateException if the model files cannot be loaded
   */
  public IncrementalSession openIncrementalSession(
      List<String> constraints, IncrementalConfig config) {
    return IncrementalSession.open(constraints, requireModel(constraints), config);
  }

  /**
   * Opens an incremental session that is notified of every committed transaction of the model
   * source and hands each result to {@code onResult}; closing the session stops this.
   *
   * @param onFailure receives exceptions (e.g. oracle mismatches) instead of the propagation
   * @throws IllegalStateException if the model source cannot report transactions (a virtual model
   *     given with {@link #forVsum} can)
   */
  public IncrementalSession attachIncrementalSession(
      List<String> constraints,
      IncrementalConfig config,
      Consumer<IncrementalResult> onResult,
      Consumer<RuntimeException> onFailure) {
    if (!(source instanceof GivenModel given && given.model() instanceof TransactionSource)) {
      throw new IllegalStateException(
          "attachIncrementalSession requires a model that reports its transactions, such as a"
              + " virtual model (VitruvOclEngine.forVsum)");
    }
    return IncrementalSession.openAttached(
        constraints, (ModelAccess & TransactionSource) given.model(), config, onResult, onFailure);
  }

  private ModelAccess requireModel(List<String> constraints) {
    Loaded loaded = source.load(constraints);
    if (loaded.failed()) {
      throw new IllegalStateException("Could not load model files: " + loaded.fileErrors());
    }
    return loaded.model();
  }

  // ------------------------------------------------------------------ model sources

  /** Where the model comes from. */
  private sealed interface ModelSource permits GivenModel, ModelFiles {
    /** The model to evaluate {@code constraints} against. */
    Loaded load(List<String> constraints);
  }

  /** A model access given by the caller. */
  private record GivenModel(ModelAccess model) implements ModelSource {
    @Override
    public Loaded load(List<String> constraints) {
      return new Loaded(model, false, List.of(), List.of());
    }
  }

  /** Metamodel and instance files, loaded per call as far as the constraints need them. */
  private record ModelFiles(Path[] ecoreFiles, Path[] xmiFiles) implements ModelSource {
    @Override
    public Loaded load(List<String> constraints) {
      SmartLoader.LoadResult result =
          SmartLoader.loadForConstraints(constraints, ecoreFiles, xmiFiles);
      return new Loaded(result.wrapper, result.hasErrors(), result.fileErrors, result.warnings);
    }
  }

  /** A loaded model, or the errors that prevented loading it. */
  private record Loaded(
      ModelAccess model, boolean failed, List<FileError> fileErrors, List<Warning> warnings) {

    /** The result of a constraint that could not be evaluated because loading failed. */
    ConstraintResult failure(String constraint) {
      return new ConstraintResult(constraint, false, List.of(), fileErrors, warnings);
    }
  }
}
