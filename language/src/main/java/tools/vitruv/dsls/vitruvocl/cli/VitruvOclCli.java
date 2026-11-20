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

package tools.vitruv.dsls.vitruvocl.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import tools.vitruv.dsls.vitruvocl.api.ConstraintFiles;
import tools.vitruv.dsls.vitruvocl.api.VitruvOclEngine;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalConfig;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalLevel;
import tools.vitruv.dsls.vitruvocl.incremental.IncrementalSession;
import tools.vitruv.dsls.vitruvocl.result.BatchValidationResult;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;

/**
 * Command-line interface for VitruvOCL compiler. Outputs JSON for easy integration with IDEs and
 * tools; the documents are described by {@link CliJson}. Every command except {@code version}
 * prints exactly one JSON document on standard output; a failure, including invalid arguments, is
 * {@code {"success":false,"error":...}} with exit status 1.
 */
@SuppressWarnings("java:S106")
public class VitruvOclCli {

  /** Standard output: the JSON result or failure, and the version. */
  private final PrintStream out;

  /** Standard error: the usage text after invalid arguments. */
  private final PrintStream err;

  private VitruvOclCli(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  /**
   * CLI entry point; exits with status 1 if the command failed.
   *
   * @param args command-line arguments; first argument selects the subcommand
   */
  public static void main(String[] args) {
    int status = run(args, System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }

  /**
   * Runs a command.
   *
   * @param args command-line arguments; first argument selects the subcommand
   * @param out where the JSON result or failure and the version are printed
   * @param err where the usage text is printed after invalid arguments
   * @return the exit status: 0 on success, 1 on invalid arguments or a failed command
   */
  static int run(String[] args, PrintStream out, PrintStream err) {
    VitruvOclCli cli = new VitruvOclCli(out, err);
    try {
      cli.execute(args);
      return 0;
    } catch (ExitException exit) {
      return exit.status;
    }
  }

  private void execute(String[] args) {
    if (args.length == 0) {
      throw invalidArguments("Missing command", true);
    }

    String command = args[0];

    try {
      switch (command) {
        case "check" -> checkConstraint(args);
        case "eval" -> evalConstraint(args);
        case "eval-batch" -> evalBatch(args);
        case "version" -> printVersion();
        default -> throw invalidArguments("Unknown command: " + command, true);
      }
    } catch (ExitException exit) {
      throw exit;
    } catch (Exception e) {
      out.println(CliJson.failure(e));
      throw new ExitException(1);
    }
  }

  private void checkConstraint(String[] args) throws IOException {
    CLIArgs parsed = parseArgs(args);
    String constraintText = Files.readString(parsed.constraintFile);

    ConstraintResult result =
        VitruvOclEngine.forFiles(parsed.ecoreFiles, new Path[0]).evaluate(constraintText);

    out.println(CliJson.check(result));
  }

  private void evalConstraint(String[] args) throws IOException {
    CLIArgs parsed = parseArgs(args);
    String constraintText = Files.readString(parsed.constraintFile);

    ConstraintResult result =
        VitruvOclEngine.forFiles(parsed.ecoreFiles, parsed.xmiFiles).evaluate(constraintText);

    out.println(CliJson.eval(result));
  }

  /**
   * Evaluates all constraints in a file and returns individual results for each.
   *
   * <p>Loads the metamodel/instance context once for the whole batch — via {@link
   * VitruvOclEngine#evaluateFile(Path)}, whose {@code SmartLoader} loading step unions every
   * constraint's dependencies (not just the first one's) before any evaluation starts — and then
   * evaluates the full constraint list through the parallel batch path ({@code
   * ConstraintListEvaluator}), instead of a per-constraint loop that re-loads the context on every
   * iteration.
   *
   * <p>Constraints-file parsing is left to {@link ConstraintFiles} rather than keeping a second,
   * divergent copy of the same splitting logic here — an earlier CLI copy was missing {@code
   * import} line filtering, which silently corrupted every constraint's compiled text whenever the
   * file being evaluated declared an {@code import} (breaking compilation of the whole batch).
   */
  private void evalBatch(String[] args) throws IOException {
    CLIArgs parsed = parseArgs(args);
    Integer threadPoolSize = parseThreadPoolSize(args);
    IncrementalLevel incrementalLevel = parseIncrementalLevel(args);

    VitruvOclEngine engine = VitruvOclEngine.forFiles(parsed.ecoreFiles, parsed.xmiFiles);
    BatchValidationResult batch;
    if (incrementalLevel != null && incrementalLevel != IncrementalLevel.FULL) {
      // The CLI has no transaction: an incremental session can only perform its initial full
      // evaluation here, which yields exactly the batch result of level 0.
      IncrementalConfig config =
          IncrementalConfig.fromSystemProperties()
              .withLevel(incrementalLevel)
              .withThreads(threadPoolSize != null ? threadPoolSize : 1);
      List<String> constraints = ConstraintFiles.read(parsed.constraintFile);
      IncrementalSession session;
      try {
        session = engine.openIncrementalSession(constraints, config);
      } catch (IllegalStateException loadFailure) {
        session = null; // model files could not be loaded: report exactly as level 0 does
      }
      if (session != null) {
        try (IncrementalSession s = session) {
          batch = s.latestResult().result();
        }
      } else {
        batch = engine.withThreadPoolSize(config.threads()).evaluateFile(parsed.constraintFile);
      }
    } else {
      VitruvOclEngine configured =
          threadPoolSize != null ? engine.withThreadPoolSize(threadPoolSize) : engine;
      batch = configured.evaluateFile(parsed.constraintFile);
    }

    out.println(CliJson.batch(batch));
  }

  /**
   * Parses an optional {@code --threads <N>} argument for {@code eval-batch}, controlling how many
   * worker threads {@link VitruvOclEngine#withThreadPoolSize(int)} distributes constraint
   * evaluation across.
   *
   * @param args full CLI argument array
   * @return the requested thread pool size, or {@code null} if {@code --threads} was not given (in
   *     which case the caller uses the engine's default thread pool size)
   */
  @SuppressWarnings("java:S127")
  private Integer parseThreadPoolSize(String[] args) {
    for (int i = 2; i < args.length; i++) {
      if ("--threads".equals(args[i]) && i + 1 < args.length) {
        String value = args[++i].trim();
        try {
          int threadPoolSize = Integer.parseInt(value);
          if (threadPoolSize < 1) {
            throw invalidArguments("--threads must be at least 1, got: " + value, false);
          }
          return threadPoolSize;
        } catch (NumberFormatException e) {
          throw invalidArguments(
              "Invalid --threads value: " + value + " (must be an integer)", false);
        }
      }
    }
    return null;
  }

  /**
   * Parses an optional {@code --incremental-level <0-3>} argument for {@code eval-batch}. Without
   * it, the level is taken from the system property {@value IncrementalConfig#PROP_LEVEL} (default
   * 0); level 0 uses the unchanged batch path.
   *
   * @param args full CLI argument array
   * @return the requested level, or {@code null} if neither option nor property is set
   */
  @SuppressWarnings("java:S127")
  private IncrementalLevel parseIncrementalLevel(String[] args) {
    for (int i = 2; i < args.length; i++) {
      if ("--incremental-level".equals(args[i]) && i + 1 < args.length) {
        String value = args[++i];
        try {
          return IncrementalLevel.parse(value);
        } catch (IllegalArgumentException e) {
          throw invalidArguments(
              "Invalid --incremental-level value: " + value + " (must be 0-3)", false);
        }
      }
    }
    String fromProperty = System.getProperty(IncrementalConfig.PROP_LEVEL);
    if (fromProperty == null) {
      return null;
    }
    try {
      return IncrementalLevel.parse(fromProperty);
    } catch (IllegalArgumentException e) {
      throw invalidArguments(
          "Invalid system property "
              + IncrementalConfig.PROP_LEVEL
              + ": "
              + fromProperty
              + " (must be 0-3)",
          false);
    }
  }

  @SuppressWarnings("java:S127")
  private CLIArgs parseArgs(String[] args) {
    if (args.length < 2) {
      throw invalidArguments("Missing constraint file", true);
    }

    Path constraintFile = Path.of(args[1]);
    Path[] ecoreFiles = new Path[0];
    Path[] xmiFiles = new Path[0];

    for (int i = 2; i < args.length; i++) {
      if ("--ecore".equals(args[i]) && i + 1 < args.length) {
        ecoreFiles =
            Arrays.stream(args[++i].split(","))
                .map(String::trim)
                .map(Path::of)
                .toArray(Path[]::new);
      } else if ("--xmi".equals(args[i]) && i + 1 < args.length) {
        xmiFiles =
            Arrays.stream(args[++i].split(","))
                .map(String::trim)
                .map(Path::of)
                .toArray(Path[]::new);
      }
    }

    return new CLIArgs(constraintFile, ecoreFiles, xmiFiles);
  }

  /**
   * Reports invalid arguments: the message as a JSON failure on standard output, so that a caller
   * parsing it always gets JSON, and the usage text on standard error.
   *
   * @param message what is wrong
   * @param withUsage whether to print the usage text
   * @return the exception that ends the command with status 1
   */
  private ExitException invalidArguments(String message, boolean withUsage) {
    out.println(CliJson.failure(message));
    if (withUsage) {
      printUsage();
    }
    return new ExitException(1);
  }

  private void printUsage() {
    err.println(
        """
        VitruvOCL Command Line Interface

        Usage:
          vitruvocl check <constraint-file> --ecore <files>
              Type-check constraint syntax and semantics

          vitruvocl eval <constraint-file> --ecore <files> --xmi <files>
              Evaluate single constraint against model instances

          vitruvocl eval-batch <constraints-file> --ecore <files> --xmi <files> [--threads <N>]
                               [--incremental-level <0-3>]
              Evaluate all constraints (in parallel) and return individual results

          vitruvocl version
              Print version information

        Arguments:
          <constraint-file>     Path to .ocl/.vocl file with single constraint
          <constraints-file>    Path to .ocl file with multiple constraints
          --ecore <files>       Comma-separated .ecore metamodel files
          --xmi <files>         Comma-separated model instance files
          --threads <N>         eval-batch only: worker threads for constraint evaluation
                                 (default: number of available processors)
          --incremental-level <0-3>
                                eval-batch only: incremental evaluation level (default 0, or the
                                 system property vitruvocl.incremental.level). The CLI has no
                                 transaction, so every level reports the result of the initial
                                 full evaluation.

        Examples:
          vitruvocl check constraints.ocl --ecore spacemission.ecore
          vitruvocl eval constraints.ocl --ecore model.ecore --xmi instance.xmi
          vitruvocl eval-batch constraints.ocl --ecore model.ecore --xmi instance.xmi
          vitruvocl eval-batch constraints.ocl --ecore model.ecore --xmi instance.xmi --threads 4
        """);
  }

  private void printVersion() {
    out.println("VitruvOCL 1.0.0");
  }

  @SuppressWarnings("java:S6218")
  record CLIArgs(Path constraintFile, Path[] ecoreFiles, Path[] xmiFiles) {}

  /** Ends a command with an exit status, after its messages were printed. */
  private static final class ExitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    ExitException(int status) {
      super(null, null, false, false);
      this.status = status;
    }
  }
}
