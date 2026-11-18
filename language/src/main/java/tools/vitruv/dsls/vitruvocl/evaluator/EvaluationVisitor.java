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

package tools.vitruv.dsls.vitruvocl.evaluator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.evaluator.transaction.TransactionModel;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.symboltable.AbstractPhaseVisitor;
import tools.vitruv.dsls.vitruvocl.symboltable.LocalScope;
import tools.vitruv.dsls.vitruvocl.symboltable.Scope;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Phase 3 visitor that evaluates OCL expressions and produces runtime values.
 *
 * <p>This visitor implements the <b>evaluation phase</b> of the OCL compiler pipeline, operating
 * after symbol table construction (Phase 1) and type checking (Phase 2). It traverses the parse
 * tree and computes concrete runtime values ({@link Value} objects) for OCL expressions.
 *
 * <h2>Architecture</h2>
 *
 * <p>The evaluator uses pre-computed type information from the type checking phase stored in {@code
 * nodeTypes} (a {@code ParseTreeProperty<Type>}) to perform type-dependent operations correctly. It
 * maintains a {@code receiverStack} to handle method chaining (e.g., {@code
 * collection.select(...).size()}) and uses the symbol table for variable resolution.
 *
 * <h2>Error Handling</h2>
 *
 * <p>Runtime errors (e.g., division by zero, type mismatches) are reported through the {@link
 * ErrorCollector} with source location information. The evaluator returns {@code
 * Value.empty(Type.ERROR)} for failed operations.
 *
 * @see Value The runtime value type representing OCL collections
 * @see AbstractPhaseVisitor Base class providing common visitor infrastructure
 */
public class EvaluationVisitor extends AbstractPhaseVisitor<Value> {

  private static final String PHASE_TAG = "evaluator";

  // ==================== Instance Fields ====================

  /**
   * Pre-computed type information from Phase 2 (type checking).
   *
   * <p>Maps parse tree nodes to their statically determined types. Used for type-dependent
   * operations like determining collection element types for iterators.
   */
  private final ParseTreeProperty<Type> nodeTypes;

  /**
   * Violation records produced during evaluation, one per violated constraint (inv, pre or post)
   * and instance.
   *
   * @param severity The @severity value (never null; defaults to "WARNING").
   * @param customMessage The interpolated @message template, or {@code null} when no @message
   *     annotation was present on the invariant.
   * @param instance The EObject that violated the constraint.
   */
  public record ViolationRecord(String severity, String customMessage, EObject instance) {}

  /**
   * One {@code pre}/{@code post} block that was not evaluated because this {@link
   * EvaluationVisitor} was constructed without transaction support (see {@link
   * #transactionSupported}).
   *
   * @param kind {@code "pre"} or {@code "post"}
   * @param blockName the block's own optional name (e.g. {@code p1} in {@code pre p1: ...}), or
   *     {@code null} if unnamed
   * @param contextName the enclosing context's display name (e.g. {@code spaceMission::Spacecraft})
   */
  public record SkippedConstraint(String kind, String blockName, String contextName) {}

  /** Violations and skipped blocks recorded during this evaluation run. */
  private final ConstraintResultRecorder resultRecorder = new ConstraintResultRecorder();

  /**
   * Number of context instances {@link #visitClassifierContextCS} evaluated its inv/pre/post blocks
   * for during this run. Pure bookkeeping for the incremental evaluator's test statistics; it has
   * no influence on evaluation.
   */
  private int evaluatedInstanceCount = 0;

  /**
   * Optional replacement for the context extension in {@link #visitClassifierContextCS}: when set,
   * it supplies the instances to evaluate per classifier context instead of {@code
   * specification.getAllInstances(contextClass)}. {@code null} (the default, and the value for
   * every caller except the incremental evaluator) keeps the full-extension behaviour unchanged.
   */
  private Function<VitruvOCLParser.ClassifierContextCSContext, List<EObject>>
      contextInstanceOverride = null;

  /**
   * Every EClass the current constraint needs the extension of, collected from the type-checked AST
   * before evaluation (static query analysis, Wei &amp; Kolovos, BigMDE 2015, Section 3.2).
   */
  private Set<EClass> prescannedTypes = Set.of();

  /**
   * The extensions of {@link #prescannedTypes}, fetched with one request on first need; {@code
   * null} until then, so an evaluation that never asks for an extension (e.g. an incremental one
   * with a context-instance override) does not trigger a traversal.
   */
  private Map<EClass, List<EObject>> prescannedInstances;

  /**
   * Stack of receiver values for navigation chains.
   *
   * <p>When evaluating {@code receiver.operation()}, the receiver value is pushed onto this stack
   * before visiting the operation node. This allows operation implementations to access their
   * receiver via {@code receiverStack.peek()}.
   *
   * <p><b>Example:</b> For {@code [1,2,3].select(x | x > 1).size()}, the stack evolves as:
   *
   * <ol>
   *   <li>Push [1,2,3] → visit select → pop → result [2,3]
   *   <li>Push [2,3] → visit size → pop → result 2
   * </ol>
   */
  private final Deque<Value> receiverStack = new ArrayDeque<>();

  /** The values of the variables of this evaluation; the symbols themselves carry no values. */
  private final Environment environment = new Environment();

  /** Runtime semantics of the String operations, see {@link StringOperationEvaluation}. */
  private final StringOperationEvaluation strings =
      new StringOperationEvaluation(new EvaluationContext());

  /** Runtime semantics of the numeric operations, see {@link NumericOperationEvaluation}. */
  private final NumericOperationEvaluation numerics =
      new NumericOperationEvaluation(new EvaluationContext());

  /** Runtime semantics of the collection operations, see {@link CollectionOperationEvaluation}. */
  private final CollectionOperationEvaluation collections =
      new CollectionOperationEvaluation(new EvaluationContext());

  /** Runtime semantics of the iterator operations, see {@link IteratorOperationEvaluation}. */
  private final IteratorOperationEvaluation iterators =
      new IteratorOperationEvaluation(new EvaluationContext());

  /**
   * Runtime semantics of {@code @pre} and the lifecycle predicates against this run's transaction
   * (empty for callers without one), see {@link TransactionEvaluation}.
   */
  private final TransactionEvaluation transactions;

  /** Runtime semantics of navigation chains, see {@link NavigationEvaluation}. */
  private final NavigationEvaluation navigation;

  /**
   * Runtime semantics of allInstances and the type operations, see {@link
   * MetamodelOperationEvaluation}.
   */
  private final MetamodelOperationEvaluation metamodelOperations;

  /**
   * Whether this evaluation run has a real transaction context at all — {@code true} only when
   * constructed via the explicit-transaction constructor (even with an empty change list, which
   * legitimately means "a transaction happened but touched nothing"). {@code false} for the
   * no-transaction constructor, used by every caller that has no notion of a transaction at all
   * (CLI, VS Code plugin): there, {@code pre}/{@code post} blocks are not evaluated with vacuous
   * empty-transaction semantics (which could silently misreport, e.g. {@code OCLisModified} always
   * {@code false}) — they are skipped outright and reported via {@link #getSkippedConstraints()}.
   */
  private final boolean transactionSupported;

  /**
   * System property that switches the correspondence fast paths off (participant filter of {@link
   * CorrespondenceLookup} and guard short-circuits); read once per visitor.
   */
  public static final String PROP_NO_CORRESPONDENCE_FAST_PATHS =
      "vitruvocl.eval.noCorrespondenceFastPaths";

  /**
   * Runtime semantics of the correspondence operations, see {@link CorrespondenceEvaluation}; its
   * memoized {@link CorrespondenceLookup} lives as long as this visitor.
   */
  private final CorrespondenceEvaluation correspondences;

  /**
   * Whether the correspondence fast paths are on (see {@link #PROP_NO_CORRESPONDENCE_FAST_PATHS}).
   */
  private final boolean correspondenceFastPaths;

  /** Decides which logical operands can be skipped without hiding a runtime error. */
  private final ErrorFreeAnalysis errorFreeAnalysis;

  /**
   * Value of each number and string literal node, computed once instead of re-parsing the literal
   * for every context instance and iteration step. Safe to share: values are immutable.
   */
  private final Map<ParserRuleContext, Value> literalValues = new IdentityHashMap<>();

  /** Right operands skipped because their correspondence guard was false (statistics). */
  private int correspondenceShortCircuits;

  // ==================== Constructor ====================

  /**
   * Constructs an EvaluationVisitor for Phase 3 of the compilation pipeline, with no transaction
   * support — {@code pre}/{@code post} blocks are skipped (see {@link #transactionSupported}) and
   * only {@code inv} is evaluated.
   *
   * @param st The symbol table containing variable and type definitions
   * @param specification The metamodel wrapper providing access to model instances
   * @param errors The error collector for reporting runtime errors
   * @param nodeTypes Pre-computed type information from the type checking phase
   */
  public EvaluationVisitor(
      SymbolTable st,
      ModelAccess specification,
      ErrorCollector errors,
      ParseTreeProperty<Type> nodeTypes) {
    this(st, specification, errors, nodeTypes, List.of(), false);
  }

  /**
   * Constructs an EvaluationVisitor for Phase 3 of the compilation pipeline, with transaction
   * support enabled — {@code pre}/{@code post} blocks are evaluated against {@code
   * transactionChanges} (which may itself be empty, meaning "a transaction happened but touched
   * nothing").
   *
   * @param st The symbol table containing variable and type definitions
   * @param specification The metamodel wrapper providing access to model instances
   * @param errors The error collector for reporting runtime errors
   * @param nodeTypes Pre-computed type information from the type checking phase
   * @param transactionChanges the ordered list of atomic changes for this transaction (may be empty
   *     — see {@link TransactionModel})
   */
  public EvaluationVisitor(
      SymbolTable st,
      ModelAccess specification,
      ErrorCollector errors,
      ParseTreeProperty<Type> nodeTypes,
      List<EChange<EObject>> transactionChanges) {
    this(st, specification, errors, nodeTypes, transactionChanges, true);
  }

