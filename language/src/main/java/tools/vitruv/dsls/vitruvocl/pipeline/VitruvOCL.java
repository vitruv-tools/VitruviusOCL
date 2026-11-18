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
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.dsls.vitruvocl.adapter.vsum.VsumWrapper;
import tools.vitruv.dsls.vitruvocl.api.ConstraintFiles;
import tools.vitruv.dsls.vitruvocl.api.VitruvOclEngine;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorSeverity;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalConfig;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalResult;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalSession;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.result.BatchValidationResult;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;
import tools.vitruv.framework.vsum.VirtualModel;

/**
 * Static facade for evaluating VitruvOCL constraints against a registered VSUM or standalone files.
 * Every method delegates to {@link VitruvOclEngine}, the instance API that replaces this facade;
 * the registered VSUM or wrapper is the only state kept here.
 */
public class VitruvOCL {

  private VitruvOCL() {}

  private static VsumWrapper vsumWrapper = null;
  private static ModelAccess directWrapper = null;

  /** Worker threads for constraint lists when the caller does not pass a count. */
  private static final int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  /**
   * Registers the VSUM to evaluate constraints against.
   *
   * @param vsum the virtual model; must not be {@code null}
   */
  public static synchronized void registerVSUM(VirtualModel vsum) {
    if (vsum == null) {
      throw new IllegalArgumentException("VSUM must not be null");
    }
    vsumWrapper = new VsumWrapper(vsum);
  }

  /**
   * Registers a direct {@link ModelAccess} for constraint evaluation.
   *
   * <p>Used by the language server when loading a VSUM directory directly via EMF without going
   * through {@link tools.vitruv.framework.vsum.VirtualModelBuilder}.
   *
   * @param wrapper the wrapper to register; must not be {@code null}
   */
  public static synchronized void registerDirectWrapper(ModelAccess wrapper) {
    if (wrapper == null) {
      throw new IllegalArgumentException("Wrapper must not be null");
    }
    directWrapper = wrapper;
  }

  /** Clears any registered VSUM or direct wrapper. */
  public static synchronized void clearVSUM() {
    vsumWrapper = null;
    directWrapper = null;
  }

  /**
   * Returns whether a VSUM or direct wrapper is currently registered.
   *
   * @return {@code true} if a VSUM or direct wrapper is registered
   */
  public static synchronized boolean hasRegisteredVSUM() {
    return vsumWrapper != null || directWrapper != null;
  }

  // ---------------------------------------------------------------------------
  // Single constraints
  // ---------------------------------------------------------------------------

  /** Evaluates a constraint against the registered VSUM; see {@link VitruvOclEngine#evaluate}. */
  public static ConstraintResult evaluateConstraint(String constraint) {
    return registered().evaluate(constraint);
  }

  /** Evaluates a constraint with a transaction against the registered VSUM. */
  public static ConstraintResult evaluateConstraint(
      String constraint, List<EChange<EObject>> transaction) {
    return registered().evaluate(constraint, transaction);
  }

  /** Evaluates a constraint against {@code wrapper}, independent of any registered VSUM. */
  public static ConstraintResult evaluateConstraint(String constraint, ModelAccess wrapper) {
    return VitruvOclEngine.forModel(wrapper).evaluate(constraint);
  }

  /** Evaluates a constraint against metamodels and instances loaded from files. */
  public static ConstraintResult evaluateConstraint(
      String constraint, Path[] ecoreFiles, Path[] xmiFiles) {
    return VitruvOclEngine.forFiles(ecoreFiles, xmiFiles).evaluate(constraint);
  }

  /** Evaluates a constraint with a transaction against metamodels and instances from files. */
  public static ConstraintResult evaluateConstraint(
      String constraint, Path[] ecoreFiles, Path[] xmiFiles, List<EChange<EObject>> transaction) {
    return VitruvOclEngine.forFiles(ecoreFiles, xmiFiles).evaluate(constraint, transaction);
  }

  // ---------------------------------------------------------------------------
  // Constraint lists and files
  // ---------------------------------------------------------------------------

  /**
   * Evaluates all constraints in the given file against the registered VSUM. If the file cannot be
   * read, the result holds a single error entry instead of an exception being thrown.
   */
  public static BatchValidationResult evaluateConstraints(Path constraintsFile) {
    return evaluateConstraints(constraintsFile, DEFAULT_THREAD_POOL_SIZE);
  }

