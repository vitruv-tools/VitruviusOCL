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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.OclLexers;
import tools.vitruv.dsls.vitruvocl.evaluator.AllInstancesTypeScanner;
import tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;
import tools.vitruv.dsls.vitruvocl.typechecker.TypeCheckVisitor;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * A constraint parsed and type-checked once (passes 1 and 2), so that it can be evaluated many
 * times, e.g. in every check of a Vitruv constraint checker, without lexing, parsing and type
 * checking it again. Each evaluation rebuilds only the symbol table, whose variables hold the
 * values of one evaluation; the parse tree and the node types are read-only and may be shared by
 * concurrent evaluations.
 *
 * <p>A constraint that does not compile is evaluated with {@link
 * ConstraintEvaluations#evaluateFresh}, which reports its errors exactly like a fresh compile.
 */
public final class PreparedConstraint {

  private static final AtomicLong PREPARATIONS = new AtomicLong();

  private final String text;
  private final VitruvOCLParser.ContextDeclCSContext tree;
  private final ParseTreeProperty<Type> nodeTypes;

  private PreparedConstraint(
      String text, VitruvOCLParser.ContextDeclCSContext tree, ParseTreeProperty<Type> nodeTypes) {
    this.text = text;
    this.tree = tree;
    this.nodeTypes = nodeTypes;
  }

  /**
   * Parses and type-checks {@code text} against {@code wrapper}'s metamodels; never throws for an
   * invalid constraint.
   */
  public static PreparedConstraint prepare(String text, ModelAccess wrapper) {
    PREPARATIONS.incrementAndGet();
    VitruvOCLLexer lexer = OclLexers.create(CharStreams.fromString(text));
    lexer.removeErrorListeners(); // a failed preparation is re-reported by evaluateFresh
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    VitruvOCLParser parser = new VitruvOCLParser(tokens);
    parser.removeErrorListeners();
    VitruvOCLParser.ContextDeclCSContext tree = parser.contextDeclCS();
    if (parser.getNumberOfSyntaxErrors() > 0) {
      return new PreparedConstraint(text, null, null);
    }
    ErrorCollector errors = new ErrorCollector();
    SymbolTableImpl symbolTable = new SymbolTableImpl(wrapper);
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(symbolTable, wrapper, errors, scopes).visit(tree);
    if (errors.hasErrors()) {
      return new PreparedConstraint(text, null, null);
    }
    TypeCheckVisitor typeChecker = new TypeCheckVisitor(symbolTable, wrapper, errors, scopes);
    typeChecker.setTokenStream(tokens);
    typeChecker.visit(tree);
    if (errors.hasErrors()) {
      return new PreparedConstraint(text, null, null);
    }
    return new PreparedConstraint(text, tree, typeChecker.getNodeTypes());
  }

  /** How many constraints were prepared so far in this JVM (for tests and monitoring). */
  public static long preparations() {
    return PREPARATIONS.get();
  }

  /**
   * Requests the instances of every type the {@code constraints} may need in one call, so that a
   * single traversal serves all of them (Wei &amp; Kolovos' "one traversal serves all", across
   * constraints) instead of one traversal per newly requested type. Only an optimization: a failure
   * is ignored, each constraint's evaluation reports it.
   */
  public static void prefetchInstances(
      Collection<PreparedConstraint> constraints, ModelAccess wrapper) {
    try {
      Set<EClass> types = new LinkedHashSet<>();
      for (PreparedConstraint constraint : constraints) {
        types.addAll(constraint.requestedTypes());
      }
      if (types.size() >= 2) {
        wrapper.getAllInstances(types);
      }
    } catch (RuntimeException | StackOverflowError e) {
      // only an optimization: each constraint's own evaluation reports the failure
    }
  }

  /** The constraint text. */
  public String text() {
    return text;
  }

  /** Whether passes 1 and 2 succeeded. */
  public boolean isCompiled() {
    return tree != null;
  }

  /** The parse tree, or {@code null} if the constraint did not compile. */
  public VitruvOCLParser.ContextDeclCSContext tree() {
    return tree;
  }

  /** The node types of {@link #tree()}, or {@code null} if the constraint did not compile. */
  public ParseTreeProperty<Type> nodeTypes() {
    return nodeTypes;
  }

  /**
   * The metaclasses whose instances evaluating this constraint may request; empty if it did not
   * compile.
   */
  public Set<EClass> requestedTypes() {
    return isCompiled() ? AllInstancesTypeScanner.scan(tree, nodeTypes) : Set.of();
  }

  /**
   * Evaluates the constraint through {@code view}, which must answer for the metamodels it was
   * prepared against.
   *
   * @param transaction the changes of the transaction, for {@code @pre}/lifecycle predicates
   * @param transactionSupported whether pre/post blocks are evaluated ({@code true}) or skipped
   * @param instanceOverride optional: per classifier context, the instances to evaluate instead of
   *     the full extension ({@code null} = full extension)
   */
  public EvaluationOutcome evaluate(
      ModelAccess view,
      List<EChange<EObject>> transaction,
      boolean transactionSupported,
      Function<VitruvOCLParser.ClassifierContextCSContext, List<EObject>> instanceOverride) {
    if (!isCompiled()) {
      return ConstraintEvaluations.evaluateFresh(text, view, transaction, transactionSupported);
    }
    ErrorCollector errors = new ErrorCollector();
    SymbolTableImpl symbolTable = new SymbolTableImpl(view);
    new SymbolTableBuilder(symbolTable, view, errors, new ScopeAnnotator()).visit(tree);
    if (errors.hasErrors()) {
      return EvaluationOutcome.failure(errors.getErrors());
    }
    EvaluationVisitor evaluator =
        new EvaluationVisitor(
            symbolTable, view, errors, nodeTypes, transaction, transactionSupported);
    if (instanceOverride != null) {
      evaluator.setContextInstanceOverride(instanceOverride);
    }
    evaluator.visit(tree);
    if (errors.hasErrors()) {
      return EvaluationOutcome.failure(errors.getErrors());
    }
    return new EvaluationOutcome(
        false,
        List.of(),
        evaluator.getViolationRecords(),
        evaluator.getSkippedConstraints(),
        evaluator.getEvaluatedInstanceCount());
  }
}