  /**
   * Full constructor used by {@code VitruvOCLCompiler}, which itself distinguishes "no transaction
   * constructor used" from "explicit, possibly-empty transaction" the same way (see its own two
   * constructors).
   *
   * @param transactionSupported see {@link #transactionSupported}
   */
  public EvaluationVisitor(
      SymbolTable st,
      ModelAccess specification,
      ErrorCollector errors,
      ParseTreeProperty<Type> nodeTypes,
      List<EChange<EObject>> transactionChanges,
      boolean transactionSupported) {
    super(st, specification, errors);
    this.nodeTypes = nodeTypes;
    this.errorFreeAnalysis = new ErrorFreeAnalysis(nodeTypes);
    this.transactionSupported = transactionSupported;
    this.correspondenceFastPaths = !Boolean.getBoolean(PROP_NO_CORRESPONDENCE_FAST_PATHS);
    this.correspondences =
        new CorrespondenceEvaluation(
            new EvaluationContext(),
            new CorrespondenceLookup(specification, correspondenceFastPaths));
    this.transactions =
        new TransactionEvaluation(
            new EvaluationContext(), new TransactionModel(transactionChanges));
    this.navigation = new NavigationEvaluation(new EvaluationContext(), transactions);
    this.metamodelOperations =
        new MetamodelOperationEvaluation(new EvaluationContext(), nodeTypes, this::instancesOf);
  }

  /**
   * How many right operands of {@code G and R} / {@code G implies R} were skipped because the
   * correspondence guard {@code G} was false; for tests and statistics.
   */
  public int getCorrespondenceShortCircuits() {
    return correspondenceShortCircuits;
  }

  // ==================== Error Reporting ====================

  @Override
  protected String phaseTag() {
    return PHASE_TAG;
  }

  /**
   * Reports an undefined variable error.
   *
   * <p>Called when attempting to resolve a variable that doesn't exist in the current scope.
   *
   * @param name The undefined variable name
   * @param ctx The parse tree context where the error occurred
   */
  @Override
  protected void handleUndefinedSymbol(String name, org.antlr.v4.runtime.ParserRuleContext ctx) {
    reportError(ctx, "Variable not bound: " + name);
  }

  // ==================== Context Declaration (Entry Point) ====================

  /**
   * Evaluates the top-level context declaration.
   *
   * <p>Processes all classifier contexts in the OCL document and returns the result of the last
   * evaluated constraint.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * context Person inv:
   *   self.age >= 0
   * context Company inv:
   *   self.employees->size() > 0
   * }</pre>
   *
   * @param ctx The context declaration node
   * @return The value of the last evaluated constraint, or empty if none
   */
  @Override
  public Value visitContextDeclCS(VitruvOCLParser.ContextDeclCSContext ctx) {
    prescannedTypes = AllInstancesTypeScanner.scan(ctx, nodeTypes);
    prescannedInstances = null;
    Value lastResult = null;
    for (VitruvOCLParser.ClassifierContextCSContext classifierCtx : ctx.classifierContextCS()) {
      lastResult = visit(classifierCtx);
    }
    return lastResult != null ? lastResult : Value.empty(Type.ERROR);
  }

  /**
   * Returns all instances of {@code eClass} (subtypes included). Types found by the static
   * analysis are fetched together with one request; any other type (e.g. when evaluation does not
   * start at a {@code contextDeclCS}) falls back to a single-type request.
   */
  private List<EObject> instancesOf(EClass eClass) {
    if (!prescannedTypes.contains(eClass)) {
      return specification.getAllInstances(eClass);
    }
    if (prescannedInstances == null) {
      prescannedInstances = specification.getAllInstances(prescannedTypes);
    }
    return prescannedInstances.getOrDefault(eClass, Collections.emptyList());
  }

  // ==================== Classifier Context (Constraint Evaluation)
  // ====================

  /**
   * Evaluates a classifier context by iterating over all instances of the context type.
   *
   * <p>For each instance of the metaclass specified in the context (e.g., {@code context Person}),
   * this method:
   *
   * <ol>
   *   <li>Creates a new local scope
   *   <li>Binds {@code self} to the current instance
   *   <li>Evaluates all invariants in that scope
   *   <li>Collects results into a bag of boolean values
   * </ol>
   *
   * <p><b>Example:</b> If there are 3 Person instances and the constraint is {@code self.age >= 0},
   * this returns a bag of 3 boolean values, one for each instance.
   *
   * @param ctx The classifier context node
   * @return A bag containing one boolean result per instance
   */
  @Override
  @SuppressWarnings("java:S3776")
  public Value visitClassifierContextCS(VitruvOCLParser.ClassifierContextCSContext ctx) {
    Type contextType = nodeTypes.get(ctx);

    if (contextType == null || contextType == Type.ERROR) {
      return Value.empty(Type.bag(Type.BOOLEAN));
    }

    EClass eClass = contextType.getEClass();
    if (eClass == null) {
      return Value.empty(Type.bag(Type.BOOLEAN));
    }

    // Retrieve all instances of the context type from the metamodel (or the incremental
    // evaluator's relevant subset of them, if an override is installed)
    List<EObject> instances =
        contextInstanceOverride != null ? contextInstanceOverride.apply(ctx) : instancesOf(eClass);
    List<OCLElement> allResults = new ArrayList<>();

    // Everything that does not depend on the instance is set up once, not per instance: the
    // scope, the 'self' symbol (rebound per instance, like the iterator variables - see
    // defineIterVar/rebindIterVar), the operation parameters, the 'self' type, and the lists of
    // invariant/pre/post nodes (ANTLR collects these anew on every accessor call).
    Scope instanceScope = new LocalScope(symbolTable.getCurrentScope());
    symbolTable.enterScope(instanceScope);
    VariableSymbol selfSymbol = bindContextVariables(ctx, contextType);
    exitScope(instanceScope);
    // Bindings made while evaluating one instance are dropped once it is done (see Environment).
    Environment.Mark contextBindings = environment.mark();
    Type selfType = Type.singleton(contextType);
    List<VitruvOCLParser.InvCSContext> invariants = ctx.invCS();
    List<VitruvOCLParser.PreCSContext> preconditions =
        transactionSupported ? ctx.preCS() : List.of();
    List<VitruvOCLParser.PostCSContext> postconditions =
        transactionSupported ? ctx.postCS() : List.of();

    // Evaluate invariants for each instance
    for (EObject instance : instances) {
      evaluatedInstanceCount++;
      symbolTable.enterScope(instanceScope);
      environment.bind(
          selfSymbol, Value.of(List.of(new OCLElement.MetaclassValue(instance)), selfType));

      // Evaluate all invariants/preconditions/postconditions for this instance
      for (VitruvOCLParser.InvCSContext inv : invariants) {
        // Re-enter defensively before each invariant - see EvaluationVisitor#exitScope(Scope):
        // 'self' is never lazy so nothing here currently drains an outer sibling value mid-loop,
        // but this keeps the loop correct regardless of what a future invariant body might touch.
        symbolTable.enterScope(instanceScope);
        Value invResult = visit(inv);
        resultRecorder.record(invResult, allResults, instance, inv.annotationCS());
      }
      for (VitruvOCLParser.PreCSContext pre : preconditions) {
        symbolTable.enterScope(instanceScope);
        Value preResult = visit(pre);
        resultRecorder.record(preResult, allResults, instance, pre.annotationCS());
      }
      for (VitruvOCLParser.PostCSContext post : postconditions) {
        symbolTable.enterScope(instanceScope);
        Value postResult = visit(post);
        resultRecorder.record(postResult, allResults, instance, post.annotationCS());
      }

      exitScope(instanceScope);
      environment.dropBindingsSince(contextBindings);
    }

    // pre/post need a transaction this evaluation run doesn't have
    if (!transactionSupported) {
      resultRecorder.recordSkippedBlocks(ctx);
    }

    return Value.of(allResults, Type.bag(Type.BOOLEAN));
  }

  /**
   * Binds {@code self} and the operation parameters of a classifier context in the current scope,
   * once per context; the caller rebinds {@code self} for each instance.
   *
   * <p>Operation-context params (context Type::operation(name: Type, ...)): there is no
   * method-invocation mechanism in this evaluator, so — unlike 'self' — these are always bound to
   * an empty value rather than a real argument (see class-level docs on pre/post support). Binding
   * them at all (instead of leaving them unresolved) matters: an unresolved variable is a hard
   * evaluation error via handleUndefinedSymbol, whereas an empty-valued binding lets the
   * constraint evaluate (typically to a vacuous/empty result) until a future Reactions/YAML layer
   * supplies real argument values. Since that value never depends on the instance, binding once
   * per context is equivalent to binding per instance.
   *
   * @return the symbol {@code self} resolves to (an existing outer binding, or a new one)
   */
  private VariableSymbol bindContextVariables(
      VitruvOCLParser.ClassifierContextCSContext ctx, Type contextType) {
    VariableSymbol selfSymbol = symbolTable.resolveVariable("self");
    if (selfSymbol == null) {
      selfSymbol = new VariableSymbol("self", contextType, symbolTable.getCurrentScope(), false);
      symbolTable.defineVariable(selfSymbol);
    }
    if (ctx.operationParamListCS() != null) {
      for (VitruvOCLParser.OperationParamCSContext param :
          ctx.operationParamListCS().operationParamCS()) {
        String paramName = param.paramName.getText();
        Value emptyValue = Value.empty(Type.ANY);
        VariableSymbol paramSymbol = symbolTable.resolveVariable(paramName);
        if (paramSymbol != null) {
          environment.bind(paramSymbol, emptyValue);
        } else {
          VariableSymbol newParam =
              new VariableSymbol(paramName, Type.ANY, symbolTable.getCurrentScope(), false);
          environment.bind(newParam, emptyValue);
          symbolTable.defineVariable(newParam);
        }
      }
    }
    return selfSymbol;
  }