  /** Same as {@link #evaluateConstraints(Path)} with {@code threadPoolSize} worker threads. */
  public static BatchValidationResult evaluateConstraints(
      Path constraintsFile, int threadPoolSize) {
    return evaluateRegistered(constraintsFile, threadPoolSize, null);
  }

  /**
   * Same as {@link #evaluateConstraints(Path)} with a transaction shared by every constraint;
   * {@code pre}/{@code post} blocks are evaluated against it.
   */
  public static BatchValidationResult evaluateConstraints(
      Path constraintsFile, List<EChange<EObject>> transaction) {
    return evaluateRegistered(constraintsFile, DEFAULT_THREAD_POOL_SIZE, transaction);
  }

  /** Evaluates constraints against metamodels and instances loaded from files. */
  public static BatchValidationResult evaluateConstraints(
      List<String> constraints, Path[] ecoreFiles, Path[] xmiFiles) {
    return evaluateConstraints(constraints, ecoreFiles, xmiFiles, DEFAULT_THREAD_POOL_SIZE);
  }

  /** Same as {@link #evaluateConstraints(List, Path[], Path[])} with a thread count. */
  public static BatchValidationResult evaluateConstraints(
      List<String> constraints, Path[] ecoreFiles, Path[] xmiFiles, int threadPoolSize) {
    return VitruvOclEngine.forFiles(ecoreFiles, xmiFiles)
        .withThreadPoolSize(threadPoolSize)
        .evaluateAll(constraints);
  }

  /**
   * Evaluates the constraints of a file against metamodels and instances loaded from files.
   *
   * @throws IOException if the constraints file cannot be read
   */
  public static BatchValidationResult evaluateConstraints(
      Path constraintsFile, Path[] ecoreFiles, Path[] xmiFiles) throws IOException {
    return evaluateConstraints(constraintsFile, ecoreFiles, xmiFiles, DEFAULT_THREAD_POOL_SIZE);
  }

  /**
   * Same as {@link #evaluateConstraints(Path, Path[], Path[])} with a thread count.
   *
   * @throws IOException if the constraints file cannot be read
   */
  public static BatchValidationResult evaluateConstraints(
      Path constraintsFile, Path[] ecoreFiles, Path[] xmiFiles, int threadPoolSize)
      throws IOException {
    return VitruvOclEngine.forFiles(ecoreFiles, xmiFiles)
        .withThreadPoolSize(threadPoolSize)
        .evaluateFile(constraintsFile);
  }

  /**
   * Evaluates a project's constraints against its metamodel and instance directories ({@code
   * model/src/main/{constraints.ocl,ecore,instances}}).
   *
   * @throws IOException if the project or its constraints file cannot be read
   */
  public static BatchValidationResult evaluateProject(Path projectDir) throws IOException {
    return evaluateProject(projectDir, DEFAULT_THREAD_POOL_SIZE);
  }

  /**
   * Same as {@link #evaluateProject(Path)} with a thread count.
   *
   * @throws IOException if the project or its constraints file cannot be read
   */
  public static BatchValidationResult evaluateProject(Path projectDir, int threadPoolSize)
      throws IOException {
    return evaluateProject(
        ConstraintFiles.projectConstraints(projectDir), projectDir, threadPoolSize);
  }

  /**
   * Evaluates the constraints of an explicit file against a project's metamodel and instance
   * directories.
   *
   * @throws IOException if the project or the constraints file cannot be read
   */
  public static BatchValidationResult evaluateProject(Path constraintsFile, Path resourcesDir)
      throws IOException {
    return evaluateProject(constraintsFile, resourcesDir, DEFAULT_THREAD_POOL_SIZE);
  }

  /**
   * Same as {@link #evaluateProject(Path, Path)} with a thread count.
   *
   * @throws IOException if the project or the constraints file cannot be read
   */
  public static BatchValidationResult evaluateProject(
      Path constraintsFile, Path resourcesDir, int threadPoolSize) throws IOException {
    return VitruvOclEngine.forProject(resourcesDir)
        .withThreadPoolSize(threadPoolSize)
        .evaluateFile(constraintsFile);
  }