  /**
   * Returns the {@code pre}/{@code post} blocks that were not evaluated because this evaluation run
   * has no transaction context (see {@link #transactionSupported}). Empty whenever transaction
   * support is enabled, or whenever the evaluated document has no {@code pre}/{@code post} blocks.
   */
  public List<SkippedConstraint> getSkippedConstraints() {
    return resultRecorder.skippedConstraints();
  }

  /**
   * Returns how many context instances this run evaluated (summed over all classifier contexts).
   * Used only for statistics; see {@link #evaluatedInstanceCount}.
   */
  public int getEvaluatedInstanceCount() {
    return evaluatedInstanceCount;
  }

  /**
   * Installs a replacement for the context extension (see {@link #contextInstanceOverride}). Only
   * used by the incremental evaluator to evaluate a constraint on its relevant instances.
   *
   * @param override per classifier context, the instances to evaluate; {@code null} restores the
   *     default full-extension behaviour
   */
  public void setContextInstanceOverride(
      Function<VitruvOCLParser.ClassifierContextCSContext, List<EObject>> override) {
    this.contextInstanceOverride = override;
  }

  /**
   * Returns EObject instances that violated the constraint during evaluation.
   *
   * <p>Each entry corresponds to one invariant evaluation that returned false. Call after visiting
   * the parse tree to retrieve violation context for precise error reporting.
   *
   * @return Unmodifiable list of violating EObjects in evaluation order
   */
  public List<EObject> getViolatingInstances() {
    return resultRecorder.violatingInstances();
  }

  /**
   * Returns violation records produced during the last evaluation, one per violated constraint
   * (inv, pre or post) and instance.
   */
  public List<ViolationRecord> getViolationRecords() {
    return resultRecorder.violationRecords();
  }

  // ==================== Annotations ====================

  @Override
  public Value visitSeverityAnnotation(VitruvOCLParser.SeverityAnnotationContext ctx) {
    return Value.boolValue(true);
  }

  @Override
  public Value visitMessageAnnotation(VitruvOCLParser.MessageAnnotationContext ctx) {
    return Value.boolValue(true);
  }

  // ==================== Control Flow ====================

  /**
   * Evaluates an if-then-else expression.
   *
   * <p>Evaluates the condition and returns the then-branch if true, else-branch if false.
   * Implements short-circuit evaluation (only one branch is evaluated).
   *
   * <p><b>Syntax:</b> {@code if <condition> then <thenBranch> else <elseBranch> endif}
   *
   * <p><b>Example:</b> {@code if age >= 18 then 'adult' else 'minor' endif}
   *
   * @param ctx The if-expression node
   * @return The value of the selected branch
   */
  @Override
  public Value visitIfExpCS(VitruvOCLParser.IfExpCSContext ctx) {
    Value condVal = visit(ctx.condition);

    if (condVal == null || condVal.isEmpty()) {
      return visit(ctx.elseBranch);
    }

    OCLElement condElem = condVal.getElements().get(0);
    Boolean condition = condElem.tryGetBool();

    if (condition == null) {
      return visit(ctx.elseBranch);
    }

    // Short-circuit: only evaluate the selected branch
    return condition ? visit(ctx.thenBranch) : visit(ctx.elseBranch);
  }

  /**
   * Evaluates an invariant constraint.
   *
   * <p>Processes all specification expressions in the invariant and returns the result of the last
   * one.
   *
   * @param ctx The invariant node
   * @return The boolean result of the constraint evaluation
   */
  @Override
  public Value visitInvCS(VitruvOCLParser.InvCSContext ctx) {
    return evaluateSpecs(ctx.specificationCS());
  }

  /**
   * Evaluates a precondition constraint. Mirrors {@link #visitInvCS}.
   *
   * @param ctx The precondition node
   * @return The boolean result of the constraint evaluation
   */
  @Override
  public Value visitPreCS(VitruvOCLParser.PreCSContext ctx) {
    return evaluateSpecs(ctx.specificationCS());
  }

  /**
   * Evaluates a postcondition constraint. Mirrors {@link #visitInvCS}.
   *
   * @param ctx The postcondition node
   * @return The boolean result of the constraint evaluation
   */
  @Override
  public Value visitPostCS(VitruvOCLParser.PostCSContext ctx) {
    return evaluateSpecs(ctx.specificationCS());
  }

  private Value evaluateSpecs(List<VitruvOCLParser.SpecificationCSContext> specs) {
    Value result = Value.boolValue(true);
    for (VitruvOCLParser.SpecificationCSContext spec : specs) {
      result = visit(spec);
    }
    return result;
  }

  // ==================== Collection Query Operations ====================

  /** Evaluates {@code isEmpty()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitIsEmptyOp(VitruvOCLParser.IsEmptyOpContext ctx) {
    return collections.isEmpty(ctx);
  }

  /** Evaluates {@code notEmpty()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitNotEmptyOp(VitruvOCLParser.NotEmptyOpContext ctx) {
    return collections.notEmpty(ctx);
  }

  /** Evaluates {@code first()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitFirstOp(VitruvOCLParser.FirstOpContext ctx) {
    return collections.first(ctx);
  }

  /** Evaluates {@code last()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitLastOp(VitruvOCLParser.LastOpContext ctx) {
    return collections.last(ctx);
  }

  /** Evaluates {@code reverse()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitReverseOp(VitruvOCLParser.ReverseOpContext ctx) {
    return collections.reverse(ctx);
  }

  /** Evaluates {@code size()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitSizeOp(VitruvOCLParser.SizeOpContext ctx) {
    return collections.size(ctx);
  }

  // ==================== Collection Modification Operations ====================

  /** Evaluates {@code includes()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitIncludesOp(VitruvOCLParser.IncludesOpContext ctx) {
    return collections.includes(ctx);
  }

  /** Evaluates {@code including()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitIncludingOp(VitruvOCLParser.IncludingOpContext ctx) {
    return collections.including(ctx);
  }

  /** Evaluates {@code excluding()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitExcludingOp(VitruvOCLParser.ExcludingOpContext ctx) {
    return collections.excluding(ctx);
  }

  /** Evaluates {@code excludes()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitExcludesOp(VitruvOCLParser.ExcludesOpContext ctx) {
    return collections.excludes(ctx);
  }

  /** Evaluates {@code union()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitUnionOp(VitruvOCLParser.UnionOpContext ctx) {
    return collections.union(ctx);
  }

  /** Evaluates {@code append()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitAppendOp(VitruvOCLParser.AppendOpContext ctx) {
    return collections.append(ctx);
  }

  /** Evaluates {@code flatten()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitFlattenOp(VitruvOCLParser.FlattenOpContext ctx) {
    return collections.flatten(ctx);
  }

  // ==================== Arithmetic Aggregation Operations ====================

  /** Evaluates {@code sum()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitSumOp(VitruvOCLParser.SumOpContext ctx) {
    return collections.sum(ctx);
  }

  /** Evaluates {@code max()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitMaxOp(VitruvOCLParser.MaxOpContext ctx) {
    return collections.max(ctx);
  }

  /** Evaluates {@code min()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitMinOp(VitruvOCLParser.MinOpContext ctx) {
    return collections.min(ctx);
  }

  /** Evaluates {@code avg()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitAvgOp(VitruvOCLParser.AvgOpContext ctx) {
    return collections.avg(ctx);
  }

  /** Evaluates {@code abs()}; see {@link NumericOperationEvaluation}. */
  @Override
  public Value visitAbsOp(VitruvOCLParser.AbsOpContext ctx) {
    return numerics.abs(ctx);
  }

  /** Evaluates {@code floor()}; see {@link NumericOperationEvaluation}. */
  @Override
  public Value visitFloorOp(VitruvOCLParser.FloorOpContext ctx) {
    return numerics.floor(ctx);
  }

  /** Evaluates {@code ceil()}; see {@link NumericOperationEvaluation}. */
  @Override
  public Value visitCeilOp(VitruvOCLParser.CeilOpContext ctx) {
    return numerics.ceil(ctx);
  }

  /** Evaluates {@code round()}; see {@link NumericOperationEvaluation}. */
  @Override
  public Value visitRoundOp(VitruvOCLParser.RoundOpContext ctx) {
    return numerics.round(ctx);
  }

  // ==================== String Operations ====================

  /** Evaluates {@code length()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitLengthOp(VitruvOCLParser.LengthOpContext ctx) {
    return strings.length(ctx);
  }

  /** Evaluates {@code concat()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitConcatOp(VitruvOCLParser.ConcatOpContext ctx) {
    return strings.concat(ctx);
  }

  /** Evaluates {@code substring()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitSubstringOp(VitruvOCLParser.SubstringOpContext ctx) {
    return strings.substring(ctx);
  }

  /** Evaluates {@code toUpper()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitToUpperOp(VitruvOCLParser.ToUpperOpContext ctx) {
    return strings.toUpper(ctx);
  }

  /** Evaluates {@code toLower()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitToLowerOp(VitruvOCLParser.ToLowerOpContext ctx) {
    return strings.toLower(ctx);
  }

  /** Evaluates {@code indexOf()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitIndexOfOp(VitruvOCLParser.IndexOfOpContext ctx) {
    return strings.indexOf(ctx);
  }

  /** Evaluates {@code equalsIgnoreCase()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitEqualsIgnoreCaseOp(VitruvOCLParser.EqualsIgnoreCaseOpContext ctx) {
    return strings.equalsIgnoreCase(ctx);
  }

  // ==================== Iterator Operations ====================

  /** Evaluates {@code select()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitSelectOp(VitruvOCLParser.SelectOpContext ctx) {
    return iterators.select(ctx);
  }

  /** Evaluates {@code one()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitOneOp(VitruvOCLParser.OneOpContext ctx) {
    return iterators.one(ctx);
  }

  /** Evaluates {@code any()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitAnyOp(VitruvOCLParser.AnyOpContext ctx) {
    return iterators.any(ctx);
  }

  /** Evaluates {@code isUnique()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitIsUniqueOp(VitruvOCLParser.IsUniqueOpContext ctx) {
    return iterators.isUnique(ctx);
  }

  /** Evaluates {@code sortedBy()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitSortedByOp(VitruvOCLParser.SortedByOpContext ctx) {
    return iterators.sortedBy(ctx);
  }

  /** Evaluates {@code collectNested()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitCollectNestedOp(VitruvOCLParser.CollectNestedOpContext ctx) {
    return iterators.collectNested(ctx);
  }

  /** Evaluates {@code iterate()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitIterateOp(VitruvOCLParser.IterateOpContext ctx) {
    return iterators.iterate(ctx);
  }

  /** Evaluates {@code reject()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitRejectOp(VitruvOCLParser.RejectOpContext ctx) {
    return iterators.reject(ctx);
  }

  /** Evaluates {@code collect()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitCollectOp(VitruvOCLParser.CollectOpContext ctx) {
    return iterators.collect(ctx);
  }

  /** Evaluates {@code forAll()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitForAllOp(VitruvOCLParser.ForAllOpContext ctx) {
    return iterators.forAll(ctx);
  }

  /** Evaluates {@code exists()}; see {@link IteratorOperationEvaluation}. */
  @Override
  public Value visitExistsOp(VitruvOCLParser.ExistsOpContext ctx) {
    return iterators.exists(ctx);
  }

  /** Evaluates {@code oclAsType(T)}. See {@link MetamodelOperationEvaluation#asType}. */
  @Override
  public Value visitOclAsTypeOp(VitruvOCLParser.OclAsTypeOpContext ctx) {
    return metamodelOperations.asType(ctx);
  }

  /**
   * Evaluates variable references.
   *
   * <p>Handles the special {@code null} keyword, which evaluates to an empty optional value {@code
   * ?Any? = []} rather than looking up a variable. All other names are resolved from the symbol
   * table.
   *
   * @param ctx The variable expression node
   * @return The value bound to the variable, or empty optional for {@code null}
   */
  @Override
  public Value visitVariableExpCS(VitruvOCLParser.VariableExpCSContext ctx) {
    String varName = ctx.varName.getText();

    // null is not valid in OCL# — type checker already prevents reaching this
    if (varName.equals("null")) {
      return Value.empty(Type.optional(Type.ANY));
    }

    VariableSymbol varSymbol = symbolTable.resolveVariable(varName);
    if (varSymbol == null) {
      handleUndefinedSymbol(varName, ctx);
      return Value.empty(Type.ERROR);
    }

    Value value = environment.valueOf(varSymbol);
    if (value == null) {
      return Value.empty(Type.ANY);
    }

    return value;
  }

  /**
   * Restores {@code symbolTable}'s current scope to {@code enteredScope}'s enclosing scope,
   * explicitly — <b>not</b> via the symbol table's own {@code exitScope()}, which ascends from
   * whatever scope happens to be current rather than from {@code enteredScope} specifically.
   *
   * <p>That distinction is load-bearing once lazy {@link Value}s are involved: evaluating a body
   * expression while {@code enteredScope} is current can itself force materialization of an
   * <em>outer</em>, already-existing lazy {@code Value} (e.g. a {@code let}-bound variable, or an
   * enclosing iterator variable's collection) whose own scope has nothing to do with {@code
   * enteredScope}. Draining that outer value enters/exits <em>its</em> scope internally, leaving
   * {@code symbolTable}'s current scope pointing at some ancestor of {@code enteredScope} by the
   * time control returns here — the plain, argument-less {@code exitScope()} would then ascend
   * from that wrong place instead of from {@code enteredScope}. Since a {@link LocalScope}'s
   * parent is fixed at construction (see {@link LocalScope#getEnclosingScope()}), going there
   * directly is always correct regardless of any such drift.
   *
   * @param enteredScope the exact scope object that was entered and is now being left
   */
  private void exitScope(Scope enteredScope) {
    symbolTable.enterScope(enteredScope.getEnclosingScope());
  }

  // ==================== Arithmetic Operations ====================

  /** Evaluates {@code includesAll()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitIncludesAllOp(VitruvOCLParser.IncludesAllOpContext ctx) {
    return collections.includesAll(ctx);
  }

  /** Evaluates {@code excludesAll()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitExcludesAllOp(VitruvOCLParser.ExcludesAllOpContext ctx) {
    return collections.excludesAll(ctx);
  }

  /** Evaluates {@code count()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitCountOp(VitruvOCLParser.CountOpContext ctx) {
    return collections.count(ctx);
  }

  /** Evaluates {@code prepend()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitPrependOp(VitruvOCLParser.PrependOpContext ctx) {
    return collections.prepend(ctx);
  }

  /** Evaluates {@code insertAt()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitInsertAtOp(VitruvOCLParser.InsertAtOpContext ctx) {
    return collections.insertAt(ctx);
  }

  /** Evaluates {@code symmetricDifference()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitSymmetricDifferenceOp(VitruvOCLParser.SymmetricDifferenceOpContext ctx) {
    return collections.symmetricDifference(ctx);
  }

  /** Evaluates {@code div()}; see {@link NumericOperationEvaluation}. */
  @Override
  public Value visitDivOp(VitruvOCLParser.DivOpContext ctx) {
    return numerics.div(ctx);
  }

  /** Evaluates {@code mod()}; see {@link NumericOperationEvaluation}. */
  @Override
  public Value visitModOp(VitruvOCLParser.ModOpContext ctx) {
    return numerics.mod(ctx);
  }

  /**
   * Evaluates multiplicative operations ({@code *} and {@code /}).
   *
   * <p>Numeric promotion (INTEGER ⊂ FLOAT ⊂ DOUBLE):
   *
   * <ul>
   *   <li>Both operands INTEGER with {@code *} → integer arithmetic
   *   <li>Both operands INTEGER with {@code /} → real arithmetic (DOUBLE)
   *   <li>Either operand FLOAT (and neither DOUBLE) → float arithmetic
   *   <li>Either operand DOUBLE → double arithmetic
   * </ul>
   *
   * <p>Integer division by zero is an error; floating-point division by zero produces ±Infinity
   * following IEEE 754.
   *
   * @param ctx The multiplicative operation node
   * @return A singleton numeric value
   */
  @Override
  @SuppressWarnings("java:S3776")
  public Value visitMultiplicative(VitruvOCLParser.MultiplicativeContext ctx) {
    Value leftValue = visit(ctx.left);
    Value rightValue = visit(ctx.right);
    String operator = ctx.op.getText();

    if (leftValue.isEmpty() || rightValue.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }

    OCLElement leftElem = leftValue.getElements().get(0);
    OCLElement rightElem = rightValue.getElements().get(0);

    // ── Integer × Integer ──────────────────────────────────────────────────
    Integer leftInt = leftElem.tryGetInt();
    Integer rightInt = rightElem.tryGetInt();
    if (leftInt != null && rightInt != null) {
      if (operator.equals("/") && rightInt == 0) {
        return Value.empty(Type.DOUBLE);
      }
      if (operator.equals("/")) {
        return Value.doubleValue(((double) leftInt) / rightInt);
      }
      int result = leftInt * rightInt;
      return Value.intValue(result);
    }

    // ── Any numeric × Any numeric → promote to double ──────────────────────
    if (OCLElement.isNumeric(leftElem) && OCLElement.isNumeric(rightElem)) {
      double l = leftElem.toDoubleValue();
      double r = rightElem.toDoubleValue();
      double result = operator.equals("*") ? l * r : l / r;

      // If both operands are Float → stay Float (no Double contamination)
      if (leftElem instanceof OCLElement.FloatValue && rightElem instanceof OCLElement.FloatValue) {
        return Value.of(
            java.util.List.of(new OCLElement.FloatValue((float) result)),
            tools.vitruv.dsls.vitruvocl.types.Type.FLOAT);
      }
      return Value.doubleValue(result);
    }

    return Value.empty(Type.INTEGER);
  }

  /**
   * Evaluates additive operations ({@code +} and {@code -}).
   *
   * <p>Numeric promotion (INTEGER ⊂ FLOAT ⊂ DOUBLE):
   *
   * <ul>
   *   <li>Both operands INTEGER → integer arithmetic
   *   <li>Either operand FLOAT (and neither DOUBLE) → float arithmetic
   *   <li>Either operand DOUBLE → double arithmetic
   * </ul>
   *
   * @param ctx The additive operation node
   * @return A singleton numeric value
   */
  @Override
  public Value visitAdditive(VitruvOCLParser.AdditiveContext ctx) {
    Value leftValue = visit(ctx.left);
    Value rightValue = visit(ctx.right);
    String operator = ctx.op.getText();

    if (leftValue.isEmpty() || rightValue.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }

    OCLElement leftElem = leftValue.getElements().get(0);
    OCLElement rightElem = rightValue.getElements().get(0);

    // ── Integer ± Integer ──────────────────────────────────────────────────
    Integer leftInt = leftElem.tryGetInt();
    Integer rightInt = rightElem.tryGetInt();
    if (leftInt != null && rightInt != null) {
      int result = operator.equals("+") ? leftInt + rightInt : leftInt - rightInt;
      return Value.intValue(result);
    }

    // ── Any numeric ± Any numeric → promote to double ──────────────────────
    if (OCLElement.isNumeric(leftElem) && OCLElement.isNumeric(rightElem)) {
      double l = leftElem.toDoubleValue();
      double r = rightElem.toDoubleValue();
      double result = operator.equals("+") ? l + r : l - r;

      // If both operands are Float → stay Float
      if (leftElem instanceof OCLElement.FloatValue && rightElem instanceof OCLElement.FloatValue) {
        return Value.of(
            java.util.List.of(new OCLElement.FloatValue((float) result)),
            tools.vitruv.dsls.vitruvocl.types.Type.FLOAT);
      }
      return Value.doubleValue(result);
    }

    return Value.empty(Type.INTEGER);
  }

  // ==================== Comparison Operations ====================