  // ---------------------------------------------------------------------------
  // Incremental evaluation
  // ---------------------------------------------------------------------------

  /** Opens an incremental session against the registered VSUM (or direct wrapper). */
  public static IncrementalSession openIncrementalSession(
      List<String> constraints, IncrementalConfig config) {
    return registered().openIncrementalSession(constraints, config);
  }

  /**
   * Same as {@link #openIncrementalSession(List, IncrementalConfig)}, reading the constraints from
   * a {@code .ocl} file.
   *
   * @throws IOException if the constraints file cannot be read
   */
  public static IncrementalSession openIncrementalSession(
      Path constraintsFile, IncrementalConfig config) throws IOException {
    return openIncrementalSession(ConstraintFiles.read(constraintsFile), config);
  }

  /**
   * Opens an incremental session over metamodels and instances loaded from files.
   *
   * @throws IOException if the constraints file cannot be read
   * @throws IllegalStateException if the metamodel or instance files cannot be loaded
   */
  public static IncrementalSession openIncrementalSession(
      Path constraintsFile, Path[] ecoreFiles, Path[] xmiFiles, IncrementalConfig config)
      throws IOException {
    List<String> constraints = ConstraintFiles.read(constraintsFile);
    return VitruvOclEngine.forFiles(ecoreFiles, xmiFiles)
        .openIncrementalSession(constraints, config);
  }

  /** Processes one transaction (already applied to the model) in {@code session}. */
  public static IncrementalResult evaluateConstraintsIncremental(
      IncrementalSession session, List<EChange<EObject>> transaction) {
    return session.onTransaction(transaction);
  }

  /**
   * Opens an incremental session over the VSUM registered with {@link #registerVSUM} that is
   * notified of every commit.
   *
   * @throws IllegalStateException if no VSUM (as opposed to a direct wrapper) is registered
   */
  public static IncrementalSession attachIncrementalSession(
      List<String> constraints,
      IncrementalConfig config,
      Consumer<IncrementalResult> onResult,
      Consumer<RuntimeException> onFailure) {
    VsumWrapper wrapper;
    synchronized (VitruvOCL.class) {
      wrapper = vsumWrapper;
    }
    if (wrapper == null) {
      throw new IllegalStateException(
          "attachIncrementalSession requires a VSUM registered via VitruvOCL.registerVSUM(vsum)");
    }
    return VitruvOclEngine.forModel(wrapper)
        .attachIncrementalSession(constraints, config, onResult, onFailure);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Reads the constraints file first, so that an unreadable file yields an error result even
   * without a registered VSUM, then evaluates against the registered VSUM.
   *
   * @param transaction the shared transaction, or {@code null} to skip {@code pre}/{@code post}
   */
  private static BatchValidationResult evaluateRegistered(
      Path constraintsFile, int threadPoolSize, List<EChange<EObject>> transaction) {
    List<String> constraints;
    try {
      constraints = ConstraintFiles.read(constraintsFile);
    } catch (IOException e) {
      return unreadableFile(constraintsFile, e);
    }
    VitruvOclEngine engine = registered().withThreadPoolSize(threadPoolSize);
    return transaction == null
        ? engine.evaluateAll(constraints)
        : engine.evaluateAll(constraints, transaction);
  }

  private static BatchValidationResult unreadableFile(Path constraintsFile, IOException e) {
    CompileError error =
        new CompileError(
            1,
            0,
            "Could not read constraints file: "
                + constraintsFile.getFileName()
                + " ("
                + e.getMessage()
                + ")",
            ErrorSeverity.ERROR,
            constraintsFile.toString());
    return new BatchValidationResult(
        List.of(
            new ConstraintResult(
                constraintsFile.toString(), false, List.of(error), List.of(), List.of())));
  }

  /** An engine over the registered direct wrapper, else the registered VSUM. */
  private static synchronized VitruvOclEngine registered() {
    if (directWrapper != null) {
      return VitruvOclEngine.forModel(directWrapper);
    }
    if (vsumWrapper != null) {
      return VitruvOclEngine.forModel(vsumWrapper);
    }
    throw new IllegalStateException(
        "No VSUM registered. Call VitruvOCL.registerVSUM(vsum) or "
            + "VitruvOCL.registerDirectWrapper(wrapper) before evaluating constraints "
            + "without explicit file paths.");
  }
}