  /**
   * Evaluates equality comparison (==). Both operands must be singletons for comparison.
   *
   * @param ctx the equality comparison context
   * @return singleton boolean Value with comparison result
   */
  @Override
  public Value visitEqualityComparison(VitruvOCLParser.EqualityComparisonContext ctx) {
    return evaluateBinaryComparison(ctx.left, ctx.right, ctx, OCLElement::semanticEquals);
  }

  /**
   * Evaluates inequality comparison (!=). Both operands must be singletons for comparison.
   *
   * @param ctx the inequality comparison context
   * @return singleton boolean Value with comparison result
   */
  @Override
  public Value visitInequalityComparison(VitruvOCLParser.InequalityComparisonContext ctx) {
    return evaluateBinaryComparison(
        ctx.left, ctx.right, ctx, (left, right) -> !OCLElement.semanticEquals(left, right));
  }

  /** Invalid operator sequence — type checker already reported the error; yield empty. */
  @Override
  public Value visitInvalidBinaryOp(VitruvOCLParser.InvalidBinaryOpContext ctx) {
    return Value.empty(Type.ERROR);
  }

  /** Typo of select/reject/exists with ~ syntax — type checker already reported the error. */
  @Override
  public Value visitUnknownCorrOp(VitruvOCLParser.UnknownCorrOpContext ctx) {
    return Value.empty(Type.ERROR);
  }

  /**
   * Evaluates less-than comparison. Both operands must be singletons for comparison.
   *
   * @param ctx the less-than comparison context
   * @return singleton boolean Value with comparison result
   */
  @Override
  public Value visitLessThanComparison(VitruvOCLParser.LessThanComparisonContext ctx) {
    return evaluateBinaryComparison(
        ctx.left, ctx.right, ctx, (left, right) -> OCLElement.compareRelational(left, right) < 0);
  }

  /**
   * Evaluates less-than-or-equal comparison. Both operands must be singletons for comparison.
   *
   * @param ctx the less-than-or-equal comparison context
   * @return singleton boolean Value with comparison result
   */
  @Override
  public Value visitLessThanOrEqualComparison(
      VitruvOCLParser.LessThanOrEqualComparisonContext ctx) {
    return evaluateBinaryComparison(
        ctx.left, ctx.right, ctx, (left, right) -> OCLElement.compareRelational(left, right) <= 0);
  }

  /**
   * Evaluates greater-than comparison. Both operands must be singletons for comparison.
   *
   * @param ctx the greater-than comparison context
   * @return singleton boolean Value with comparison result
   */
  @Override
  public Value visitGreaterThanComparison(VitruvOCLParser.GreaterThanComparisonContext ctx) {
    return evaluateBinaryComparison(
        ctx.left, ctx.right, ctx, (left, right) -> OCLElement.compareRelational(left, right) > 0);
  }

  /**
   * Evaluates greater-than-or-equal comparison (>=). Both operands must be singletons for
   * comparison.
   *
   * @param ctx the greater-than-or-equal comparison context
   * @return singleton boolean Value with comparison result
   */
  @Override
  public Value visitGreaterThanOrEqualComparison(
      VitruvOCLParser.GreaterThanOrEqualComparisonContext ctx) {
    return evaluateBinaryComparison(
        ctx.left, ctx.right, ctx, (left, right) -> OCLElement.compareRelational(left, right) >= 0);
  }

  /**
   * Helper method for evaluating binary comparisons. Extracts singleton operands and applies the
   * comparison function.
   *
   * @param leftCtx the left operand context
   * @param rightCtx the right operand context
   * @param errorCtx context for error reporting
   * @param comparisonFn the comparison function to apply
   * @return singleton boolean Value with comparison result
   */
  private Value evaluateBinaryComparison(
      ParserRuleContext leftCtx,
      ParserRuleContext rightCtx,
      ParserRuleContext errorCtx,
      java.util.function.BiPredicate<OCLElement, OCLElement> comparisonFn) {

    Value leftValue = visit(leftCtx);
    Value rightValue = visit(rightCtx);

    // Vacuously true if either operand is empty (no instances)
    if (leftValue.isEmpty() || rightValue.isEmpty()) {
      return Value.boolValue(true);
    }

    if (leftValue.size() != 1 || rightValue.size() != 1) {
      reportError(errorCtx, "Comparison requires singleton operands");
      return Value.boolValue(false);
    }

    OCLElement leftElem = leftValue.getElements().get(0);
    OCLElement rightElem = rightValue.getElements().get(0);

    boolean result = comparisonFn.test(leftElem, rightElem);
    return Value.boolValue(result);
  }

  // ==================== Boolean Logic Operations ====================

  // ============================================================================
  // Logical operations (3 labeled alternatives)
  // ============================================================================

  /**
   * Evaluates logical AND operation. Both operands must be singleton booleans.
   *
   * <p><b>Example:</b> {@code true and false} → {@code false}
   *
   * @param ctx the logical AND context
   * @return singleton boolean Value with AND result
   */
  @Override
  public Value visitLogicalAnd(VitruvOCLParser.LogicalAndContext ctx) {
    Value leftValue = visit(ctx.left);
    // anything but a single true makes the conjunction false for every value of R
    if (!isTrue(leftValue) && errorFreeAnalysis.cannotReportErrors(ctx.right)) {
      countShortCircuit(ctx.left);
      return Value.boolValue(false);
    }
    return combineLogical(leftValue, visit(ctx.right), (left, right) -> left && right);
  }

  /** Counts a skipped right operand behind a correspondence guard (observable in tests). */
  private void countShortCircuit(ParserRuleContext left) {
    if (isCorrespondenceGuard(left)) {
      correspondenceShortCircuits++;
    }
  }

  /**
   * Evaluates logical OR operation. Both operands must be singleton booleans.
   *
   * <p><b>Example:</b> {@code true or false} → {@code true}
   *
   * @param ctx the logical OR context
   * @return singleton boolean Value with OR result
   */
  @Override
  public Value visitLogicalOr(VitruvOCLParser.LogicalOrContext ctx) {
    Value leftValue = visit(ctx.left);
    if (isTrue(leftValue) && errorFreeAnalysis.cannotReportErrors(ctx.right)) {
      return Value.boolValue(true); // true or R is true for every value of R, even an undefined one
    }
    Value rightValue = visit(ctx.right);
    if (isTrue(leftValue) || isTrue(rightValue)) {
      return Value.boolValue(true);
    }
    return combineLogical(leftValue, rightValue, (left, right) -> left || right);
  }

  /**
   * Evaluates logical XOR operation. Both operands must be singleton booleans.
   *
   * <p><b>Example:</b> {@code true xor true} → {@code false}
   *
   * @param ctx the logical XOR context
   * @return singleton boolean Value with XOR result
   */
  @Override
  public Value visitLogicalXor(VitruvOCLParser.LogicalXorContext ctx) {
    return evaluateBinaryLogical(ctx.left, ctx.right, (left, right) -> left ^ right);
  }

  /**
   * Helper method for evaluating binary logical operations. Extracts singleton boolean operands and
   * applies the logical function.
   *
   * @param leftCtx the left operand context
   * @param rightCtx the right operand context
   * @param errorCtx context for error reporting
   * @param logicalFn the logical function to apply
   * @return singleton boolean Value with operation result
   */
  private Value evaluateBinaryLogical(
      ParserRuleContext leftCtx,
      ParserRuleContext rightCtx,
      java.util.function.BiPredicate<Boolean, Boolean> logicalFn) {

    Value leftValue = visit(leftCtx);
    Value rightValue = visit(rightCtx);
    return combineLogical(leftValue, rightValue, logicalFn);
  }

  /** Applies a binary logical operation to two already evaluated operands. */
  private static Value combineLogical(
      Value leftValue,
      Value rightValue,
      java.util.function.BiPredicate<Boolean, Boolean> logicalFn) {
    if (leftValue.isEmpty() || rightValue.isEmpty()) {
      return Value.boolValue(false);
    }
    if (leftValue.size() != 1 || rightValue.size() != 1) {
      return Value.boolValue(false);
    }

    OCLElement leftElem = leftValue.getElements().get(0);
    OCLElement rightElem = rightValue.getElements().get(0);

    Boolean left = leftElem.tryGetBool();
    Boolean right = rightElem.tryGetBool();

    if (left == null || right == null) {
      return Value.boolValue(false);
    }

    boolean result = logicalFn.test(left, right);
    return Value.boolValue(result);
  }

  /**
   * Evaluates the implication operation (implies).
   *
   * <p>Implements logical implication: {@code A implies B} is equivalent to {@code (not A) or B}.
   * Both operands must be singleton booleans.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code true implies true} → {@code true}
   *   <li>{@code true implies false} → {@code false}
   *   <li>{@code false implies true} → {@code true}
   *   <li>{@code false implies false} → {@code true}
   * </ul>
   *
   * @param ctx The implication operation node
   * @return A singleton boolean value
   */
  @Override
  public Value visitImplication(VitruvOCLParser.ImplicationContext ctx) {
    Value leftValue = visit(ctx.left);
    // anything but a single true makes the implication true for every value of R
    if (!isTrue(leftValue) && errorFreeAnalysis.cannotReportErrors(ctx.right)) {
      countShortCircuit(ctx.left);
      return Value.boolValue(true);
    }
    Value rightValue = visit(ctx.right);

    if (leftValue.isEmpty() || rightValue.isEmpty()) {
      return Value.boolValue(true);
    }
    if (leftValue.size() != 1 || rightValue.size() != 1) {
      return Value.boolValue(true);
    }

    OCLElement leftElem = leftValue.getElements().get(0);
    OCLElement rightElem = rightValue.getElements().get(0);

    Boolean left = leftElem.tryGetBool();
    Boolean right = rightElem.tryGetBool();

    if (left == null || right == null) {
      return Value.boolValue(true);
    }

    // A implies B ≡ ¬A ∨ B
    boolean result = !left || right;
    return Value.boolValue(result);
  }

  // ==================== Unary Operations & Navigation ====================

  /**
   * Evaluates primary expression with navigation chain.
   *
   * <p>Processes base expression followed by zero or more navigation steps (property access, method
   * calls, iterator operations). Each navigation step uses the previous result as its receiver,
   * enabling method chaining like {@code spacecraft.payload.name}.
   *
   * @param ctx Parser context for primary expression with navigation
   * @return Final value after applying all navigation steps, or error Value if any step fails
   */
  @Override
  public Value visitPrimaryWithNav(VitruvOCLParser.PrimaryWithNavContext ctx) {
    // Evaluate base expression
    Value currentValue = visit(ctx.base);

    if (currentValue == null) {
      return Value.empty(Type.ANY);
    }

    // Process navigation chain (including '@pre', see NavigationEvaluation#navigate)
    currentValue = navigation.navigate(ctx.navigationChainCS(), currentValue);

    return currentValue;
  }

  /**
   * Evaluates unary minus operation.
   *
   * <p>Implements OCL unary negation: requires singleton integer operand, returns negated value as
   * singleton collection following "everything is a collection" principle.
   *
   * @param ctx Parser context for unary minus expression
   * @return Singleton collection containing negated integer, or error Value if operand is
   *     non-singleton or non-integer
   */
  @Override
  public Value visitUnaryMinus(VitruvOCLParser.UnaryMinusContext ctx) {
    Value operandValue = visit(ctx.operand);

    if (operandValue == null || operandValue.isEmpty()) {
      return Value.empty(Type.INTEGER);
    }
    if (operandValue.size() != 1) {
      return Value.empty(Type.INTEGER);
    }

    OCLElement elem = operandValue.getElements().get(0);
    Double dblValue = elem.tryGetDouble();
    if (dblValue != null) {
      if (elem instanceof OCLElement.FloatValue) {
        return Value.of(
            java.util.List.of(new OCLElement.FloatValue((float) -dblValue)), Type.FLOAT);
      }
      return Value.doubleValue(-dblValue);
    }
    Integer value = elem.tryGetInt();
    if (value == null) {
      return Value.empty(Type.INTEGER);
    }

    return Value.intValue(-value);
  }

  /**
   * Evaluates logical not operation.
   *
   * <p>Implements OCL boolean negation: negates all boolean elements in the operand collection.
   * Supports both singleton and multi-element collections, returning a collection of the same size
   * with all values negated.
   *
   * @param ctx Parser context for logical not expression
   * @return Collection of negated boolean values, or error Value if any element is non-boolean
   */
  @Override
  public Value visitLogicalNot(VitruvOCLParser.LogicalNotContext ctx) {
    Value operandValue = visit(ctx.operand);

    if (operandValue == null) {
      return Value.empty(Type.BOOLEAN);
    }

    // Handle collection of booleans - negate all elements
    List<OCLElement> results = new ArrayList<>();
    for (OCLElement elem : operandValue.getElements()) {
      Boolean value = elem.tryGetBool();
      if (value == null) {
        continue;
      }
      results.add(new OCLElement.BoolValue(!value));
    }

    Type resultType = nodeTypes.get(ctx);
    return Value.of(results, resultType != null ? resultType : Type.BOOLEAN);
  }

  // ==================== Metamodel Operations ====================

  /** Evaluates {@code allInstances()}. See {@link MetamodelOperationEvaluation#allInstances}. */
  @Override
  public Value visitAllInstancesOp(VitruvOCLParser.AllInstancesOpContext ctx) {
    return metamodelOperations.allInstances(ctx);
  }

  // ==================== Type Checking Operations ====================

  /** Evaluates {@code oclIsKindOf(T)}. See {@link MetamodelOperationEvaluation#isKindOf}. */
  @Override
  public Value visitOclIsKindOfOp(VitruvOCLParser.OclIsKindOfOpContext ctx) {
    return metamodelOperations.isKindOf(ctx);
  }

  /** Evaluates {@code oclIsTypeOf(T)}. See {@link MetamodelOperationEvaluation#isTypeOf}. */
  @Override
  public Value visitOclIsTypeOfOp(VitruvOCLParser.OclIsTypeOfOpContext ctx) {
    return metamodelOperations.isTypeOf(ctx);
  }

  // ==================== Cross-Metamodel Support ====================

  /**
   * Evaluates fully qualified metaclass references (e.g., {@code spaceMission::Spacecraft}).
   *
   * <p>This is the entry point for cross-metamodel constraints, allowing references to metaclasses
   * from different metamodels using qualified names.
   *
   * <p><b>Example:</b> {@code satelliteSystem::Satellite.allInstances()}
   *
   * @param ctx The prefixed qualified name node
   * @return The result of evaluating navigation on the qualified type
   */
  @Override
  public Value visitPrefixedQualified(VitruvOCLParser.PrefixedQualifiedContext ctx) {
    String metamodel = ctx.metamodel.getText();
    String className = ctx.className.getText();
    String qualifiedName = metamodel + "::" + className;

    // First try: enum literal (EnumName::LiteralName)
    org.eclipse.emf.ecore.EEnum eEnum = specification.resolveEEnum(metamodel);
    if (eEnum != null) {
      org.eclipse.emf.ecore.EEnumLiteral literal = eEnum.getEEnumLiteral(className);
      if (literal != null) {
        return new Value(List.of(new OCLElement.EnumValue(literal)), Type.enumType(eEnum));
      }
    }

    // Second try: metaclass type (metamodel::ClassName)
    Type metaclassType = symbolTable.lookupType(qualifiedName);
    if (metaclassType == null || metaclassType == Type.ERROR) {
      return Value.empty(Type.set(Type.ANY));
    }

    return navigation.navigate(ctx.navigationChainCS(), Value.of(List.of(), metaclassType));
  }

  // ==================== Collection Literals ====================

  /**
   * Evaluates collection literal expressions.
   *
   * <p>Creates runtime collections from literal syntax like {@code Bag{1,2,3}} or {@code
   * Set{'a','b'}}.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code Bag{1,2,3}} → bag with 3 elements
   *   <li>{@code Set{1,2,2}} → set with 2 elements (duplicates removed)
   *   <li>{@code Sequence{1..5}} → sequence from 1 to 5
   * </ul>
   *
   * @param ctx The collection literal node
   * @return The evaluated collection value
   */
  @Override
  public Value visitCollectionLiteralExpCS(VitruvOCLParser.CollectionLiteralExpCSContext ctx) {
    Type collectionType = nodeTypes.get(ctx);

    if (ctx.arguments == null) {
      return Value.empty(collectionType);
    }

    Value argumentsValue = visit(ctx.arguments);
    List<OCLElement> elements = argumentsValue.getElements();

    Value collection = Value.of(elements, collectionType);

    // Remove duplicates for Set types
    if (collectionType.isUnique()) {
      collection = collection.removeDuplicates();
    }
    return collection;
  }

  /**
   * Evaluates collection literal arguments.
   *
   * <p>Processes the elements inside collection literal braces, handling both individual values and
   * ranges.
   *
   * @param ctx The collection arguments node
   * @return A collection containing all argument elements
   */
  @Override
  public Value visitCollectionArguments(VitruvOCLParser.CollectionArgumentsContext ctx) {
    List<OCLElement> elements = new ArrayList<>();

    for (VitruvOCLParser.CollectionLiteralPartCSContext partCtx : ctx.collectionLiteralPartCS()) {
      Value partValue = visit(partCtx);
      // Range syntax (1..5) always flattens
      if (partCtx.expCS().size() == 2) {
        elements.addAll(partValue.getElements());
      } else if (partValue.getRuntimeType().isCollection()) {
        // Nested collection literal — keep as single element
        elements.add(new OCLElement.NestedCollection(partValue));
      } else {
        elements.addAll(partValue.getElements());
      }
    }

    return new Value(elements, Type.ANY);
  }

  /** Evaluates {@code ceiling()}; see {@link NumericOperationEvaluation}. */
  @Override
  public Value visitCeilingOp(VitruvOCLParser.CeilingOpContext ctx) {
    return numerics.ceiling(ctx);
  }

  /**
   * Evaluates a single collection literal part (element or range).
   *
   * <p>Handles:
   *
   * <ul>
   *   <li>Single values: {@code 42}
   *   <li>Ranges: {@code 1..10} (generates all integers in the range)
   * </ul>
   *
   * @param ctx The collection literal part node
   * @return The element(s) represented by this part
   */
  @Override
  @SuppressWarnings("java:S3776")
  public Value visitCollectionLiteralPartCS(VitruvOCLParser.CollectionLiteralPartCSContext ctx) {
    Value firstValue = visit(ctx.expCS(0));

    // Range syntax: start..end
    if (ctx.expCS().size() == 2) {
      Value secondValue = visit(ctx.expCS(1));

      if (firstValue.isEmpty() || secondValue.isEmpty()) {
        return Value.empty(Type.INTEGER);
      }
      if (firstValue.size() != 1 || secondValue.size() != 1) {
        return Value.empty(Type.INTEGER);
      }

      OCLElement firstElem = firstValue.getElements().get(0);
      OCLElement secondElem = secondValue.getElements().get(0);

      Integer start = firstElem.tryGetInt();
      Integer end = secondElem.tryGetInt();

      if (start == null || end == null) {
        return Value.empty(Type.INTEGER);
      }

      // Generate range (supports both ascending and descending)
      List<OCLElement> range = new ArrayList<>();
      if (start <= end) {
        for (int i = start; i <= end; i++) {
          range.add(new OCLElement.IntValue(i));
        }
      } else {
        for (int i = start; i >= end; i--) {
          range.add(new OCLElement.IntValue(i));
        }
      }

      return new Value(range, Type.INTEGER);
    }

    return firstValue;
  }

  // ==================== Advanced Collection Operations ====================

  /** Evaluates {@code lift()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitLiftOp(VitruvOCLParser.LiftOpContext ctx) {
    return collections.lift(ctx);
  }

  // ==================== Literal Values ====================

  /**
   * Evaluates integer literals.
   *
   * <p><b>Example:</b> {@code 42} → singleton integer value
   *
   * @param ctx The number literal node
   * @return A singleton integer value
   */
  @Override
  public Value visitNumberLit(VitruvOCLParser.NumberLitContext ctx) {
    return literalValues.computeIfAbsent(
        ctx,
        literal -> {
          String text = literal.getText();
          if (text.contains(".") || text.contains("e") || text.contains("E")) {
            return Value.doubleValue(Double.parseDouble(text));
          }
          return Value.intValue(Integer.parseInt(text));
        });
  }

  /**
   * Evaluates string literals.
   *
   * <p><b>Example:</b> {@code 'hello'} → singleton string value
   *
   * @param ctx The string literal node
   * @return A singleton string value
   */
  @Override
  public Value visitStringLit(VitruvOCLParser.StringLitContext ctx) {
    return literalValues.computeIfAbsent(
        ctx,
        literal -> {
          String text = literal.getText();
          return Value.stringValue(text.substring(1, text.length() - 1)); // Remove quotes
        });
  }

  /**
   * Evaluates boolean literals.
   *
   * <p><b>Example:</b> {@code true} → singleton boolean value
   *
   * @param ctx The boolean literal node
   * @return A singleton boolean value
   */
  @Override
  public Value visitBooleanLit(VitruvOCLParser.BooleanLitContext ctx) {
    String text = ctx.getText();
    boolean value = text.equals("true");
    return Value.boolValue(value);
  }

  // ==================== Variable Handling ====================

  /**
   * Evaluates {@code self} references.
   *
   * <p>Returns the value bound to the special {@code self} variable, which represents the current
   * context instance in invariants.
   *
   * @param ctx The self expression node
   * @return The current context instance
   */
  @Override
  public Value visitSelfExpCS(VitruvOCLParser.SelfExpCSContext ctx) {
    VariableSymbol selfSymbol = symbolTable.resolveVariable("self");

    if (selfSymbol == null) {
      return Value.empty(Type.ANY);
    }

    Value selfValue = environment.valueOf(selfSymbol);

    if (selfValue == null) {
      return Value.empty(Type.ANY);
    }

    return selfValue;
  }

  /**
   * Evaluates variable declarations in let expressions.
   *
   * <p>Binds the variable to its initializer value in the symbol table.
   *
   * @param ctx The variable declaration node
   * @return The initializer value
   */
  @Override
  public Value visitVariableDeclaration(VitruvOCLParser.VariableDeclarationContext ctx) {
    // Captured *before* evaluating varInit, and used directly (bypassing
    // symbolTable.defineVariable()'s dependence on "whatever the current scope now is"): if
    // varInit itself forces materialization of an outer, already-existing lazy Value (e.g. an
    // enclosing let-bound or iterator-bound collection), symbolTable's current scope can drift
    // away from this declaration's actual scope by the time visit(ctx.varInit) returns - see
    // EvaluationVisitor#exitScope(Scope) for the general explanation of why laziness makes this
    // distinction load-bearing. Empirically confirmed: reverting this and the corresponding fix
    // in visitLetExpCS makes LazyChainRegressionTest's shadowing test resolve a variable from the
    // wrong enclosing scope (111 instead of 222).
    Scope declaringScope = symbolTable.getCurrentScope();
    String varName = ctx.varName.getText();
    Value initValue = visit(ctx.varInit);

    if (initValue == null) {
      return Value.empty(Type.ANY);
    }

    Type varType = nodeTypes.get(ctx);
    if (varType == null) {
      varType = nodeTypes.get(ctx.varInit);
      if (varType == null) {
        varType = Type.ANY;
      }
    }

    VariableSymbol varSymbol = new VariableSymbol(varName, varType, declaringScope, false);
    environment.bind(varSymbol, initValue);
    declaringScope.defineVariable(varSymbol);

    return initValue;
  }

  // ==================== Nested Expressions ====================

  /**
   * Evaluates nested (parenthesized) expressions.
   *
   * <p><b>Example:</b> {@code (1 + 2) * 3} - the {@code (1 + 2)} part is a nested expression.
   *
   * @param ctx The nested expression node
   * @return The value of the last expression in the parentheses
   */
  @Override
  public Value visitNestedExpCS(VitruvOCLParser.NestedExpCSContext ctx) {
    List<VitruvOCLParser.ExpCSContext> exps = ctx.expCS();

    if (exps.isEmpty()) {
      return Value.empty(Type.ANY);
    }

    Value result = null;
    for (VitruvOCLParser.ExpCSContext exp : exps) {
      result = visit(exp);
    }

    return result;
  }

  // ==================== Delegation & Default Implementations
  // ====================

  /**
   * Evaluates specification nodes (OCL constraint bodies).
   *
   * @param ctx The specification node
   * @return The value of the last expression
   */
  @Override
  public Value visitSpecificationCS(VitruvOCLParser.SpecificationCSContext ctx) {
    Value result = null;
    for (VitruvOCLParser.ExpCSContext exp : ctx.expCS()) {
      result = visit(exp);
    }
    return result != null ? result : Value.empty(Type.ERROR);
  }

  /** Delegates to infixedExpCS. */
  @Override
  public Value visitExpCS(VitruvOCLParser.ExpCSContext ctx) {
    return visit(ctx.infixedExpCS());
  }

  /** Delegates to prefixedExpCS. */
  @Override
  public Value visitPrefixedExpr(VitruvOCLParser.PrefixedExprContext ctx) {
    return visit(ctx.prefixedExpCS());
  }

  /** Delegates to literalExpCS. */
  @Override
  public Value visitLiteral(VitruvOCLParser.LiteralContext ctx) {
    return visit(ctx.literalExpCS());
  }

  /** Delegates to ifExpCS. */
  @Override
  public Value visitConditional(VitruvOCLParser.ConditionalContext ctx) {
    return visit(ctx.ifExpCS());
  }

  /**
   * Evaluates a {@code let} expression, binding local variables before evaluating the body.
   *
   * <p>Opens a fresh {@link LocalScope}, evaluates all variable declarations in order (each binding
   * is immediately visible to subsequent declarations and the body), then evaluates each body
   * expression in sequence. The value of the last body expression is returned as the result of the
   * {@code let} expression.
   *
   * <p>Example: {@code let x : Integer = 5, y : Integer = x * 2 in x + y}
   *
   * @param ctx the parse tree node for the {@code let} expression, containing the variable
   *     declaration list and one or more body expressions
   * @return the value of the last body expression evaluated within the let scope; or an error value
   *     if the body contains no expressions
   */
  @Override
  public Value visitLetExpCS(VitruvOCLParser.LetExpCSContext ctx) {
    LocalScope letScope = new LocalScope(symbolTable.getCurrentScope());
    symbolTable.enterScope(letScope);
    try {
      // Re-enter letScope explicitly before every declaration and before every body expression,
      // rather than relying on it having "stayed" current since the top of this method: any one
      // of them may itself force materialization of an outer, already-existing lazy Value (e.g. an
      // enclosing let-bound or iterator-bound collection), which leaves symbolTable's current
      // scope pointing away from letScope by the time visit() returns - see
      // EvaluationVisitor#exitScope(Scope) for why. Each step must start from a known-correct
      // scope regardless of what the previous one did to the current-scope pointer. Empirically
      // confirmed: reverting this (and the corresponding fix in visitVariableDeclaration) makes
      // LazyChainRegressionTest's shadowing test resolve a variable from the wrong enclosing scope
      // (111 instead of 222).
      for (VitruvOCLParser.VariableDeclarationContext varDecl :
          ctx.variableDeclarations().variableDeclaration()) {
        symbolTable.enterScope(letScope);
        visit(varDecl);
      }

      Value result = null;
      for (VitruvOCLParser.ExpCSContext exp : ctx.expCS()) {
        symbolTable.enterScope(letScope);
        result = visit(exp);
      }

      if (result == null) {
        return Value.empty(Type.ANY);
      }
      return result;
    } finally {
      exitScope(letScope);
    }
  }

  /** Delegates to letExpCS. */
  @Override
  public Value visitLetBinding(VitruvOCLParser.LetBindingContext ctx) {
    return visit(ctx.letExpCS());
  }

  /** Delegates to collectionLiteralExpCS. */
  @Override
  public Value visitCollectionLiteral(VitruvOCLParser.CollectionLiteralContext ctx) {
    return visit(ctx.collectionLiteralExpCS());
  }

  /** Delegates to typeLiteralCS. */
  @Override
  public Value visitTypeLiteral(VitruvOCLParser.TypeLiteralContext ctx) {
    return visit(ctx.typeLiteralExpCS());
  }

  /** Delegates to nestedExpCS. */
  @Override
  public Value visitNested(VitruvOCLParser.NestedContext ctx) {
    return visit(ctx.nestedExpCS());
  }

  /** Delegates to selfExpCS. */
  @Override
  public Value visitSelf(VitruvOCLParser.SelfContext ctx) {
    return visit(ctx.selfExpCS());
  }

  /** Delegates to variableExpCS. */
  @Override
  public Value visitVariable(VitruvOCLParser.VariableContext ctx) {
    return visit(ctx.variableExpCS());
  }

  /**
   * Evaluates the correspondence operator {@code obj1 ~ obj2}. See {@link
   * CorrespondenceEvaluation#operator}.
   */
  @Override
  public Value visitCorrespondence(VitruvOCLParser.CorrespondenceContext ctx) {
    return correspondences.operator(ctx);
  }

  // ==================== Correspondence guard short-circuit ====================

  /** Whether {@code operand} is a binary {@code ~} and the fast paths are on. */
  private boolean isCorrespondenceGuard(ParserRuleContext operand) {
    return correspondenceFastPaths && operand instanceof VitruvOCLParser.CorrespondenceContext;
  }

  private static boolean isTrue(Value value) {
    if (value.isEmpty() || value.size() != 1) {
      return false;
    }
    return Boolean.TRUE.equals(value.getElements().get(0).tryGetBool());
  }

  // ==================== Not Yet Implemented Features ====================
  /**
   * Placeholder for message operator (^).
   *
   * <p>Not yet implemented - reports error.
   */
  @Override
  public Value visitMessage(VitruvOCLParser.MessageContext ctx) {
    return Value.empty(Type.ANY);
  }

  // ==================== Type-Related Nodes (No Runtime Value)
  // ====================

  /** Type expressions have no runtime value. */
  @Override
  public Value visitTypeExpCS(VitruvOCLParser.TypeExpCSContext ctx) {
    if (ctx.typeNameExpCS() != null) {
      return visit(ctx.typeNameExpCS());
    }
    if (ctx.typeLiteralCS() != null) {
      return visit(ctx.typeLiteralCS());
    }
    return Value.empty(Type.ERROR);
  }

  /** Type literals have no runtime value. */
  @Override
  public Value visitTypeLiteralCS(VitruvOCLParser.TypeLiteralCSContext ctx) {
    return Value.empty(Type.ANY);
  }

  /** Collection types have no runtime value. */
  @Override
  public Value visitCollectionTypeCS(VitruvOCLParser.CollectionTypeCSContext ctx) {
    Type type = nodeTypes.get(ctx);
    return Value.empty(type != null ? type : Type.ANY);
  }

  /** Collection type identifiers have no runtime value. */
  @Override
  public Value visitCollectionTypeIdentifier(VitruvOCLParser.CollectionTypeIdentifierContext ctx) {
    return Value.empty(Type.ANY);
  }

  /** Primitive types have no runtime value. */
  @Override
  public Value visitPrimitiveTypeCS(VitruvOCLParser.PrimitiveTypeCSContext ctx) {
    return Value.empty(Type.ANY);
  }

  /** Type names have no runtime value. */
  @Override
  public Value visitTypeNameExpCS(VitruvOCLParser.TypeNameExpCSContext ctx) {
    return Value.empty(Type.ANY);
  }

  /** Type literal expressions have no runtime value. */
  @Override
  public Value visitTypeLiteralExpCS(VitruvOCLParser.TypeLiteralExpCSContext ctx) {
    return visit(ctx.typeLiteralCS());
  }

  // ==================== Error States (Require Receiver Context)
  // ====================

  /**
   * Error: navigation chain visited without receiver context.
   *
   * <p>This should never be reached - navigation chains should always be visited via {@link
   * NavigationEvaluation#navigate}.
   */
  @Override
  public Value visitNavigationChainCS(VitruvOCLParser.NavigationChainCSContext ctx) {
    if (ctx.navigationTargetCS() == null) {
      // '@pre' reached without receiver context — see visitPrimaryWithNav
      return Value.empty(Type.ANY);
    }
    return visit(ctx.navigationTargetCS());
  }

  /**
   * Error: property access visited without receiver context.
   *
   * <p>Should be visited via {@link NavigationEvaluation#navigate}.
   */
  @Override
  public Value visitPropertyNav(VitruvOCLParser.PropertyNavContext ctx) {
    return Value.empty(Type.ANY);
  }

  /**
   * Error: operation call visited without receiver context.
   *
   * <p>Should be visited via {@link NavigationEvaluation#navigate}.
   */
  @Override
  public Value visitOperationNav(VitruvOCLParser.OperationNavContext ctx) {
    return Value.empty(Type.ANY);
  }

  /**
   * Error: property access visited without receiver.
   *
   * <p>Should be visited via {@link NavigationEvaluation#navigate}.
   */
  @Override
  public Value visitPropertyAccess(VitruvOCLParser.PropertyAccessContext ctx) {
    return Value.empty(Type.ANY);
  }

  /**
   * Error: lifecycle predicate visited without receiver context.
   *
   * <p>Should be visited via {@link NavigationEvaluation#navigate}.
   */
  @Override
  public Value visitLifecycleNav(VitruvOCLParser.LifecycleNavContext ctx) {
    return Value.empty(Type.ANY);
  }

  // ==================== Object-Lifecycle Predicates ====================

  /** Evaluates {@code OCLisNew}. See {@link TransactionEvaluation#isNew}. */
  @Override
  public Value visitOclIsNewPred(VitruvOCLParser.OclIsNewPredContext ctx) {
    return transactions.isNew(ctx);
  }

  /** Evaluates {@code OCLisModified}. See {@link TransactionEvaluation#isModified}. */
  @Override
  public Value visitOclIsModifiedPred(VitruvOCLParser.OclIsModifiedPredContext ctx) {
    return transactions.isModified(ctx);
  }

  /** Evaluates {@code OCLisDeleted}. See {@link TransactionEvaluation#isDeleted}. */
  @Override
  public Value visitOclIsDeletedPred(VitruvOCLParser.OclIsDeletedPredContext ctx) {
    return transactions.isDeleted(ctx);
  }

  // ==================== Miscellaneous ====================

  /**
   * Processes variable declarations (currently unused).
   *
   * @param ctx The variable declarations node
   * @return Empty value
   */
  @Override
  public Value visitVariableDeclarations(VitruvOCLParser.VariableDeclarationsContext ctx) {
    for (VitruvOCLParser.VariableDeclarationContext varDecl : ctx.variableDeclaration()) {
      visit(varDecl);
    }
    return Value.empty(Type.ANY);
  }

  /** Whitespace node - no value. */
  @Override
  public Value visitOnespace(VitruvOCLParser.OnespaceContext ctx) {
    return Value.empty(Type.ANY);
  }

  /** Evaluates {@code asSet()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitAsSetOp(VitruvOCLParser.AsSetOpContext ctx) {
    return collections.asSet(ctx);
  }

  /** Evaluates {@code asBag()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitAsBagOp(VitruvOCLParser.AsBagOpContext ctx) {
    return collections.asBag(ctx);
  }

  /** Evaluates {@code asSequence()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitAsSequenceOp(VitruvOCLParser.AsSequenceOpContext ctx) {
    return collections.asSequence(ctx);
  }

  /** Evaluates {@code asOrderedSet()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitAsOrderedSetOp(VitruvOCLParser.AsOrderedSetOpContext ctx) {
    return collections.asOrderedSet(ctx);
  }

  /** Evaluates {@code at()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitAtOp(VitruvOCLParser.AtOpContext ctx) {
    return collections.at(ctx);
  }

  /** Evaluates {@code subSequence()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitSubSequenceOp(VitruvOCLParser.SubSequenceOpContext ctx) {
    return collections.subSequence(ctx);
  }

  /** Evaluates {@code characters()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitCharactersOp(VitruvOCLParser.CharactersOpContext ctx) {
    return strings.characters(ctx);
  }

  /** Evaluates {@code tokenize()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitTokenizeOp(VitruvOCLParser.TokenizeOpContext ctx) {
    return strings.tokenize(ctx);
  }

  /** Evaluates {@code intersection()}; see {@link CollectionOperationEvaluation}. */
  @Override
  public Value visitIntersectionOp(VitruvOCLParser.IntersectionOpContext ctx) {
    return collections.intersection(ctx);
  }

  /** Evaluates {@code toInteger()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitToIntegerOp(VitruvOCLParser.ToIntegerOpContext ctx) {
    return strings.toInteger(ctx);
  }

  /** Evaluates {@code toReal()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitToRealOp(VitruvOCLParser.ToRealOpContext ctx) {
    return strings.toReal(ctx);
  }

  /** Evaluates {@code substituteAll()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitSubstituteAllOp(VitruvOCLParser.SubstituteAllOpContext ctx) {
    return strings.substituteAll(ctx);
  }

  /** Evaluates {@code substituteFirst()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitSubstituteFirstOp(VitruvOCLParser.SubstituteFirstOpContext ctx) {
    return strings.substituteFirst(ctx);
  }

  /** Evaluates {@code matches()}; see {@link StringOperationEvaluation}. */
  @Override
  public Value visitMatchesOp(VitruvOCLParser.MatchesOpContext ctx) {
    return strings.matches(ctx);
  }

  /**
   * Evaluates {@code select(~)} with optional Type/Tag filters. See {@link
   * CorrespondenceEvaluation#select}.
   */
  @Override
  public Value visitSelectCorrespondence(VitruvOCLParser.SelectCorrespondenceContext ctx) {
    return correspondences.select(ctx);
  }

  /**
   * Evaluates {@code reject(~)} with optional Type/Tag filters. See {@link
   * CorrespondenceEvaluation#reject}.
   */
  @Override
  public Value visitRejectCorrespondence(VitruvOCLParser.RejectCorrespondenceContext ctx) {
    return correspondences.reject(ctx);
  }

  /**
   * Evaluates {@code exists(~)} with optional Type/Tag filters. See {@link
   * CorrespondenceEvaluation#exists}.
   */
  @Override
  public Value visitExistsCorrespondence(VitruvOCLParser.ExistsCorrespondenceContext ctx) {
    return correspondences.exists(ctx);
  }

  /** Exposes this visitor's state to the operation-family evaluators. */
  private final class EvaluationContext implements OperationEvaluationContext {
    @Override
    public Value receiver() {
      return receiverStack.peek();
    }

    @Override
    public Value evaluate(ParserRuleContext expression) {
      return visit(expression);
    }

    @Override
    public Value evaluateWithReceiver(Value receiver, ParserRuleContext node) {
      receiverStack.push(receiver);
      try {
        return visit(node);
      } finally {
        receiverStack.pop();
      }
    }

    @Override
    public Value evaluateChildrenWithReceiver(Value receiver, ParserRuleContext node) {
      receiverStack.push(receiver);
      try {
        return visitChildren(node);
      } finally {
        receiverStack.pop();
      }
    }

    @Override
    public void error(ParserRuleContext ctx, String message) {
      reportError(ctx, message);
    }

    @Override
    public boolean mayReportErrors(ParserRuleContext expression) {
      return !errorFreeAnalysis.cannotReportErrors(expression);
    }

    @Override
    public Type nodeType(ParserRuleContext ctx) {
      return nodeTypes.get(ctx);
    }

    @Override
    public SymbolTable symbolTable() {
      return symbolTable;
    }

    @Override
    public Environment environment() {
      return environment;
    }
  }
}
