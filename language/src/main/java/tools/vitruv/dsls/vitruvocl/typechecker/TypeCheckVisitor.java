package tools.vitruv.dsls.vitruvocl.typechecker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.AbstractPhaseVisitor;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.EditDistance;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.ErrorSeverity;
import tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor;
import tools.vitruv.dsls.vitruvocl.operations.OclOperation;
import tools.vitruv.dsls.vitruvocl.operations.OclOperations;
import tools.vitruv.dsls.vitruvocl.pipeline.MetamodelWrapperInterface;
import tools.vitruv.dsls.vitruvocl.symboltable.Scope;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.Symbol;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;

/**
 * Phase 2 visitor that performs static type checking on OCL expressions.
 *
 * <p>This visitor implements the <b>type checking phase</b> of the VitruvOCL compiler pipeline,
 * operating after symbol table construction (Phase 1) and before evaluation (Phase 3). It validates
 * type rules, ensures type safety, and annotates the parse tree with type information.
 *
 * <h2>Core Responsibilities</h2>
 *
 * <ul>
 *   <li><b>Type inference:</b> Determines the type of each expression in the parse tree
 *   <li><b>Type checking:</b> Validates that operations are applied to compatible types
 *   <li><b>Type annotation:</b> Stores computed types in {@code nodeTypes} for use by the evaluator
 *   <li><b>Error reporting:</b> Collects type errors with source location information
 *   <li><b>Scope management:</b> Handles variable scoping for let-expressions and iterators
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <p>The type checker uses a {@code receiverStack} to track receiver types during navigation chain
 * type checking. For example, in {@code person.company.employees}, the stack helps propagate types
 * through the chain:
 *
 * <ol>
 *   <li>Push {@code Person} type
 *   <li>Check {@code .company} → push {@code Company} type
 *   <li>Check {@code .employees} → push {@code Set(Employee)} type
 * </ol>
 *
 * <h2>Type System Features</h2>
 *
 * <ul>
 *   <li>"Everything is a collection" - singletons are {@code Collection(T,1,1)}
 *   <li><b>Collection types:</b> Set, Bag, Sequence, OrderedSet with element types
 *   <li><b>Primitive types:</b> Integer, String, Boolean, Real (Double)
 *   <li><b>Metaclass types:</b> Types representing EMF EClasses
 *   <li><b>Type conformance:</b> Subtype relationships and type compatibility checking
 *   <li><b>Implicit operations:</b> Implicit collect on collections during property access
 * </ul>
 *
 * <h2>Error Handling</h2>
 *
 * <p>Type errors are collected in the {@link ErrorCollector} with severity levels (ERROR, WARNING).
 * The visitor continues checking after errors to report multiple issues in one pass. Errors
 * include:
 *
 * <ul>
 *   <li>Type mismatches in operations (e.g., {@code 5 + "hello"})
 *   <li>Unknown properties or operations
 *   <li>Incompatible types in if-then-else branches
 *   <li>Invalid receiver types for operations
 *   <li>Variable redefinition in scopes
 * </ul>
 *
 * <h2>Usage in Pipeline</h2>
 *
 * <pre>{@code
 * SymbolTable symbolTable = symbolTableBuilder.build(parseTree);
 * TypeCheckVisitor typeChecker = new TypeCheckVisitor(
 *     symbolTable, metamodelWrapper, errorCollector);
 * typeChecker.setTokenStream(tokens);
 * typeChecker.visit(parseTree);
 *
 * if (!errorCollector.hasErrors()) {
 *     ParseTreeProperty<Type> nodeTypes = typeChecker.getNodeTypes();
 *     // Pass to evaluation phase
 * }
 * }</pre>
 *
 * @see Type The type system implementation
 * @see TypeResolver Helper class for binary operation type resolution
 * @see EvaluationVisitor Phase 3 visitor that uses the type information
 */
public class TypeCheckVisitor extends AbstractPhaseVisitor<Type> {

  // ==================== Instance Fields ====================

  /**
   * Stack of receiver types for navigation chain type checking.
   *
   * <p>During navigation like {@code a.b.c()}, this stack tracks the type at each step:
   *
   * <ul>
   *   <li>Push type of {@code a}
   *   <li>Check {@code .b} using type from stack, push result type
   *   <li>Check {@code .c()} using type from stack
   * </ul>
   *
   * <p>This allows operation visitor methods to access their receiver type via {@code
   * receiverStack.peek()}.
   */
  private final Deque<Type> receiverStack = new ArrayDeque<>();

  /**
   * Which kind of constraint block ({@code inv}/{@code pre}/{@code post}) is currently being
   * type-checked. Used to restrict {@code @pre} and the {@code OCLisNew}/{@code OCLisModified}/
   * {@code OCLisDeleted} lifecycle predicates to {@code post} blocks only. Set at the start of
   * {@link #visitInvCS}/{@link #visitPreCS}/{@link #visitPostCS}; these blocks never nest, so a
   * single field (rather than a stack) is sufficient.
   */
  private enum ConstraintSectionKind {
    INV,
    PRE,
    POST
  }

  private ConstraintSectionKind currentSection = ConstraintSectionKind.INV;

  /**
   * Maps parse tree nodes to their computed types.
   *
   * <p>This property is populated during type checking and retrieved by {@link #getNodeTypes()} for
   * use in the evaluation phase. The evaluator uses these pre-computed types for type-dependent
   * operations.
   */
  private final ParseTreeProperty<Type> nodeTypes = new ParseTreeProperty<>();

  /** Variables whose initializer is being checked; referencing them there is an error. */
  private final Map<VariableSymbol, Boolean> initializing = new IdentityHashMap<>();

  /** Type rules of the String operations, see {@link StringOperationTyping}. */
  private final StringOperationTyping strings =
      new StringOperationTyping(new TypingContext());

  /** Type rules of the numeric operations, see {@link NumericOperationTyping}. */
  private final NumericOperationTyping numerics =
      new NumericOperationTyping(new TypingContext());

  /** Type rules of the collection operations, see {@link CollectionOperationTyping}. */
  private final CollectionOperationTyping collections =
      new CollectionOperationTyping(new TypingContext());

  /** Type rules of the iterator operations, see {@link IteratorOperationTyping}. */
  private final IteratorOperationTyping iterators =
      new IteratorOperationTyping(new TypingContext());

  /**
   * Scope annotator for retrieving scopes created in Pass 1.
   *
   * <p>Pass 1 (SymbolTableBuilder) annotates parse tree nodes with their scopes. Pass 2 (this
   * visitor) retrieves these annotations to enter the correct scopes.
   */
  private final ScopeAnnotator scopeAnnotator;

  /**
   * Token stream for accessing keyword positions.
   *
   * <p>Used in {@link #visitIfExpCS} to determine which expressions belong to condition,
   * then-branch, and else-branch based on keyword positions.
   */
  private org.antlr.v4.runtime.TokenStream tokens;

  // ==================== Constructor ====================

  /**
   * Constructs a TypeCheckVisitor for Phase 2 of the compilation pipeline.
   *
   * @param st The symbol table containing variable and type definitions from Phase 1
   * @param wrapper The metamodel wrapper providing access to ECore metamodel information
   * @param errors The error collector for reporting type errors
   * @param scopeAnnotator The scope annotator containing scope annotations from Phase 1
   */
  public TypeCheckVisitor(
      SymbolTable st,
      MetamodelWrapperInterface wrapper,
      ErrorCollector errors,
      ScopeAnnotator scopeAnnotator) {
    super(st, wrapper, errors);
    this.scopeAnnotator = scopeAnnotator;
  }

  // ==================== Error Reporting ====================

  /**
   * Handles undefined symbol errors.
   *
   * <p>Currently throws {@link UnsupportedOperationException} - should be implemented to report
   * errors properly.
   *
   * @param name The undefined symbol name
   * @param ctx The parse tree context where the error occurred
   */
  /* OCL keywords that look like variable names and are commonly mistyped. */
  private static final java.util.List<String> KEYWORD_LIKE_NAMES =
      java.util.List.of("self", "true", "false");

  // ==================== String Constants ====================

  private static final String PHASE_TAG = "type-checker";
  static final String ERR_NO_SCOPE = "Internal error: No scope annotation from Pass 1";
  private static final String ERR_TYPE_MISMATCH_PREFIX = "Type mismatch: cannot apply '";
  private static final String ERR_TO = "' to ";
  private static final String ERR_DID_YOU_MEAN = "' — did you mean '";
  private static final String AND_SEPARATOR = " and ";
  private static final String ERR_ORDERING_TYPES =
      "Ordering comparison requires numeric or String types, got: ";
  private static final String ERR_BRANCH_TYPES = "Incompatible branch types: ";
  private static final String ERR_UNKNOWN_OP = "Unknown operation '";
  private static final String OP_SELECT = "select";
  private static final String OP_REJECT = "reject";
  private static final String OP_EXISTS = "exists";
  private static final String OP_AS_SET = "asSet";
  private static final String OP_AS_BAG = "asBag";
  private static final String OP_AS_SEQUENCE = "asSequence";
  private static final String OP_AS_ORDERED_SET = "asOrderedSet";
  private static final String TYPE_BOOLEAN = "Boolean";
  private static final String TYPE_INTEGER = "Integer";
  private static final String TYPE_STRING = "String";
  private static final String ERR_OP_DOES_NOT_EXIST = "' — this operation does not exist";

  @Override
  protected String phaseTag() {
    return PHASE_TAG;
  }

  @Override
  protected void handleUndefinedSymbol(String name, ParserRuleContext ctx) {
    String lower = name.toLowerCase(java.util.Locale.ROOT);

    // Check against keyword-like names first (self, null, true, false)
    String bestKeyword =
        KEYWORD_LIKE_NAMES.stream()
            .min(
                java.util.Comparator.comparingInt(
                    k ->
                        EditDistance.damerauLevenshtein(
                            lower, k.toLowerCase(java.util.Locale.ROOT))))
            .orElse(null);
    int kwDist =
        bestKeyword == null
            ? Integer.MAX_VALUE
            : EditDistance.damerauLevenshtein(
                lower, bestKeyword.toLowerCase(java.util.Locale.ROOT));

    int threshold = EditDistance.editThreshold(name.length());
    String message;
    String suggestion;
    if (kwDist <= threshold) {
      message = "Undefined variable '" + name + ERR_DID_YOU_MEAN + bestKeyword + "'?";
      suggestion = bestKeyword;
    } else {
      message = "Undefined variable: " + name;
      suggestion = null;
    }

    org.antlr.v4.runtime.Token tok = ctx.getStart();
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            suggestion));
  }

  // ==================== Context Declaration ====================

  /**
   * Type checks the top-level context declaration.
   *
   * <p>Processes all classifier contexts (e.g., {@code context Person}, {@code context Company})
   * and returns the type of the last one.
   *
   * @param ctx The context declaration node
   * @return The type of the last classifier context
   */
  @Override
  public Type visitContextDeclCS(VitruvOCLParser.ContextDeclCSContext ctx) {
    Type lastType = Type.ERROR;

    for (VitruvOCLParser.ClassifierContextCSContext classifierCtx : ctx.classifierContextCS()) {
      lastType = visit(classifierCtx);
    }

    nodeTypes.put(ctx, lastType);
    return lastType;
  }

  /**
   * Type checks a classifier context declaration.
   *
   * <p>Enters the scope created by Pass 1 (SymbolTableBuilder) which already contains the 'self'
   * variable.
   *
   * @param ctx The classifier context node
   * @return The context type (metaclass type)
   */
  @Override
  public Type visitClassifierContextCS(VitruvOCLParser.ClassifierContextCSContext ctx) {
    // Resolve context type (qualified or unqualified)
    Type contextType;
    if (ctx.metamodel != null && ctx.className != null) {
      // Qualified: metamodel::ClassName
      String qualifiedName = ctx.metamodel.getText() + "::" + ctx.className.getText();
      contextType = symbolTable.lookupType(qualifiedName);
    } else if (ctx.contextName != null) {
      // Unqualified: ClassName
      contextType = symbolTable.lookupType(ctx.contextName.getText());
    } else {
      reportError(ctx, "Invalid context declaration");
      return Type.ERROR;
    }

    if (contextType == null) {
      return Type.ERROR; // Pass 1 already reported a precise error for this token
    }

    nodeTypes.put(ctx, contextType);

    // Enter scope created by Pass 1 - scope already contains 'self' variable
    Scope contextScope = scopeAnnotator.getScope(ctx);
    if (contextScope == null) {
      return Type.ERROR; // Pass 1 failed for this context — bail out silently
    }

    symbolTable.enterScope(contextScope);

    try {
      // Type-check all invariants
      for (VitruvOCLParser.InvCSContext inv : ctx.invCS()) {
        visit(inv);
      }

      // Cardinality: at most one pre/post per context (not enforced by the grammar — mirrors the
      // @severity/@message duplicate check below).
      if (ctx.preCS().size() > 1) {
        reportError(ctx, "'pre' may only appear once per context");
      }
      if (ctx.postCS().size() > 1) {
        reportError(ctx, "'post' may only appear once per context");
      }
      for (VitruvOCLParser.PreCSContext pre : ctx.preCS()) {
        visit(pre);
      }
      for (VitruvOCLParser.PostCSContext post : ctx.postCS()) {
        visit(post);
      }
      return contextType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks an invariant constraint.
   *
   * <p>Invariants must evaluate to Boolean type. This method checks all specification expressions
   * and validates that they are conformant to Boolean.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * inv: self.age >= 0        // Must be Boolean
   * inv: self.name.size() > 0 // Must be Boolean
   * }</pre>
   *
   * @param ctx The invariant node
   * @return Type.BOOLEAN if valid, Type.ERROR otherwise
   */
  @Override
  public Type visitInvCS(VitruvOCLParser.InvCSContext ctx) {
    currentSection = ConstraintSectionKind.INV;
    return checkConstraintBody(ctx, ctx.annotationCS(), ctx.specificationCS(), "Invariant");
  }

  /**
   * Type checks a precondition constraint. Preconditions must evaluate to Boolean, exactly like
   * {@link #visitInvCS}.
   *
   * @param ctx The precondition node
   * @return Type.BOOLEAN if valid, Type.ERROR otherwise
   */
  @Override
  public Type visitPreCS(VitruvOCLParser.PreCSContext ctx) {
    currentSection = ConstraintSectionKind.PRE;
    return checkConstraintBody(ctx, ctx.annotationCS(), ctx.specificationCS(), "Precondition");
  }

  /**
   * Type checks a postcondition constraint. Postconditions must evaluate to Boolean, exactly like
   * {@link #visitInvCS}. {@code @pre} and the {@code OCLisNew}/{@code OCLisModified}/{@code
   * OCLisDeleted} lifecycle predicates are only valid here (see {@link #currentSection}).
   *
   * @param ctx The postcondition node
   * @return Type.BOOLEAN if valid, Type.ERROR otherwise
   */
  @Override
  public Type visitPostCS(VitruvOCLParser.PostCSContext ctx) {
    currentSection = ConstraintSectionKind.POST;
    return checkConstraintBody(ctx, ctx.annotationCS(), ctx.specificationCS(), "Postcondition");
  }

  /**
   * Shared body of {@link #visitInvCS}/{@link #visitPreCS}/{@link #visitPostCS}: validates
   * {@code @severity}/{@code @message} annotation cardinality, then checks that every
   * specification expression is Boolean-conformant.
   *
   * @param ctx the constraint node (inv/pre/post), used for error positions and the nodeTypes key
   * @param annotationCtxs the constraint's {@code @severity}/{@code @message} annotations
   * @param specs the constraint's specification expressions
   * @param kindLabel human-readable label for error messages ("Invariant"/"Precondition"/
   *     "Postcondition")
   * @return Type.BOOLEAN if valid, Type.ERROR otherwise
   */
  @SuppressWarnings("java:S3776")
  private Type checkConstraintBody(
      ParserRuleContext ctx,
      List<VitruvOCLParser.AnnotationCSContext> annotationCtxs,
      List<VitruvOCLParser.SpecificationCSContext> specs,
      String kindLabel) {
    // Check for duplicate @severity / @message annotations
    long severityCount =
        annotationCtxs.stream()
            .filter(a -> a instanceof VitruvOCLParser.SeverityAnnotationContext)
            .count();
    long messageCount =
        annotationCtxs.stream()
            .filter(a -> a instanceof VitruvOCLParser.MessageAnnotationContext)
            .count();
    if (severityCount > 1) {
      reportError(ctx, "@severity may only appear once per constraint");
    }
    if (messageCount > 1) {
      reportError(ctx, "@message may only appear once per constraint");
    }
    // Validate annotations before type-checking the body
    for (VitruvOCLParser.AnnotationCSContext ann : annotationCtxs) {
      visit(ann);
    }

    Type resultType = Type.BOOLEAN;

    for (VitruvOCLParser.SpecificationCSContext spec : specs) {
      int errorsBefore = errors.getErrors().size();
      Type specType = visit(spec);
      boolean newErrorsInSpec = errors.getErrors().size() > errorsBefore;

      // If the spec already has errors, an error node, or produced new errors, skip further checks.
      if (specType.equals(Type.ERROR) || hasErrorNode(spec) || newErrorsInSpec) {
        // skip — would only produce misleading follow-up diagnostics
      } else if (reportMissingOperandAfter(spec)) {
        // Spec was cut short by a missing operand (operator token follows immediately)
        resultType = Type.ERROR;
      } else if (reportMissingOperatorBetweenExpressions(spec)) {
        // Forgotten logical operator between two expressions
        resultType = Type.ERROR;
      } else {
        // Check Boolean conformance (handles both Boolean and !Boolean!)
        Type checkType =
            specType.isSingleton() || specType.isCollection()
                ? specType.getElementType()
                : specType;
        if (!checkType.isConformantTo(Type.BOOLEAN)) {
          reportError(ctx, kindLabel + " must be Boolean, got " + specType);
          resultType = Type.ERROR;
        }
      }
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== Annotations ====================

  private static final java.util.Set<String> VALID_SEVERITIES =
      java.util.Set.of("CRITICAL", "WARNING", "MAJOR", "MINOR", "INFO");

  @Override
  public Type visitSeverityAnnotation(VitruvOCLParser.SeverityAnnotationContext ctx) {
    String val = ctx.severityValue.getText();
    if (!VALID_SEVERITIES.contains(val)) {
      reportError(
          ctx,
          "Unknown severity level '"
              + val
              + "'. Valid values: CRITICAL, WARNING, MAJOR, MINOR, INFO");
    }
    return Type.BOOLEAN;
  }

  @Override
  public Type visitMessageAnnotation(VitruvOCLParser.MessageAnnotationContext ctx) {
    return Type.BOOLEAN;
  }

  // ==================== Type Expressions ====================

  /**
   * Type checks a type expression.
   *
   * <p>Type expressions represent types in declarations and type operations. Delegates to either
   * type name or type literal checking.
   *
   * @param ctx The type expression node
   * @return The resolved type
   */
  @Override
  public Type visitTypeExpCS(VitruvOCLParser.TypeExpCSContext ctx) {
    if (ctx.typeNameExpCS() != null) {
      return visitTypeNameExpCS(ctx.typeNameExpCS());
    }
    if (ctx.typeLiteralCS() != null) {
      return visitTypeLiteralCS(ctx.typeLiteralCS());
    }

    reportError(ctx, "Invalid type expression");
    return Type.ERROR;
  }

  /**
   * Type checks a type literal (primitive or collection type).
   *
   * @param ctx The type literal node
   * @return The resolved type
   */
  @Override
  public Type visitTypeLiteralCS(VitruvOCLParser.TypeLiteralCSContext ctx) {
    if (ctx.primitiveTypeCS() != null) {
      return visitPrimitiveTypeCS(ctx.primitiveTypeCS());
    }
    if (ctx.collectionTypeCS() != null) {
      return visit(ctx.collectionTypeCS());
    }

    reportError(ctx, "Invalid type literal");
    return Type.ERROR;
  }

  /**
   * Type checks a collection type declaration.
   *
   * <p>Constructs collection types from their syntax. Supports:
   *
   * <ul>
   *   <li>{@code Set(T)} - unordered, unique
   *   <li>{@code Bag(T)} - unordered, non-unique
   *   <li>{@code Sequence(T)} - ordered, non-unique
   *   <li>{@code OrderedSet(T)} - ordered, unique
   *   <li>{@code Collection(T)} - generic (defaults to Set)
   * </ul>
   *
   * <p><b>Example:</b> {@code Set(Integer)}, {@code Sequence(Person)}
   *
   * @param ctx The collection type node
   * @return The constructed collection type
   */
  @Override
  public Type visitCollectionTypeCS(VitruvOCLParser.CollectionTypeCSContext ctx) {
    String kind = ctx.collectionKind.getText();

    // Get element type if specified
    Type elementType = Type.ANY; // Default placeholder
    if (ctx.typeExpCS() != null) {
      elementType = visit(ctx.typeExpCS());
      if (elementType == Type.ERROR) {
        return Type.ERROR;
      }
    }

    Type collectionType = switch (kind) {
      case "Set" -> Type.set(elementType);
      case "Sequence" -> Type.sequence(elementType);
      case "Bag" -> Type.bag(elementType);
      case "OrderedSet" -> Type.orderedSet(elementType);
      case "Collection" -> Type.set(elementType); // Generic → Set
      default -> {
        reportError(ctx, "Unknown collection type: " + kind);
        yield Type.ERROR;
      }
    };

    nodeTypes.put(ctx, collectionType);
    return collectionType;
  }

  /**
   * Handles collection type identifiers (lexical tokens).
   *
   * <p>Not used for actual type construction - parent {@link #visitCollectionTypeCS} handles that.
   *
   * @param ctx The collection type identifier node
   * @return Type.ANY (placeholder)
   */
  @Override
  public Type visitCollectionTypeIdentifier(VitruvOCLParser.CollectionTypeIdentifierContext ctx) {
    return Type.ANY; // Placeholder, not used
  }

  /**
   * Type checks a primitive type reference.
   *
   * <p>Maps OCL primitive type names to internal {@link Type} constants:
   *
   * <ul>
   *   <li>Boolean → {@link Type#BOOLEAN}
   *   <li>Integer → {@link Type#INTEGER}
   *   <li>Real → {@link Type#DOUBLE}
   *   <li>String → {@link Type#STRING}
   *   <li>ID → {@link Type#STRING} (mapped)
   *   <li>UnlimitedNatural → {@link Type#INTEGER} (mapped)
   *   <li>OclAny → {@link Type#ANY}
   * </ul>
   *
   * @param ctx The primitive type node
   * @return The corresponding primitive type
   */
  @Override
  public Type visitPrimitiveTypeCS(VitruvOCLParser.PrimitiveTypeCSContext ctx) {
    String typeName = ctx.getText();

    Type primitiveType = Type.fromPrimitiveKeyword(typeName);

    if (primitiveType == null) {
      reportError(ctx, "Unknown primitive type: " + typeName);
      return Type.ERROR;
    }

    nodeTypes.put(ctx, primitiveType);
    return primitiveType;
  }

  /**
   * Type checks a type name expression (qualified or unqualified).
   *
   * <p>Resolves type names to their corresponding types. Handles:
   *
   * <ul>
   *   <li>Primitive types: Integer, String, Boolean, Real
   *   <li>Metamodel types (qualified): {@code metamodel::ClassName}
   *   <li>Metamodel types (unqualified): {@code ClassName}
   * </ul>
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code Integer} → Type.INTEGER
   *   <li>{@code Person} → metaclass type for Person
   *   <li>{@code company::Employee} → metaclass type for Employee
   * </ul>
   *
   * @param ctx The type name expression node
   * @return The resolved type
   */
  @Override
  public Type visitTypeNameExpCS(VitruvOCLParser.TypeNameExpCSContext ctx) {
    String typeName;

    if (ctx.metamodel != null && ctx.className != null) {
      typeName = ctx.metamodel.getText() + "::" + ctx.className.getText();
    } else if (ctx.unqualified != null) {
      typeName = ctx.unqualified.getText();
    } else {
      reportError(ctx, "Invalid type name");
      return Type.ERROR;
    }

    // Primitive names (Double, Float, ...) and metamodel types; see SymbolTable#lookupType
    Type resolvedType = symbolTable.lookupType(typeName);
    if (resolvedType == null) {
      reportError(ctx, "Unknown type: " + typeName);
      return Type.ERROR;
    }

    nodeTypes.put(ctx, resolvedType);
    return resolvedType;
  }

  // ==================== Specifications & Expressions ====================

  /**
   * Type checks a specification (OCL constraint body).
   *
   * <p>Evaluates all expressions in sequence and returns the type of the last one.
   *
   * @param ctx The specification node
   * @return The type of the last expression
   */
  @Override
  public Type visitSpecificationCS(VitruvOCLParser.SpecificationCSContext ctx) {
    if (ctx.expCS().isEmpty()) {
      reportError(ctx, "Empty specification");
      return Type.ERROR;
    }

    // Multiple expCS children means expressions were written without an operator between them.
    // e.g.  self.a == "x"  persons::Person.allInstances().exists(...)
    // Report on the second (unexpected) expression's first token.
    if (ctx.expCS().size() > 1) {
      for (int i = 1; i < ctx.expCS().size(); i++) {
        org.antlr.v4.runtime.Token bad = ctx.expCS().get(i).getStart();
        errors.add(
            new CompileError(
                bad.getLine(),
                bad.getCharPositionInLine(),
                bad.getLine(),
                bad.getCharPositionInLine() + bad.getText().length(),
                "Missing logical operator before this expression — did you mean 'and', 'or', or"
                    + " 'implies'?",
                ErrorSeverity.ERROR,
                PHASE_TAG,
                null));
      }
      return Type.ERROR;
    }

    Type resultType = null;
    boolean hasError = false;
    for (VitruvOCLParser.ExpCSContext exp : ctx.expCS()) {
      Type t = visit(exp);
      if (t != null && t.equals(Type.ERROR)) {
        hasError = true;
      }
      resultType = t;
    }

    if (hasError) {
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Delegates expression type checking to infixed expression.
   *
   * @param ctx The expression node
   * @return The expression type
   */
  @Override
  public Type visitExpCS(VitruvOCLParser.ExpCSContext ctx) {
    return visit(ctx.infixedExpCS());
  }

  // ==================== Comparison Operations ====================

  /**
   * Type checks equality comparison (==).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 5 == 10} → Boolean
   *
   * @param ctx The equality comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  /* Reports a type error spanning the full binary expression [left..right]. */
  private void addBinaryTypeError(
      org.antlr.v4.runtime.ParserRuleContext left,
      org.antlr.v4.runtime.ParserRuleContext right,
      String message) {
    org.antlr.v4.runtime.Token s = left.getStart();
    org.antlr.v4.runtime.Token e = right.getStop() != null ? right.getStop() : left.getStop();
    int endLine = e != null ? e.getLine() : s.getLine();
    int endCol =
        e != null
            ? e.getCharPositionInLine() + e.getText().length()
            : s.getCharPositionInLine() + s.getText().length();
    errors.add(
        new CompileError(
            s.getLine(),
            s.getCharPositionInLine(),
            endLine,
            endCol,
            message,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));
  }

  /**
   * Checks that both operands of a binary comparison are present and parse-error free. If not,
   * reports the error on the operator token itself (e.g. the {@code ==} sign) and returns {@code
   * false} so the caller can bail out early with {@code Type.ERROR}.
   */
  private boolean checkBinaryOperands(
      org.antlr.v4.runtime.ParserRuleContext left,
      org.antlr.v4.runtime.Token op,
      org.antlr.v4.runtime.ParserRuleContext right) {
    boolean leftBad = left == null || hasErrorNode(left);
    boolean rightBad = right == null || hasErrorNode(right);
    if (!leftBad && !rightBad) {
      return true; // both OK
    }
    String side;
    if (leftBad && rightBad) {
      side = "both operands are";
    } else if (leftBad) {
      side = "left-hand operand is";
    } else {
      side = "right-hand operand is";
    }

    errors.add(
        new CompileError(
            op.getLine(),
            op.getCharPositionInLine(),
            op.getLine(),
            op.getCharPositionInLine() + op.getText().length(),
            "Missing or invalid operand for '"
                + op.getText()
                + "' — "
                + side
                + " missing or invalid",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));
    return false;
  }

  @Override
  public Type visitEqualityComparison(VitruvOCLParser.EqualityComparisonContext ctx) {
    if (!checkBinaryOperands(ctx.left, ctx.op, ctx.right)) {
      return Type.ERROR;
    }
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;

    if (isMultiValued(leftType) || isMultiValued(rightType)) {
      // the evaluator compares single values only and fails at runtime for collections
      addBinaryTypeError(
          ctx.left,
          ctx.right,
          "Equality requires single values, not collections: "
              + leftType
              + AND_SEPARATOR
              + rightType);
      resultType = Type.ERROR;
    } else if (!areComparable(leftType, rightType)) {
      addBinaryTypeError(
          ctx.left,
          ctx.right,
          "Cannot compare incompatible types: " + leftType + AND_SEPARATOR + rightType);
      resultType = Type.ERROR;
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks inequality comparison (!=).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code "hello" != "world"} → Boolean
   *
   * @param ctx The inequality comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitInequalityComparison(VitruvOCLParser.InequalityComparisonContext ctx) {
    if (!checkBinaryOperands(ctx.left, ctx.op, ctx.right)) {
      return Type.ERROR;
    }
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;

    if (isMultiValued(leftType) || isMultiValued(rightType)) {
      // the evaluator compares single values only and fails at runtime for collections
      addBinaryTypeError(
          ctx.left,
          ctx.right,
          "Equality requires single values, not collections: "
              + leftType
              + AND_SEPARATOR
              + rightType);
      resultType = Type.ERROR;
    } else if (!areComparable(leftType, rightType)) {
      addBinaryTypeError(
          ctx.left,
          ctx.right,
          "Cannot compare incompatible types: " + leftType + AND_SEPARATOR + rightType);
      resultType = Type.ERROR;
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Reports an unsupported two-character operator sequence.
   *
   * <p>Users sometimes write operators from other OCL dialects or from general programming
   * languages that VitruvOCL does not support as a single operator:
   *
   * <ul>
   *   <li>{@code <>} — OCL standard inequality; use {@code !=} instead
   *   <li>{@code ><} — invalid; use {@code !=}, {@code >}, or {@code <}
   *   <li>{@code +-} — invalid; use {@code +} or {@code -} separately
   *   <li>{@code -+} — invalid; use {@code +} or {@code -} separately
   * </ul>
   *
   * <p>The operands are still visited so that further errors in them are reported, but the result
   * type is {@code ERROR} so nothing cascades to the surrounding expression.
   *
   * @param ctx the invalid binary operator node
   * @return {@code Type.ERROR}
   */
  @Override
  public Type visitInvalidBinaryOp(VitruvOCLParser.InvalidBinaryOpContext ctx) {
    // Visit operands so their own errors are reported
    visit(ctx.left);
    visit(ctx.right);

    String op = ctx.op.getText();
    String hint = switch (op) {
      case "<>" -> " — did you mean `!=`? (OCL standard `<>` is not supported)";
      case "><" -> " — did you mean `!=` or a comparison?";
      case "+-" -> " — use `+` or `-` as separate operators";
      case "-+" -> " — use `-` or `+` as separate operators";
      default -> "";
    };

    org.antlr.v4.runtime.Token tok = ctx.op;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            "Invalid operator `" + op + "`" + hint,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));

    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  @Override
  public Type visitSingleEqualsOp(VitruvOCLParser.SingleEqualsOpContext ctx) {
    visit(ctx.left);
    visit(ctx.right);
    // Point at the '=' sign: it sits right after the left operand's last token.
    // We approximate its position from the token stream.
    org.antlr.v4.runtime.Token eq = ctx.getStart(); // fallback
    // Walk children to find the '=' terminal
    for (int i = 0; i < ctx.getChildCount(); i++) {
      org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);
      if (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn) {
        org.antlr.v4.runtime.Token t = tn.getSymbol();
        if ("=".equals(t.getText())) {
          eq = t;
          break;
        }
      }
    }
    errors.add(
        new CompileError(
            eq.getLine(),
            eq.getCharPositionInLine(),
            eq.getLine(),
            eq.getCharPositionInLine() + 1,
            "Use '==' for equality comparison, not '='",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            "=="));
    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  @Override
  public Type visitMultiEqualsOp(VitruvOCLParser.MultiEqualsOpContext ctx) {
    visit(ctx.left);
    visit(ctx.right);
    errors.add(
        new CompileError(
            ctx.op.getLine(),
            ctx.op.getCharPositionInLine(),
            ctx.op.getLine(),
            ctx.op.getCharPositionInLine() + ctx.op.getText().length(),
            "Invalid operator '" + ctx.op.getText() + "' — did you mean '=='?",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            "=="));
    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  private boolean areOrderable(Type t1, Type t2) {
    // Unwrap ¡T! and ¿T? for ordering checks
    Type e1 = (t1.isSingleton() || t1.isOptional()) ? t1.getElementType() : t1;
    Type e2 = (t2.isSingleton() || t2.isOptional()) ? t2.getElementType() : t2;
    if (e1 == Type.ERROR || e2 == Type.ERROR) {
      return true;
    }
    if (e1 == Type.ANY || e2 == Type.ANY) {
      return true;
    }
    return TypeResolver.isNumeric(e1) && TypeResolver.isNumeric(e2);
  }

  /**
   * Type checks less-than comparison (&lt;).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 5 &lt; 10} → Boolean
   *
   * @param ctx The less-than comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitLessThanComparison(VitruvOCLParser.LessThanComparisonContext ctx) {
    if (!checkBinaryOperands(ctx.left, ctx.op, ctx.right)) {
      return Type.ERROR;
    }
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      addBinaryTypeError(
          ctx.left, ctx.right, ERR_ORDERING_TYPES + leftType + AND_SEPARATOR + rightType);
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks less-than-or-equal comparison (&lt;=).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 5 &lt;= 10} → Boolean
   *
   * @param ctx The less-than-or-equal comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitLessThanOrEqualComparison(VitruvOCLParser.LessThanOrEqualComparisonContext ctx) {
    if (!checkBinaryOperands(ctx.left, ctx.op, ctx.right)) {
      return Type.ERROR;
    }
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      addBinaryTypeError(
          ctx.left, ctx.right, ERR_ORDERING_TYPES + leftType + AND_SEPARATOR + rightType);
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks greater-than comparison (>).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 10 > 5} → Boolean
   *
   * @param ctx The greater-than comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitGreaterThanComparison(VitruvOCLParser.GreaterThanComparisonContext ctx) {
    if (!checkBinaryOperands(ctx.left, ctx.op, ctx.right)) {
      return Type.ERROR;
    }
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      addBinaryTypeError(
          ctx.left, ctx.right, ERR_ORDERING_TYPES + leftType + AND_SEPARATOR + rightType);
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks greater-than-or-equal comparison (>=).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 10 >= 5} → Boolean
   *
   * @param ctx The greater-than-or-equal comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitGreaterThanOrEqualComparison(
      VitruvOCLParser.GreaterThanOrEqualComparisonContext ctx) {
    if (!checkBinaryOperands(ctx.left, ctx.op, ctx.right)) {
      return Type.ERROR;
    }
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      addBinaryTypeError(
          ctx.left, ctx.right, ERR_ORDERING_TYPES + leftType + AND_SEPARATOR + rightType);
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /** Whether {@code type} is a collection that may hold more than one element. */
  private static boolean isMultiValued(Type type) {
    return type.isCollection() && type.getUpperBound() != 1;
  }

  /**
   * Checks if two types are comparable.
   *
   * <p>Types are comparable if:
   *
   * <ul>
   *   <li>Either is Type.ERROR (error already reported)
   *   <li>They are equal
   *   <li>One conforms to the other (subtype relationship)
   * </ul>
   *
   * @param t1 First type
   * @param t2 Second type
   * @return true if types are comparable
   */
  @SuppressWarnings("java:S3776")
  private boolean areComparable(Type t1, Type t2) {
    if (t1 == Type.ERROR || t2 == Type.ERROR) {
      return true;
    }
    if (t1.equals(t2)) {
      return true;
    }
    if (t1.isConformantTo(t2) || t2.isConformantTo(t1)) {
      return true;
    }

    // Optional ANY (?Any?) is comparable to anything — represents null
    if ((t1.isOptional() && t1.getElementType() == Type.ANY)
        || (t2.isOptional() && t2.getElementType() == Type.ANY)) {
      return true;
    }

    // Unwrap singletons and optionals for comparison (¡T! and ¿T? both unwrap to T)
    Type e1 = (t1.isSingleton() || t1.isOptional() || t1.isCollection()) ? t1.getElementType() : t1;
    Type e2 = (t2.isSingleton() || t2.isOptional() || t2.isCollection()) ? t2.getElementType() : t2;
    if (!e1.equals(t1) || !e2.equals(t2)) {
      return areComparable(e1, e2);
    }

    return false;
  }

  // ==================== Arithmetic Operations ====================

  /**
   * Type checks multiplicative operations (* and /).
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to determine result type based on operand types.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code 6 * 7} → Integer
   *   <li>{@code 10 / 2} → Integer
   * </ul>
   *
   * @param ctx The multiplicative operation node
   * @return The result type (typically Integer)
   */
  @Override
  public Type visitMultiplicative(VitruvOCLParser.MultiplicativeContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = ctx.op.getText();
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR && leftType != Type.ERROR && rightType != Type.ERROR) {
      reportError(
          ctx, ERR_TYPE_MISMATCH_PREFIX + operator + ERR_TO + leftType + AND_SEPARATOR + rightType);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks additive operations (+ and -).
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to determine result type.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code 3 + 4} → Integer
   *   <li>{@code 10 - 3} → Integer
   * </ul>
   *
   * @param ctx The additive operation node
   * @return The result type (typically Integer)
   */
  @Override
  public Type visitAdditive(VitruvOCLParser.AdditiveContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = ctx.op.getText();
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR && leftType != Type.ERROR && rightType != Type.ERROR) {
      reportError(
          ctx, ERR_TYPE_MISMATCH_PREFIX + operator + ERR_TO + leftType + AND_SEPARATOR + rightType);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== Logical Operations ====================

  /**
   * Type checks logical AND operation.
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to validate Boolean operands and return Boolean
   * result.
   *
   * <p><b>Example:</b> {@code true and false} → Boolean
   *
   * @param ctx The logical AND operation node
   * @return Type.BOOLEAN if operands are Boolean, Type.ERROR otherwise
   */
  @Override
  public Type visitLogicalAnd(VitruvOCLParser.LogicalAndContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = "and";
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR && leftType != Type.ERROR && rightType != Type.ERROR) {
      reportError(
          ctx, ERR_TYPE_MISMATCH_PREFIX + operator + ERR_TO + leftType + AND_SEPARATOR + rightType);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks logical OR operation.
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to validate Boolean operands and return Boolean
   * result.
   *
   * <p><b>Example:</b> {@code true or false} → Boolean
   *
   * @param ctx The logical OR operation node
   * @return Type.BOOLEAN if operands are Boolean, Type.ERROR otherwise
   */
  @Override
  public Type visitLogicalOr(VitruvOCLParser.LogicalOrContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = "or";
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR && leftType != Type.ERROR && rightType != Type.ERROR) {
      reportError(
          ctx, ERR_TYPE_MISMATCH_PREFIX + operator + ERR_TO + leftType + AND_SEPARATOR + rightType);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks logical XOR operation.
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to validate Boolean operands and return Boolean
   * result.
   *
   * <p><b>Example:</b> {@code true xor true} → Boolean
   *
   * @param ctx The logical XOR operation node
   * @return Type.BOOLEAN if operands are Boolean, Type.ERROR otherwise
   */
  @Override
  public Type visitLogicalXor(VitruvOCLParser.LogicalXorContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = "xor";
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR && leftType != Type.ERROR && rightType != Type.ERROR) {
      reportError(
          ctx, ERR_TYPE_MISMATCH_PREFIX + operator + ERR_TO + leftType + AND_SEPARATOR + rightType);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the implication operation (implies).
   *
   * <p>Both operands must be Boolean. Returns Boolean.
   *
   * <p><b>Example:</b> {@code age >= 18 implies canVote == true} → Boolean
   *
   * @param ctx The implication operation node
   * @return Type.BOOLEAN if both operands are Boolean
   */
  @Override
  public Type visitImplication(VitruvOCLParser.ImplicationContext ctx) {
    int errsBefore = errors.getErrors().size();
    Type leftType = visit(ctx.left);
    int errsAfterLeft = errors.getErrors().size();
    Type rightType = visit(ctx.right);
    int errsAfterRight = errors.getErrors().size();

    boolean leftFailed =
        leftType == Type.ERROR || hasErrorNode(ctx.left) || errsAfterLeft > errsBefore;
    boolean rightFailed =
        rightType == Type.ERROR || hasErrorNode(ctx.right) || errsAfterRight > errsAfterLeft;

    // Swallow cascade when the root cause was already reported in either operand.
    if (leftFailed || rightFailed) {
      nodeTypes.put(ctx, Type.BOOLEAN);
      return Type.BOOLEAN;
    }

    if (!leftType.isConformantTo(Type.BOOLEAN)) {
      org.antlr.v4.runtime.Token ls = ctx.left.getStart();
      errors.add(
          new CompileError(
              ls.getLine(),
              ls.getCharPositionInLine(),
              ls.getLine(),
              ls.getCharPositionInLine() + ls.getText().length(),
              "Left operand of 'implies' must be Boolean, got " + leftType,
              ErrorSeverity.ERROR,
              PHASE_TAG,
              null));
    }

    if (!rightType.isConformantTo(Type.BOOLEAN)) {
      // Try to find the unparsed navigation step right after ctx.right that caused the issue
      // (e.g. .reject(~, , , ...) that ANTLR couldn't parse due to extra commas).
      int[] badCallSpan = findUnparsedCorrCallAfter(ctx.right);
      if (badCallSpan.length > 0) {
        org.antlr.v4.runtime.Token opTok = tokens.get(badCallSpan[0]);
        org.antlr.v4.runtime.Token endTok = tokens.get(badCallSpan[1]);
        errors.add(
            new CompileError(
                opTok.getLine(),
                opTok.getCharPositionInLine(),
                endTok.getLine(),
                endTok.getCharPositionInLine() + endTok.getText().length(),
                "'"
                    + opTok.getText()
                    + "(~, ...)' has invalid arguments — check for syntax errors inside the"
                    + " parentheses",
                ErrorSeverity.ERROR,
                PHASE_TAG,
                null));
      } else {
        org.antlr.v4.runtime.Token rs = ctx.right.getStart();
        errors.add(
            new CompileError(
                rs.getLine(),
                rs.getCharPositionInLine(),
                rs.getLine(),
                rs.getCharPositionInLine() + rs.getText().length(),
                "Right operand of 'implies' must be Boolean, got " + rightType,
                ErrorSeverity.ERROR,
                PHASE_TAG,
                null));
      }
    }

    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  // ==================== Unary Operations & Navigation ====================

  /**
   * Visits a primary expression with navigation (`primaryWithNav`) in the AST.
   *
   * <p>This method performs type checking for a primary expression that may be followed by a chain
   * of navigation operations (e.g., property or association accesses).
   *
   * @param ctx the context of the primary expression with navigation in the AST
   * @return the resulting type of the expression after applying the navigation chain; {@link
   *     Type#ERROR} if the base expression has no type
   */
  @Override
  public Type visitPrimaryWithNav(VitruvOCLParser.PrimaryWithNavContext ctx) {
    // Get base type from primary expression
    Type currentType = visit(ctx.base);

    if (currentType == null) {
      reportError(ctx, "Base expression has no type");
      return Type.ERROR;
    }

    // Process navigation chain using receiverStack
    List<VitruvOCLParser.NavigationChainCSContext> navChain = ctx.navigationChainCS();
    receiverStack.push(currentType);
    for (VitruvOCLParser.NavigationChainCSContext nav : navChain) {
      Type resultType = visit(nav);
      receiverStack.pop();
      receiverStack.push(resultType);
      currentType = resultType;
    }
    if (!navChain.isEmpty()) {
      receiverStack.pop();
    }

    nodeTypes.put(ctx, currentType);
    return currentType;
  }

  /**
   * Visits a unary minus ({@code -}) node in the AST.
   *
   * <p>Accepts any numeric base type: {@link Type#INTEGER}, {@link Type#FLOAT}, or {@link
   * Type#DOUBLE}. The result type equals the operand type (i.e., {@code -3.14f} stays FLOAT).
   *
   * <p>Supports singleton and collection receivers via one level of unwrapping.
   *
   * @param ctx the context of the unary minus node in the AST
   * @return the type of the operand if valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitUnaryMinus(VitruvOCLParser.UnaryMinusContext ctx) {
    Type operandType = visit(ctx.operand);

    // Only unwrap singletons ¡T!, not collections
    Type baseType = operandType.isSingleton() ? operandType.getElementType() : operandType;

    if (baseType.isCollection()) {
      reportError(ctx, "Unary minus requires numeric type, got " + operandType);
      return Type.ERROR;
    }

    if (!TypeResolver.isNumeric(baseType) && baseType != Type.ERROR) {
      reportError(ctx, "Unary minus requires numeric type, got " + operandType);
      return Type.ERROR;
    }

    nodeTypes.put(ctx, operandType);
    return operandType;
  }

  /**
   * Visits a logical NOT (`not`) node in the AST.
   *
   * <p>This method performs type checking for the operand of a logical NOT expression. It supports
   * both scalar and collection types (one level of unwrapping):
   *
   * <ul>
   *   <li>If the operand is a singleton or a collection, the base element type is extracted.
   *   <li>Allowed base type is {@link Type#BOOLEAN}.
   *   <li>If the type is invalid, an error is added to the error list and {@link Type#ERROR} is
   *       returned.
   * </ul>
   *
   * <p>The type of the operand is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the logical NOT node in the AST
   * @return the type of the operand if valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitLogicalNot(VitruvOCLParser.LogicalNotContext ctx) {
    Type operandType = visit(ctx.operand);

    // Only unwrap singletons ¡T!, not collections
    Type baseType = operandType.isSingleton() ? operandType.getElementType() : operandType;

    if (baseType.isCollection()) {
      reportError(ctx, "Logical not requires Boolean type, got " + operandType);
      return Type.ERROR;
    }

    if (baseType != Type.BOOLEAN && baseType != Type.ERROR) {
      reportError(ctx, "Logical not requires Boolean type, got " + operandType);
      return Type.ERROR;
    }

    nodeTypes.put(ctx, operandType);
    return operandType;
  }

  // ==================== Cross-Metamodel Support ====================

  /**
   * Type checks fully qualified metaclass references.
   *
   * <p>Resolves qualified names like {@code spaceMission::Spacecraft} to their corresponding
   * metaclass types and handles navigation chains (typically {@code .allInstances()}).
   *
   * <p><b>Example:</b> {@code satelliteSystem::Satellite.allInstances()} → Set(Satellite)
   *
   * @param ctx The prefixed qualified name node
   * @return The result type after navigation
   */
  @Override
  public Type visitPrefixedQualified(VitruvOCLParser.PrefixedQualifiedContext ctx) {
    String metamodel = ctx.metamodel.getText();
    String className = ctx.className.getText();

    // First try: EClass (metamodel::ClassName)
    EClass eClass = specification.resolveEClass(metamodel, className);
    if (eClass != null) {
      Type metaclassType = Type.metaclassType(eClass);
      nodeTypes.put(ctx, metaclassType);
      receiverStack.push(metaclassType);
      Type currentType = metaclassType;
      for (VitruvOCLParser.NavigationChainCSContext nav : ctx.navigationChainCS()) {
        currentType = visit(nav);
        nodeTypes.put(nav, currentType);
        receiverStack.pop();
        receiverStack.push(currentType);
      }
      if (!ctx.navigationChainCS().isEmpty()) {
        receiverStack.pop();
      }
      return currentType;
    }

    // Second try: Enum literal (EnumName::LiteralName)
    org.eclipse.emf.ecore.EEnum eEnum = specification.resolveEEnum(metamodel);
    if (eEnum != null) {
      org.eclipse.emf.ecore.EEnumLiteral literal = eEnum.getEEnumLiteral(className);
      if (literal != null) {
        Type enumType = Type.enumType(eEnum);
        nodeTypes.put(ctx, enumType);
        return enumType;
      }
    }

    if (!specification.getAvailableMetamodels().contains(metamodel)) {
      reportError(ctx.metamodel, "Unknown metamodel '" + metamodel + "'");
    } else {
      reportError(
          ctx.className, "Unknown class '" + className + "' in metamodel '" + metamodel + "'");
    }
    return Type.ERROR;
  }

  // ==================== Navigation ====================

  /**
   * Delegates navigation chain type checking to navigation target.
   *
   * @param ctx The navigation chain node
   * @return The navigation result type
   */
  @Override
  public Type visitNavigationChainCS(VitruvOCLParser.NavigationChainCSContext ctx) {
    if (ctx.AT_PRE() != null) {
      return visitAtPre(ctx);
    }
    return visit(ctx.navigationTargetCS());
  }

  /**
   * Type checks the {@code @pre} postfix operator ({@code self.attr@pre}).
   *
   * <p>{@code @pre} is type-neutral: it doesn't change the type of the navigation step it follows,
   * only the point in time the value is read from (transaction start vs. current). Restrictions
   * (both deliberate, not grammar-enforced — see {@link ConstraintSectionKind}):
   *
   * <ul>
   *   <li>Only valid inside {@code post} blocks.
   *   <li>Must directly follow a property access — {@code self.attr@pre} is valid, bare {@code
   *       self@pre} is not, since there is no "old state" for a receiver that wasn't itself a
   *       navigated feature.
   * </ul>
   *
   * @param ctx the navigation chain node whose {@code AT_PRE()} terminal is non-null
   * @return the unchanged receiver type, or Type.ERROR if misplaced
   */
  private Type visitAtPre(VitruvOCLParser.NavigationChainCSContext ctx) {
    if (currentSection != ConstraintSectionKind.POST) {
      reportError(ctx.AT_PRE().getSymbol(), "'@pre' is only valid inside 'post' blocks");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }
    if (!previousNavWasPropertyAccess(ctx)) {
      reportError(
          ctx.AT_PRE().getSymbol(),
          "'@pre' must directly follow a property access, e.g. 'self.attr@pre'");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }
    Type receiverType = receiverStack.peek();
    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Returns the enclosing {@code navigationChainCS} list that {@code navChainCtx} is an element
   * of — either a {@code primaryWithNav}'s or a {@code prefixedQualified}'s navigation chain.
   */
  private List<VitruvOCLParser.NavigationChainCSContext> enclosingNavigationChain(
      ParserRuleContext navChainCtx) {
    ParseTree parent = navChainCtx.getParent();
    if (parent instanceof VitruvOCLParser.PrimaryWithNavContext p) {
      return p.navigationChainCS();
    }
    if (parent instanceof VitruvOCLParser.PrefixedQualifiedContext p) {
      return p.navigationChainCS();
    }
    return List.of();
  }

  /**
   * Returns {@code true} if the navigation-chain step immediately before {@code ctx} is a plain
   * property access.
   */
  private boolean previousNavWasPropertyAccess(VitruvOCLParser.NavigationChainCSContext ctx) {
    List<VitruvOCLParser.NavigationChainCSContext> chain = enclosingNavigationChain(ctx);
    int idx = chain.indexOf(ctx);
    if (idx <= 0) {
      return false; // first step in the chain — no preceding property access
    }
    VitruvOCLParser.NavigationChainCSContext prev = chain.get(idx - 1);
    return prev.navigationTargetCS() instanceof VitruvOCLParser.PropertyNavContext;
  }

  /**
   * Type checks property navigation.
   *
   * <p>Unwraps singleton {@code !T!} receivers before property access. After the RC2
   * iterator-variable fix all iterator vars are {@code ¡T!}, so this is the normal path for
   * navigating from iterator variables.
   *
   * @param ctx The property navigation node
   * @return The property type
   */
  @Override
  public Type visitPropertyNav(VitruvOCLParser.PropertyNavContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    // Track whether the receiver was optional so we can propagate ?T? through navigation
    boolean receiverWasOptional = receiverType.isOptional();

    // Unwrap singleton !T! or optional ?T? to T for property access
    if (receiverType.isSingleton() || receiverType.isOptional()) {
      receiverType = receiverType.getElementType();
    }

    String propertyName = ctx.propertyAccess().propertyName.getText();
    Type resultType =
        typeCheckPropertyAccess(receiverType, propertyName, ctx.propertyAccess().propertyName);

    // Propagate optionality: ?T?.prop where prop : !R! → result is ?R?
    // Collections keep their type (absent optional → treated as empty collection)
    if (receiverWasOptional && resultType != Type.ERROR && resultType.isSingleton()) {
      resultType = Type.optional(resultType.getElementType());
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks operation navigation.
   *
   * <p>Delegates to the specific operation call visitor.
   *
   * @param ctx The operation navigation node
   * @return The operation result type
   */
  @Override
  public Type visitOperationNav(VitruvOCLParser.OperationNavContext ctx) {
    return visit(ctx.operationCall());
  }

  /**
   * Delegates lifecycle-predicate navigation ({@code .OCLisNew}/{@code .OCLisModified}/{@code
   * .OCLisDeleted}) to the specific predicate visitor.
   *
   * @param ctx The lifecycle-predicate navigation node
   * @return The predicate's result type
   */
  @Override
  public Type visitLifecycleNav(VitruvOCLParser.LifecycleNavContext ctx) {
    return visit(ctx.lifecyclePredicateCS());
  }

  // ==================== Object-Lifecycle Predicates ====================

  /**
   * Validates a lifecycle predicate's ({@code OCLisNew}/{@code OCLisModified}/{@code OCLisDeleted})
   * placement (post-only, see {@link ConstraintSectionKind}) and receiver type (must be an object
   * type, singular or collection).
   *
   * @param ctx the predicate node, used for error positions
   * @param predName the predicate's display name, for error messages
   * @return {@code !Boolean!} for a singleton/bare receiver, a same-kind Boolean collection for a
   *     multi-valued receiver, or Type.ERROR if misplaced/mistyped
   */
  private Type checkLifecyclePredicateReceiver(ParserRuleContext ctx, String predName) {
    if (currentSection != ConstraintSectionKind.POST) {
      reportError(ctx, "'" + predName + "' is only valid inside 'post' blocks");
      return Type.ERROR;
    }
    Type receiverType = receiverStack.peek();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    Type elementType =
        (receiverType.isSingleton() || receiverType.isOptional() || receiverType.isCollection())
            ? receiverType.getElementType()
            : receiverType;
    if (!elementType.isMetaclassType()) {
      reportError(ctx, "'" + predName + "' requires an object receiver, got " + receiverType);
      return Type.ERROR;
    }
    if (receiverType.isCollection() && !receiverType.isSingleton()) {
      return Type.preserveCollectionKind(receiverType, Type.BOOLEAN);
    }
    return Type.singleton(Type.BOOLEAN);
  }

  /**
   * Type checks the named-argument aggregate call form ({@code OCLisNew(attr => val, ...)}).
   * Resolves each attribute against the receiver's EClass and checks the value expression's type
   * conformance. Deliberately does NOT require every attribute to be listed (no completeness
   * check — partial aggregates are valid per spec).
   *
   * @param argsCtx the aggregate argument list, or {@code null} if the bare (no-parens) form was
   *     used
   * @param receiverType the (already-validated) object receiver type
   */
  private void checkLifecycleAggregateArgs(
      VitruvOCLParser.LifecycleAggregateArgsContext argsCtx, Type receiverType) {
    if (argsCtx == null) {
      return;
    }
    Type elementType =
        (receiverType.isSingleton() || receiverType.isOptional() || receiverType.isCollection())
            ? receiverType.getElementType()
            : receiverType;
    if (!elementType.isMetaclassType()) {
      return; // receiver error already reported by checkLifecyclePredicateReceiver
    }
    EClass eClass = elementType.getEClass();
    for (VitruvOCLParser.LifecycleAggregateArgContext arg : argsCtx.lifecycleAggregateArg()) {
      String attrName = arg.attr.getText();
      EStructuralFeature feature = eClass.getEStructuralFeature(attrName);
      if (feature == null) {
        feature = findFeatureInSubtypes(eClass, attrName);
      }
      Type valueType = visit(arg.value);
      if (feature == null) {
        reportError(arg.attr, unknownPropertyMessage(attrName, eClass));
        continue;
      }
      if (valueType == Type.ERROR) {
        continue; // already reported
      }
      Type featureType = mapFeatureToType(feature);
      Type expectedElem =
          (featureType.isSingleton() || featureType.isOptional())
              ? featureType.getElementType()
              : featureType;
      Type actualElem =
          (valueType.isSingleton() || valueType.isOptional())
              ? valueType.getElementType()
              : valueType;
      if (!actualElem.isConformantTo(expectedElem)) {
        reportError(
            arg.value,
            "Type mismatch: attribute '"
                + attrName
                + "' expects "
                + featureType
                + ", got "
                + valueType);
      }
    }
  }

  /**
   * Type checks {@code OCLisNew} (bare or with a {@code (attr => val, ...)} aggregate).
   *
   * @param ctx The OCLisNew predicate node
   * @return see {@link #checkLifecyclePredicateReceiver}
   */
  @Override
  public Type visitOclIsNewPred(VitruvOCLParser.OclIsNewPredContext ctx) {
    Type resultType = checkLifecyclePredicateReceiver(ctx, "OCLisNew");
    if (resultType != Type.ERROR) {
      checkLifecycleAggregateArgs(ctx.lifecycleAggregateArgs(), receiverStack.peek());
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks {@code OCLisModified} (bare or with a {@code (attr => val, ...)} aggregate). The
   * aggregate values are target/post-state values, not deltas — see grammar documentation.
   *
   * @param ctx The OCLisModified predicate node
   * @return see {@link #checkLifecyclePredicateReceiver}
   */
  @Override
  public Type visitOclIsModifiedPred(VitruvOCLParser.OclIsModifiedPredContext ctx) {
    Type resultType = checkLifecyclePredicateReceiver(ctx, "OCLisModified");
    if (resultType != Type.ERROR) {
      checkLifecycleAggregateArgs(ctx.lifecycleAggregateArgs(), receiverStack.peek());
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks {@code OCLisDeleted}. No aggregate form exists — the grammar itself rules it out.
   *
   * @param ctx The OCLisDeleted predicate node
   * @return see {@link #checkLifecyclePredicateReceiver}
   */
  @Override
  public Type visitOclIsDeletedPred(VitruvOCLParser.OclIsDeletedPredContext ctx) {
    Type resultType = checkLifecyclePredicateReceiver(ctx, "OCLisDeleted");
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks property access on a receiver.
   *
   * <p>Handles both singleton and collection receivers:
   *
   * <ul>
   *   <li><b>Singleton:</b> {@code person.name} → direct property access
   *   <li><b>Collection:</b> {@code companies.name} → implicit collect, returns a flattened
   *       collection whose Ctype combines the receiver's own unique/ordered flags with the
   *       navigated feature's flags (OCL#, Sect. 4.4.4, "e.p"): ordered = receiver-ordered ∨
   *       feature-ordered; unique = receiver-unique ∧ feature-unique. E.g. navigating {@code
   *       .name} (a singleton feature) over a {@code Sequence(Company)} receiver yields {@code
   *       Sequence(String)}, not a flat {@code Set(String)}.
   * </ul>
   *
   * @param receiverType The type of the receiver
   * @param propName The property name
   * @param ctx The parse tree context for error reporting
   * @return The property type (may be wrapped in collection)
   */
  private Type typeCheckPropertyAccess(
      Type receiverType, String propName, Token propertyNameToken) {
    // Implicit collect for collections
    if (receiverType.isCollection()) {
      Type elemType = receiverType.getElementType();

      if (!elemType.isMetaclassType()) {
        reportError(propertyNameToken, "Cannot navigate on non-object type");
        return Type.ERROR;
      }

      EClass eClass = elemType.getEClass();
      EStructuralFeature feature = eClass.getEStructuralFeature(propName);

      if (feature == null) {
        feature = findFeatureInSubtypes(eClass, propName);
        if (feature == null) {
          reportError(propertyNameToken, unknownPropertyMessage(propName, eClass));
          return Type.ERROR;
        }
      }

      Type featureType = mapFeatureToType(feature);

      // Combine the receiver's Ctype with the navigated feature's own Ctype (OCL#, Sect. 4.4.4:
      // "e.p" combines receiver and feature multiplicity via ω ∨ ω' for orderedness). A
      // single-valued feature contributes no ordering/duplication of its own (neutral element:
      // ordered=false, unique=true).
      boolean featureOrdered = feature.isMany() && feature.isOrdered();
      boolean featureUnique = !feature.isMany() || feature.isUnique();
      boolean resultOrdered = receiverType.isOrdered() || featureOrdered;
      // Uniqueness is combined conservatively (AND): duplicates across different receiver
      // elements (or within a non-unique feature) can only be ruled out if neither side allows
      // them.
      boolean resultUnique = receiverType.isUnique() && featureUnique;
      return Type.collectionKindFor(resultUnique, resultOrdered, featureType.getElementType());
    }

    // Singleton property access
    if (receiverType.isMetaclassType()) {
      EClass eClass = receiverType.getEClass();
      EStructuralFeature feature = eClass.getEStructuralFeature(propName);

      if (feature == null) {
        feature = findFeatureInSubtypes(eClass, propName);
        if (feature == null) {
          reportError(propertyNameToken, unknownPropertyMessage(propName, eClass));
          return Type.ERROR;
        }
      }

      return mapFeatureToType(feature);
    }

    reportError(propertyNameToken, "Cannot access property on " + receiverType);
    return Type.ERROR;
  }

  private static String unknownPropertyMessage(String propName, EClass eClass) {
    return "Unknown property '"
        + propName
        + "' on type '"
        + eClass.getName()
        + "'"
        + " (not found on declared type or any known subtype;"
        + " use oclIsKindOf + oclAsType to cast to the concrete subtype first)";
  }

  /**
   * Searches all known subclasses of {@code abstractClass} (reachable via EMF EPackage hierarchy)
   * for a structural feature named {@code propName}. Returns the feature if found unambiguously on
   * one or more subtypes (all with the same defining EClass), or {@code null} if not found or
   * ambiguous across incompatible types.
   */
  private EStructuralFeature findFeatureInSubtypes(EClass abstractClass, String propName) {
    // Collect all EPackages reachable from the root package
    EPackage rootPkg = abstractClass.getEPackage();
    while (rootPkg.getESuperPackage() != null) {
      rootPkg = rootPkg.getESuperPackage();
    }
    List<EClass> allClasses = new ArrayList<>();
    collectEClasses(rootPkg, allClasses, new HashSet<>());

    // Find feature on any subtype (abstractClass.isSuperTypeOf(candidate) is true for subtypes)
    EStructuralFeature found = null;
    for (EClass candidate : allClasses) {
      if (candidate == abstractClass || !abstractClass.isSuperTypeOf(candidate)) {
        continue;
      }
      EStructuralFeature f = candidate.getEStructuralFeature(propName);
      if (f != null) {
        if (found == null) {
          found = f;
        } else if (found.getEType() != f.getEType() || found.isMany() != f.isMany()) {
          // Ambiguous: same feature name but different types on different subtypes — reject
          return null;
        }
      }
    }
    return found;
  }

  private void collectEClasses(EPackage pkg, List<EClass> result, Set<EPackage> visited) {
    if (!visited.add(pkg)) {
      return;
    }
    for (EClassifier c : pkg.getEClassifiers()) {
      if (c instanceof EClass eClass) {
        result.add(eClass);
      }
    }
    for (EPackage sub : pkg.getESubpackages()) {
      collectEClasses(sub, result, visited);
    }
  }

  /**
   * Maps an EMF structural feature to a VitruvOCL type.
   *
   * <p>Respects EMF ordering and uniqueness annotations:
   *
   * <ul>
   *   <li>ordered + unique → OrderedSet {@code <T>}
   *   <li>ordered + !unique → Sequence {@code [T]}
   *   <li>!ordered + unique → Set {@code {T}}
   *   <li>!ordered + !unique → Bag {@code {{T}}}
   *   <li>single-valued → Singleton {@code !T!}
   * </ul>
   *
   * @param feature The EMF structural feature
   * @return The corresponding VitruvOCL type
   */
  @SuppressWarnings("java:S125")
  private Type mapFeatureToType(EStructuralFeature feature) {
    Type baseType = mapEClassifierToType(feature.getEType());

    if (feature.getUpperBound() > 1 || feature.getUpperBound() == -1) {
      // Multi-valued: respect EMF ordering and uniqueness annotations
      boolean ordered = feature.isOrdered();
      boolean unique = feature.isUnique();
      // EMF's upperBound already uses -1 for "unbounded", matching Type.UNBOUNDED — no
      // translation needed.
      int lowerBound = feature.getLowerBound();
      int upperBound = feature.getUpperBound();

      if (ordered && unique) {
        return Type.orderedSet(baseType, lowerBound, upperBound); // <T>
      } else if (ordered) {
        return Type.sequence(baseType, lowerBound, upperBound); // [T]
      } else if (unique) {
        return Type.set(baseType, lowerBound, upperBound); // {T}
      } else {
        return Type.bag(baseType, lowerBound, upperBound); // {{T}}
      }
    } else {
      // EAttribute with lowerBound=0: primitive always has a default value → ¡T!
      // EReference with lowerBound=0: reference may be absent → ¿T?
      // Any single-valued with lowerBound>=1: always present → ¡T!
      if (feature instanceof org.eclipse.emf.ecore.EReference && feature.getLowerBound() == 0) {
        return Type.optional(baseType);
      } else {
        return Type.singleton(baseType);
      }
    }
  }

  /**
   * Maps an EMF EClassifier to a VitruvOCL type.
   *
   * <p>Handles:
   *
   * <ul>
   *   <li>EString → Type.STRING
   *   <li>EInt → Type.INTEGER
   *   <li>EBoolean → Type.BOOLEAN
   *   <li>EClass → metaclass type
   * </ul>
   *
   * @param classifier The EMF classifier
   * @return The corresponding VitruvOCL type
   */
  private Type mapEClassifierToType(EClassifier classifier) {
    // Handle primitive types
    switch (classifier.getName()) {
      case "EString":
        return Type.STRING;
      case "EInt":
        return Type.INTEGER;
      case "EBoolean":
        return Type.BOOLEAN;
      case "EFloat":
        return Type.FLOAT;
      case "EDouble":
        return Type.DOUBLE;
      default:
        if (EcorePackage.Literals.ECLASS.equals(classifier.eClass())) {
          return Type.metaclassType((EClass) classifier);
        }
        // Handle EEnum — enum literals are compared by value in OCL
        if (classifier instanceof org.eclipse.emf.ecore.EEnum eEnum) {
          return Type.enumType(eEnum);
        }
        // Handle custom EDataType (e.g. autosar::Identifier with
        // instanceClassName="java.lang.String")
        if (classifier instanceof org.eclipse.emf.ecore.EDataType eDataType) {
          String instanceClass = eDataType.getInstanceClassName();
          if (instanceClass != null) {
            return switch (instanceClass) {
              case "java.lang.String", TYPE_STRING -> Type.STRING;
              case "int", "java.lang.Integer" -> Type.INTEGER;
              case TYPE_BOOLEAN, "java.lang.Boolean" -> Type.BOOLEAN;
              case "float", "java.lang.Float" -> Type.FLOAT;
              case "double", "java.lang.Double" -> Type.DOUBLE;
              default -> Type.STRING; // fallback: treat unknown scalar datatypes as String
            };
          }
        }
        return Type.ERROR;
    }
  }

  // ==================== Control Flow ====================

  /**
   * Type checks if-then-else expressions.
   *
   * <p>Validates:
   *
   * <ol>
   *   <li>Condition must be Boolean
   *   <li>Then and else branches must have compatible types
   * </ol>
   *
   * <p>Result type is the common supertype of both branches.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * if age >= 18 then 'adult' else 'minor' endif  // → String
   * if condition then 1 else 2.0 endif            // → Real (common supertype)
   * }</pre>
   *
   * @param ctx The if-expression node
   * @return The common supertype of both branches
   */
  @Override
  @SuppressWarnings("java:S3776")
  public Type visitIfExpCS(VitruvOCLParser.IfExpCSContext ctx) {
    List<VitruvOCLParser.ExpCSContext> allExps = ctx.expCS();

    int thenIndex = -1;
    int elseIndex = -1;
    for (int i = 0; i < ctx.getChildCount(); i++) {
      String text = ctx.getChild(i).getText();
      if ("then".equals(text) && thenIndex == -1) {
        thenIndex = ctx.getChild(i).getSourceInterval().a;
      }
      if ("else".equals(text) && elseIndex == -1) {
        elseIndex = ctx.getChild(i).getSourceInterval().a;
      }
    }

    List<VitruvOCLParser.ExpCSContext> condExps = new ArrayList<>();
    List<VitruvOCLParser.ExpCSContext> thenExps = new ArrayList<>();
    List<VitruvOCLParser.ExpCSContext> elseExps = new ArrayList<>();

    for (VitruvOCLParser.ExpCSContext exp : allExps) {
      int expIndex = exp.getStart().getTokenIndex();
      if (expIndex < thenIndex) {
        condExps.add(exp);
      } else if (expIndex < elseIndex) {
        thenExps.add(exp);
      } else {
        elseExps.add(exp);
      }
    }

    // Type-check condition
    Type condType = null;
    for (VitruvOCLParser.ExpCSContext exp : condExps) {
      condType = visit(exp);
    }
    if (condType != null && condType != Type.ERROR) {
      Type condCheck = condType.isSingleton() ? condType.getElementType() : condType;
      if (condCheck.isCollection() || !condCheck.isConformantTo(Type.BOOLEAN)) {
        reportError(ctx, "If condition must be Boolean, got " + condType);
      }
    }

    // Type-check then-branch
    Type thenType = null;
    for (VitruvOCLParser.ExpCSContext exp : thenExps) {
      thenType = visit(exp);
    }

    // Type-check else-branch
    Type elseType = null;
    for (VitruvOCLParser.ExpCSContext exp : elseExps) {
      elseType = visit(exp);
    }

    if (thenType == null || elseType == null) {
      return Type.ERROR;
    }

    // Branches must have compatible types — mixing collection and scalar is an error
    boolean thenIsCollection = thenType.isCollection() && !thenType.isSingleton();
    boolean elseIsCollection = elseType.isCollection() && !elseType.isSingleton();
    if (thenIsCollection != elseIsCollection) {
      reportError(ctx, ERR_BRANCH_TYPES + thenType + AND_SEPARATOR + elseType);
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    // Both collections must have same kind (ordered/unique)
    if (thenIsCollection
        && elseIsCollection
        && (thenType.isOrdered() != elseType.isOrdered()
            || thenType.isUnique() != elseType.isUnique())) {
      reportError(ctx, ERR_BRANCH_TYPES + thenType + AND_SEPARATOR + elseType);
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    // Determine result type
    Type resultType;
    if (thenType.equals(elseType)) {
      resultType = thenType;
    } else if (thenType.isConformantTo(elseType)) {
      resultType = elseType;
    } else if (elseType.isConformantTo(thenType)) {
      resultType = thenType;
    } else {
      resultType = Type.commonSuperType(thenType, elseType);
      if (resultType == null || resultType == Type.ANY) {
        reportError(ctx, ERR_BRANCH_TYPES + thenType + AND_SEPARATOR + elseType);
        resultType = Type.ERROR;
      }
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Processes variable declarations (in let-expressions).
   *
   * @param ctx The variable declarations node
   * @return Type.ANY (not used)
   */
  @Override
  public Type visitVariableDeclarations(VitruvOCLParser.VariableDeclarationsContext ctx) {
    for (VitruvOCLParser.VariableDeclarationContext varDecl : ctx.variableDeclaration()) {
      visit(varDecl);
    }
    return Type.ANY; // Not used
  }

  /**
   * Type checks a single variable declaration.
   *
   * <p>Validates:
   *
   * <ul>
   *   <li>No duplicate variable names in current scope
   *   <li>Initializer type conforms to declared type (if present), using singleton-unwrapping rules
   *       via {@link Type#conformsWithUnwrapping}
   * </ul>
   *
   * <p>The variable is registered with the declared type (not the wrapped initType) so that
   * downstream navigation operates on the correct member type.
   *
   * @param ctx The variable declaration node
   * @return The variable type
   */
  @Override
  public Type visitVariableDeclaration(VitruvOCLParser.VariableDeclarationContext ctx) {
    String varName = ctx.varName.getText();

    // Variable already defined in Pass 1 - just look it up
    VariableSymbol symbol = symbolTable.resolveVariable(varName);
    if (symbol == null) {
      reportError(ctx, "Variable '" + varName + "' not found (Pass 1 error?)");
      return Type.ERROR;
    }

    // Type-check initializer
    Type initType = checkInitializer(ctx.varInit, symbol);

    // Check explicit type if present
    Type declaredType = symbol.getType();
    if (ctx.varType != null) {
      Type explicitType = visit(ctx.varType);

      // use standard isConformantTo first, then singleton-unwrapping
      if (!initType.isConformantTo(explicitType)
          && !Type.conformsWithUnwrapping(initType, explicitType)) {
        reportError(ctx, "Type mismatch: got " + initType + ", expected " + explicitType);
        return Type.ERROR;
      }

      // Always use the declared type for the variable so navigation on it uses
      // the correct (potentially more specific) type
      declaredType = explicitType;
      if (symbol.getType() == Type.ANY) {
        symbol.setType(declaredType);
      }
    } else {
      // No explicit type — refine symbol with inferred type from initializer
      if (declaredType == Type.ANY) {
        symbol.setType(initType);
        declaredType = initType;
      } else if (!initType.isConformantTo(declaredType)
          && !Type.conformsWithUnwrapping(initType, declaredType)) {
        reportError(ctx, "Type mismatch: got " + initType + ", expected " + declaredType);
      }
    }

    nodeTypes.put(ctx, declaredType);
    return declaredType;
  }

  // ==================== Literal Values ====================

  /**
   * Type checks integer literals.
   *
   * @param ctx The number literal node
   * @return Type.INTEGER
   */
  @Override
  public Type visitNumberLit(VitruvOCLParser.NumberLitContext ctx) {
    String text = ctx.getText();
    Type type = text.contains(".") ? Type.DOUBLE : Type.INTEGER;
    nodeTypes.put(ctx, type);
    return type;
  }

  /**
   * Type checks string literals.
   *
   * @param ctx The string literal node
   * @return Type.STRING
   */
  @Override
  public Type visitStringLit(VitruvOCLParser.StringLitContext ctx) {
    nodeTypes.put(ctx, Type.STRING);
    return Type.STRING;
  }

  /**
   * Type checks boolean literals.
   *
   * @param ctx The boolean literal node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitBooleanLit(VitruvOCLParser.BooleanLitContext ctx) {
    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  // ==================== Variable References ====================

  /**
   * Type checks {@code initializer} while {@code unbound} are marked as being initialized: there,
   * {@link #visitVariableExpCS} resolves their names to the variables they shadow, or reports an
   * error if there are none (the reference would fail at runtime as unbound).
   */
  private Type checkInitializer(ParserRuleContext initializer, VariableSymbol... unbound) {
    List<VariableSymbol> marked = new ArrayList<>();
    for (VariableSymbol variable : unbound) {
      if (variable != null && initializing.put(variable, Boolean.TRUE) == null) {
        marked.add(variable);
      }
    }
    try {
      return visit(initializer);
    } finally {
      marked.forEach(initializing::remove);
    }
  }

  /**
   * Type checks variable references.
   *
   * <p>Handles the special {@code null} keyword, which is the empty optional {@code ?Any?} = {@code
   * []} rather than a variable. All other names are resolved from the symbol table.
   *
   * @param ctx The variable expression node
   * @return The variable's type, or {@code ?Any?} for {@code null}
   */
  @Override
  public Type visitVariableExpCS(VitruvOCLParser.VariableExpCSContext ctx) {
    String varName = ctx.varName.getText();

    // null is not valid in OCL# — use isEmpty() / notEmpty() instead
    if (varName.equals("null")) {
      reportError(
          ctx,
          "'null' is not a valid expression in OCL#. Use isEmpty() / notEmpty() to test optional"
              + " values.");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    VariableSymbol symbol = symbolTable.resolveVariable(varName);
    if (symbol == null) {
      handleUndefinedSymbol(varName, ctx);
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }
    // inside its own initializer a name refers to the variable it shadows, as at runtime
    VariableSymbol own = symbol;
    while (symbol != null && initializing.containsKey(symbol)) {
      Scope outer = symbol.getDefiningScope().getEnclosingScope();
      symbol = outer == null ? null : outer.resolveVariable(varName);
    }
    if (symbol == null) {
      reportError(ctx, "Variable '" + own.getName() + "' is used in its own initializer");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    Type varType = symbol.getType();
    nodeTypes.put(ctx, varType);
    return varType;
  }

  /**
   * Type checks {@code self} references.
   *
   * <p>Returns the type of the special {@code self} variable from the current context.
   *
   * @param ctx The self expression node
   * @return The context type (type of self)
   */
  @Override
  public Type visitSelfExpCS(VitruvOCLParser.SelfExpCSContext ctx) {
    Symbol selfSymbol = symbolTable.resolveVariable("self");

    if (selfSymbol == null) {
      reportError(ctx, "'self' not defined in current context");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    Type selfType = selfSymbol.getType();
    nodeTypes.put(ctx, selfType);
    return selfType;
  }

  // ==================== Collection Literals ====================

  /**
   * Type checks collection literal expressions.
   *
   * <p>Infers element type from collection arguments and constructs appropriate collection type.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code Set{1,2,3}} → Set(Integer)
   *   <li>{@code Bag{'a','b'}} → Bag(String)
   *   <li>{@code Sequence{}} → Sequence(Any)
   * </ul>
   *
   * @param ctx The collection literal node
   * @return The inferred collection type
   */
  @Override
  public Type visitCollectionLiteralExpCS(VitruvOCLParser.CollectionLiteralExpCSContext ctx) {
    Type collectionType = visit(ctx.collectionKind);

    if (collectionType == Type.ERROR) {
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    // Empty collection: keep ANY as element type
    if (ctx.arguments == null) {
      nodeTypes.put(ctx, collectionType);
      return collectionType;
    }

    // Infer element type from arguments
    Type inferredElementType = visit(ctx.arguments);

    if (inferredElementType == Type.ERROR) {
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    // Create collection with inferred element type
    Type resultType = Type.preserveCollectionKind(collectionType, inferredElementType);

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks collection arguments (elements in collection literal).
   *
   * <p>Computes the common supertype of all elements.
   *
   * @param ctx The collection arguments node
   * @return The common element type
   */
  @Override
  public Type visitCollectionArguments(VitruvOCLParser.CollectionArgumentsContext ctx) {
    Type commonType = null;

    for (VitruvOCLParser.CollectionLiteralPartCSContext part : ctx.collectionLiteralPartCS()) {
      Type partType = visit(part);

      if (partType == Type.ERROR) {
        continue;
      }

      if (commonType == null) {
        commonType = partType;
      } else if (!partType.equals(commonType)) {
        Type superType = Type.commonSuperType(commonType, partType);
        commonType = (superType != null) ? superType : Type.ANY;
      }
    }

    return (commonType != null) ? commonType : Type.ANY;
  }

  /**
   * Type checks a collection literal part (element or range).
   *
   * <p>Handles:
   *
   * <ul>
   *   <li>Single elements: {@code 42}
   *   <li>Ranges: {@code 1..10} (both bounds must be Integer)
   * </ul>
   *
   * @param ctx The collection literal part node
   * @return The element type (Integer for ranges)
   */
  @Override
  public Type visitCollectionLiteralPartCS(VitruvOCLParser.CollectionLiteralPartCSContext ctx) {
    Type firstType = visit(ctx.expCS(0));

    // Range: 1..10
    if (ctx.expCS().size() == 2) {
      Type secondType = visit(ctx.expCS(1));

      if (!firstType.isConformantTo(Type.INTEGER)) {
        reportError(ctx, "Range start must be Integer");
      }
      if (!secondType.isConformantTo(Type.INTEGER)) {
        reportError(ctx, "Range end must be Integer");
      }

      return Type.INTEGER;
    }

    return firstType;
  }

  // ==================== Collection Operations ====================

  /** Type checks {@code first()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitFirstOp(VitruvOCLParser.FirstOpContext ctx) {
    return collections.first(ctx);
  }

  /** Type checks {@code last()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitLastOp(VitruvOCLParser.LastOpContext ctx) {
    return collections.last(ctx);
  }

  /** Type checks {@code reverse()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitReverseOp(VitruvOCLParser.ReverseOpContext ctx) {
    return collections.reverse(ctx);
  }

  /** Type checks {@code isEmpty()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitIsEmptyOp(VitruvOCLParser.IsEmptyOpContext ctx) {
    return collections.isEmpty(ctx);
  }

  /** Type checks {@code notEmpty()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitNotEmptyOp(VitruvOCLParser.NotEmptyOpContext ctx) {
    return collections.notEmpty(ctx);
  }

  /** Type checks {@code including()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitIncludingOp(VitruvOCLParser.IncludingOpContext ctx) {
    return collections.including(ctx);
  }

  /** Type checks {@code excluding()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitExcludingOp(VitruvOCLParser.ExcludingOpContext ctx) {
    return collections.excluding(ctx);
  }

  /** Type checks {@code includes()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitIncludesOp(VitruvOCLParser.IncludesOpContext ctx) {
    return collections.includes(ctx);
  }

  /** Type checks {@code excludes()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitExcludesOp(VitruvOCLParser.ExcludesOpContext ctx) {
    return collections.excludes(ctx);
  }

  /** Type checks {@code flatten()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitFlattenOp(VitruvOCLParser.FlattenOpContext ctx) {
    return collections.flatten(ctx);
  }

  /** Type checks {@code union()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitUnionOp(VitruvOCLParser.UnionOpContext ctx) {
    return collections.union(ctx);
  }

  /** Type checks {@code append()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitAppendOp(VitruvOCLParser.AppendOpContext ctx) {
    return collections.append(ctx);
  }

  // ==================== Aggregate Operations ====================

  /** Type checks {@code sum()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitSumOp(VitruvOCLParser.SumOpContext ctx) {
    return collections.sum(ctx);
  }

  /** Type checks {@code max()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitMaxOp(VitruvOCLParser.MaxOpContext ctx) {
    return collections.max(ctx);
  }

  /** Type checks {@code min()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitMinOp(VitruvOCLParser.MinOpContext ctx) {
    return collections.min(ctx);
  }

  /** Type checks {@code avg()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitAvgOp(VitruvOCLParser.AvgOpContext ctx) {
    return collections.avg(ctx);
  }

  // ==================== Numeric Operations ====================

  /** Type checks {@code abs()}; see {@link NumericOperationTyping}. */
  @Override
  public Type visitAbsOp(VitruvOCLParser.AbsOpContext ctx) {
    return numerics.abs(ctx);
  }

  /** Type checks {@code floor()}; see {@link NumericOperationTyping}. */
  @Override
  public Type visitFloorOp(VitruvOCLParser.FloorOpContext ctx) {
    return numerics.floor(ctx);
  }

  /** Type checks {@code ceil()}; see {@link NumericOperationTyping}. */
  @Override
  public Type visitCeilOp(VitruvOCLParser.CeilOpContext ctx) {
    return numerics.ceil(ctx);
  }

  /** Type checks {@code round()}; see {@link NumericOperationTyping}. */
  @Override
  public Type visitRoundOp(VitruvOCLParser.RoundOpContext ctx) {
    return numerics.round(ctx);
  }

  /** Type checks {@code length()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitLengthOp(VitruvOCLParser.LengthOpContext ctx) {
    return strings.length(ctx);
  }

  /** Type checks {@code size()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitSizeOp(VitruvOCLParser.SizeOpContext ctx) {
    return collections.size(ctx);
  }

  /** Type checks {@code lift()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitLiftOp(VitruvOCLParser.LiftOpContext ctx) {
    return collections.lift(ctx);
  }

  // ==================== Iterator Operations ====================

  /** Type checks {@code select()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitSelectOp(VitruvOCLParser.SelectOpContext ctx) {
    return iterators.select(ctx);
  }

  /** Type checks {@code reject()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitRejectOp(VitruvOCLParser.RejectOpContext ctx) {
    return iterators.reject(ctx);
  }

  /** Type checks {@code collect()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitCollectOp(VitruvOCLParser.CollectOpContext ctx) {
    return iterators.collect(ctx);
  }

  /** Type checks {@code forAll()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitForAllOp(VitruvOCLParser.ForAllOpContext ctx) {
    return iterators.forAll(ctx);
  }

  /** Type checks {@code exists()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitExistsOp(VitruvOCLParser.ExistsOpContext ctx) {
    return iterators.exists(ctx);
  }

  @Override
  public Type visitIteratorMissingBody(VitruvOCLParser.IteratorMissingBodyContext ctx) {
    String opName = ctx.op.getText();
    org.antlr.v4.runtime.Token start = ctx.getStart();
    org.antlr.v4.runtime.Token stop = ctx.getStop(); // closing ')'
    int endLine = stop != null ? stop.getLine() : start.getLine();
    int endCol =
        stop != null
            ? stop.getCharPositionInLine() + stop.getText().length()
            : start.getCharPositionInLine() + ctx.getText().length();
    errors.add(
        new CompileError(
            start.getLine(),
            start.getCharPositionInLine(),
            endLine,
            endCol,
            "'" + opName + "' requires '| <body>' after the iterator variable(s)",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));
    return Type.ERROR;
  }

  @Override
  public Type visitNoArgOpWithArgs(VitruvOCLParser.NoArgOpWithArgsContext ctx) {
    // Visit args so their own errors surface, then report the operation error.
    for (VitruvOCLParser.ExpCSContext arg : ctx.args) {
      visit(arg);
    }
    String opName = ctx.op.getText();
    org.antlr.v4.runtime.Token start = ctx.getStart();
    org.antlr.v4.runtime.Token stop = ctx.getStop();
    int endLine = stop != null ? stop.getLine() : start.getLine();
    int endCol =
        stop != null
            ? stop.getCharPositionInLine() + stop.getText().length()
            : start.getCharPositionInLine() + ctx.getText().length();
    errors.add(
        new CompileError(
            start.getLine(),
            start.getCharPositionInLine(),
            endLine,
            endCol,
            "'" + opName + "' takes no arguments",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));
    return Type.ERROR;
  }

  /** Whether {@code name} is a catalog operation called with empty parens, e.g. {@code sum()}. */
  private static boolean takesNoArguments(String name) {
    return OclOperations.lookup(name)
        .map(op -> op.params().isEmpty() && op.family() != OclOperation.Family.ITERATOR)
        .orElse(false);
  }

  @Override
  public Type visitOpMissingParens(VitruvOCLParser.OpMissingParensContext ctx) {
    org.antlr.v4.runtime.Token op = ctx.op;
    String name = op.getText();
    String suggestion;
    String message;
    if (takesNoArguments(name)) {
      suggestion = name + "()";
      message = "'" + name + "' is an operation — add parentheses: '" + suggestion + "'";
    } else {
      suggestion = name + "(...)";
      message = "'" + name + "' is an operation that requires arguments — use '" + suggestion + "'";
    }
    errors.add(
        new CompileError(
            op.getLine(),
            op.getCharPositionInLine(),
            op.getLine(),
            op.getCharPositionInLine() + op.getText().length(),
            message,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            suggestion));
    return Type.ERROR;
  }

  // ==================== String Operations ====================

  /** Type checks {@code concat()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitConcatOp(VitruvOCLParser.ConcatOpContext ctx) {
    return strings.concat(ctx);
  }

  /** Type checks {@code substring()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitSubstringOp(VitruvOCLParser.SubstringOpContext ctx) {
    return strings.substring(ctx);
  }

  /** Type checks {@code toUpper()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitToUpperOp(VitruvOCLParser.ToUpperOpContext ctx) {
    return strings.toUpper(ctx);
  }

  /** Type checks {@code toLower()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitToLowerOp(VitruvOCLParser.ToLowerOpContext ctx) {
    return strings.toLower(ctx);
  }

  /** Type checks {@code indexOf()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitIndexOfOp(VitruvOCLParser.IndexOfOpContext ctx) {
    return strings.indexOf(ctx);
  }

  /** Type checks {@code equalsIgnoreCase()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitEqualsIgnoreCaseOp(VitruvOCLParser.EqualsIgnoreCaseOpContext ctx) {
    return strings.equalsIgnoreCase(ctx);
  }

  // ==================== Type Operations ====================

  /**
   * Type checks the {@code oclIsKindOf()} operation.
   *
   * <p>For a singleton {@code !T!} or bare metaclass receiver, returns {@code !Boolean!}. For a
   * multi-valued collection receiver, returns a collection of Boolean with the same kind.
   *
   * @param ctx The oclIsKindOf operation node
   * @return {@code !Boolean!} for singleton/bare receivers; {@code Collection(Boolean)} for
   *     multi-valued receivers
   */
  @Override
  public Type visitOclIsKindOfOp(VitruvOCLParser.OclIsKindOfOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    Type targetType = visit(ctx.type);
    if (targetType == null && ctx.type != null) {
      targetType = resolveTypeExpression(ctx.type);
    }

    // Mirror the logic of visitOclAsTypeOp: Singleton !T! must not be wrapped in Set first,
    // otherwise preserveCollectionKind would return Set{Boolean} = {Boolean} instead of !Boolean!
    Type resultType;
    if (receiverType.isCollection() && !receiverType.isSingleton()) {
      // Multi-valued collection: one Boolean per element, preserve collection kind
      resultType = Type.preserveCollectionKind(receiverType, Type.BOOLEAN);
    } else {
      // Singleton !T! or bare metaclass T → oclIsKindOf returns a single !Boolean!
      resultType = Type.singleton(Type.BOOLEAN);
    }

    nodeTypes.put(ctx, resultType);
    nodeTypes.put(ctx.type, targetType);
    return resultType;
  }

  /** Helper to resolve type expressions for type operations. */
  private Type resolveTypeExpression(VitruvOCLParser.TypeExpCSContext ctx) {
    String text = ctx.getText();
    return switch (text) {
      case TYPE_INTEGER -> Type.INTEGER;
      case TYPE_STRING -> Type.STRING;
      case TYPE_BOOLEAN -> Type.BOOLEAN;
      default -> Type.ANY;
    };
  }

  /**
   * Visits an 'oclIsTypeOf' operation node in the AST.
   *
   * <p>This operation checks whether each element of a collection is exactly of a given type.
   *
   * <ul>
   *   <li>Ensures that the receiver is a collection; otherwise reports an error and returns {@link
   *       Type#ERROR}.
   *   <li>The resulting type preserves the collection kind of the receiver, but with {@link
   *       Type#BOOLEAN} as element type.
   *   <li>The resulting type is stored in {@code nodeTypes}.
   * </ul>
   *
   * @param ctx the context of the 'oclIsTypeOf' operation node in the AST
   * @return the collection of Boolean type if receiver is valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitOclIsTypeOfOp(VitruvOCLParser.OclIsTypeOfOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    Type targetType = visit(ctx.type);
    if (targetType == null && ctx.type != null) {
      targetType = resolveTypeExpression(ctx.type);
    }

    // Mirror visitOclIsKindOfOp: Singleton !T! must not be wrapped in Set first
    Type resultType;
    if (receiverType.isCollection() && !receiverType.isSingleton()) {
      // Multi-valued collection: one Boolean per element, preserve collection kind
      resultType = Type.preserveCollectionKind(receiverType, Type.BOOLEAN);
    } else {
      // Singleton !T! or bare metaclass T → oclIsTypeOf returns a single !Boolean!
      resultType = Type.singleton(Type.BOOLEAN);
    }
    nodeTypes.put(ctx, resultType);
    nodeTypes.put(ctx.type, targetType);
    return resultType;
  }

  /**
   * Visits an 'oclAsType' operation node in the AST.
   *
   * <p>Casts the receiver to the target type while preserving the multiplicity. The receiver's
   * ctype multiplicity is preserved — only the member type τ is replaced by the target type:
   *
   * <ul>
   *   <li>{@code ¡Shape! .oclAsType(Box)} → {@code ¡Box!}
   *   <li>{@code {Shape} .oclAsType(Box)} → {@code {Box}}
   * </ul>
   *
   * @param ctx the context of the 'oclAsType' operation node in the AST
   * @return the cast type with preserved multiplicity, or {@link Type#ERROR}
   */
  @Override
  public Type visitOclAsTypeOp(VitruvOCLParser.OclAsTypeOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    Type targetType = visit(ctx.type);
    if (targetType == null && ctx.type != null) {
      targetType = resolveTypeExpression(ctx.type);
    }

    // preserve the receiver's multiplicity, replace only the
    // member type τ. Bare metaclass types are implicitly ¡T! (singleton).
    Type resultType;
    if (receiverType.isCollection() && !receiverType.isSingleton()) {
      // Multi-valued collection {T}, [T], etc. — preserve collection kind
      resultType = Type.preserveCollectionKind(receiverType, targetType);
    } else {
      // Singleton ¡T! or bare metaclass T → result is ¡targetType!
      resultType = Type.singleton(targetType);
    }

    nodeTypes.put(ctx, resultType);
    nodeTypes.put(ctx.type, targetType);
    return resultType;
  }

  // ==================== Metamodel Operations ====================
  /**
   * Type checks the {@code allInstances()} operation.
   *
   * <p>Requires metaclass receiver, returns Set of that metaclass type.
   *
   * <p><b>Example:</b> {@code Person.allInstances()} → OrderedSet(Person)
   *
   * @param ctx The allInstances operation node
   * @return OrderedSet(MetaclassType)
   */
  @Override
  public Type visitAllInstancesOp(VitruvOCLParser.AllInstancesOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }

    if (!receiverType.isMetaclassType()) {
      reportError(ctx, "allInstances() requires metaclass receiver");
      return Type.ERROR;
    }

    Type resultType = Type.orderedSet(receiverType);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== Not Yet Implemented ====================
  /**
   * Type checks correspondence operator (~).
   *
   * <p>The correspondence operator is a binary predicate that checks if two objects are related via
   * a Correspondence model instance. Both operands must be single object instances (not
   * collections, not primitives).
   *
   * <p><b>Syntax:</b> {@code obj1 ~ obj2}
   *
   * <p><b>Type:</b> {@code Boolean}
   *
   * <p><b>Examples:</b>
   *
   * <pre>
   * self ~ sat                                    // Boolean
   * Satellite.allInstances().select(s | self ~ s) // Filtered collection
   * self ~ sat implies sat.active                 // Consistency check
   * </pre>
   *
   * @param ctx Correspondence comparison operation node
   * @return Type.BOOLEAN if operands are valid, Type.ERROR otherwise
   */
  @Override
  public Type visitCorrespondence(VitruvOCLParser.CorrespondenceContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    // Unwrap singletons to get base types
    Type baseLeftType = leftType.isSingleton() ? leftType.getElementType() : leftType;

    // Both sides must be single objects (singleton or object type, not multi-valued collection)
    if (leftType.isCollection() && leftType.getMultiplicity() != Multiplicity.SINGLETON) {
      reportError(
          ctx.left,
          "Correspondence operator ~ requires single object on left side, got collection: "
              + leftType);
      return Type.ERROR;
    }

    if (rightType.isCollection() && rightType.getMultiplicity() != Multiplicity.SINGLETON) {
      reportError(
          ctx.right,
          "Correspondence operator ~ requires single object on right side, got collection: "
              + rightType);
      return Type.ERROR;
    }

    // Both must be metaclass types (not primitives)
    if (!baseLeftType.isMetaclassType() && baseLeftType != Type.ANY) {
      reportError(
          ctx.left,
          "Correspondence operator ~ requires object type on left side, got: " + baseLeftType);
      return Type.ERROR;
    }

    Type baseRightType = rightType.isSingleton() ? rightType.getElementType() : rightType;
    if (!baseRightType.isMetaclassType() && baseRightType != Type.ANY) {
      reportError(
          ctx.right,
          "Correspondence operator ~ requires object type on right side, got: " + baseRightType);
      return Type.ERROR;
    }

    // Result is always Boolean
    Type resultType = Type.BOOLEAN;
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks select(~) / select(~, Type=T, Tag='x') shorthand.
   *
   * <p>Without a Type filter: returns the receiver collection type unchanged. With a Type filter:
   * returns a collection of that metaclass type (same collection kind, narrowed element type).
   *
   * @param ctx The selectCorrespondence node
   * @return Refined or unchanged receiver collection type, or Type.ERROR
   */
  @Override
  public Type visitSelectCorrespondence(VitruvOCLParser.SelectCorrespondenceContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == null || receiverType == Type.ERROR) {
      reportError(ctx, "Cannot determine receiver type for select(~)");
      return Type.ERROR;
    }

    VariableSymbol selfSymbol = symbolTable.resolveVariable("self");
    if (selfSymbol == null) {
      reportError(ctx, "select(~) requires 'self' to be bound in current context");
      return Type.ERROR;
    }

    Type baseSelfType = selfSymbol.getType();
    if (baseSelfType.isSingleton()) {
      baseSelfType = baseSelfType.getElementType();
    }

    if (!baseSelfType.isMetaclassType() && baseSelfType != Type.ANY) {
      reportError(ctx, "select(~) requires 'self' to be an object type, got: " + baseSelfType);
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (!elemType.isMetaclassType() && elemType != Type.ANY) {
      reportError(ctx, "select(~) requires collection of object types, got: " + elemType);
      return Type.ERROR;
    }

    // Visit filter to check options and extract optional Type refinement
    int corrErrsBefore = errors.getErrors().size();
    Type filterType = visit(ctx.corrFilter);
    if (reportCorrFilterParseError(ctx.corrFilter, ctx, OP_SELECT, corrErrsBefore)) {
      return Type.ERROR;
    }

    // If a Type filter was given, narrow the result collection element type
    Type resultType =
        (filterType != Type.ANY && filterType != Type.ERROR)
            ? Type.preserveCollectionKind(receiverType, filterType)
            : receiverType;

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks reject(~) / reject(~, Type=T, Tag='x') shorthand.
   *
   * <p>The type filter does not narrow the result: reject keeps the elements that are not a
   * corresponding T, which includes every non-T element.
   *
   * @param ctx The rejectCorrespondence node
   * @return the unchanged receiver collection type, or Type.ERROR
   */
  @Override
  public Type visitRejectCorrespondence(VitruvOCLParser.RejectCorrespondenceContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == null || receiverType == Type.ERROR) {
      reportError(ctx, "Cannot determine receiver type for reject(~)");
      return Type.ERROR;
    }

    VariableSymbol selfSymbol = symbolTable.resolveVariable("self");
    if (selfSymbol == null) {
      reportError(ctx, "reject(~) requires 'self' to be bound in current context");
      return Type.ERROR;
    }

    Type baseSelfType = selfSymbol.getType();
    if (baseSelfType.isSingleton()) {
      baseSelfType = baseSelfType.getElementType();
    }

    if (!baseSelfType.isMetaclassType() && baseSelfType != Type.ANY) {
      reportError(ctx, "reject(~) requires 'self' to be an object type, got: " + baseSelfType);
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (!elemType.isMetaclassType() && elemType != Type.ANY) {
      reportError(ctx, "reject(~) requires collection of object types, got: " + elemType);
      return Type.ERROR;
    }

    int corrErrsBefore = errors.getErrors().size();
    visit(ctx.corrFilter);
    if (reportCorrFilterParseError(ctx.corrFilter, ctx, OP_REJECT, corrErrsBefore)) {
      return Type.ERROR;
    }

    // No narrowing: reject keeps every element that is not a corresponding T, so the result
    // still contains non-T elements and has the receiver's element type.
    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks exists(~) / exists(~, Type=T, Tag='x') shorthand.
   *
   * <p>Always returns Boolean. Type/Tag filters are validated but don't change the return type.
   *
   * @param ctx The existsCorrespondence node
   * @return Type.BOOLEAN if validation succeeds, Type.ERROR otherwise
   */
  @Override
  public Type visitExistsCorrespondence(VitruvOCLParser.ExistsCorrespondenceContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == null || receiverType == Type.ERROR) {
      reportError(ctx, "Cannot determine receiver type for exists(~)");
      return Type.ERROR;
    }

    VariableSymbol selfSymbol = symbolTable.resolveVariable("self");
    if (selfSymbol == null) {
      reportError(ctx, "exists(~) requires 'self' to be bound in current context");
      return Type.ERROR;
    }

    Type baseSelfType = selfSymbol.getType();
    if (baseSelfType.isSingleton()) {
      baseSelfType = baseSelfType.getElementType();
    }

    if (!baseSelfType.isMetaclassType() && baseSelfType != Type.ANY) {
      reportError(ctx, "exists(~) requires 'self' to be an object type, got: " + baseSelfType);
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (!elemType.isMetaclassType() && elemType != Type.ANY) {
      reportError(ctx, "exists(~) requires collection of object types, got: " + elemType);
      return Type.ERROR;
    }

    // Validate filter options (type-checks the Type= expression if present)
    int corrErrsBefore = errors.getErrors().size();
    visit(ctx.corrFilter);
    if (reportCorrFilterParseError(ctx.corrFilter, ctx, OP_EXISTS, corrErrsBefore)) {
      return Type.ERROR;
    }

    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Checks whether a correspondenceFilterCS subtree had ANTLR parse errors (silent recovery from
   * e.g. extra commas like {@code reject(~, , , Type=T)}). When errors are detected and no
   * TypeChecker error was already reported for the filter, adds a targeted error spanning the full
   * operation call (e.g. the whole {@code reject(~, , , ...)}) and returns {@code true}.
   *
   * @param corrFilter the parsed correspondenceFilterCS context
   * @param callCtx the parent iterator/correspondence call context (for span)
   * @param opName operation name for the message (e.g. "reject")
   * @param errsBefore error count before visiting the filter
   * @return {@code true} if a parse error was detected
   */
  private boolean reportCorrFilterParseError(
      org.antlr.v4.runtime.ParserRuleContext corrFilter,
      org.antlr.v4.runtime.ParserRuleContext callCtx,
      String opName,
      int errsBefore) {
    boolean tcErr = errors.getErrors().size() > errsBefore;
    boolean parseErr = hasErrorNode(corrFilter) || hasExtraCommasInRange(corrFilter);
    if (!parseErr && !tcErr) {
      return false;
    }
    if (!tcErr) {
      // ANTLR recovered silently — no TypeChecker error was emitted yet → report one
      org.antlr.v4.runtime.Token start = callCtx.getStart();
      org.antlr.v4.runtime.Token stop = callCtx.getStop();
      int endLine = stop != null ? stop.getLine() : start.getLine();
      int endCol =
          stop != null
              ? stop.getCharPositionInLine() + stop.getText().length()
              : start.getCharPositionInLine() + callCtx.getText().length();
      errors.add(
          new CompileError(
              start.getLine(),
              start.getCharPositionInLine(),
              endLine,
              endCol,
              "'"
                  + opName
                  + "(~, ...)' has invalid arguments — check for extra or misplaced commas",
              ErrorSeverity.ERROR,
              PHASE_TAG,
              null));
    }
    return true;
  }

  /**
   * Tokens that can start a new expression — if one follows a complete spec, an operator is
   * missing.
   */
  private static final java.util.Set<String> EXPR_START_KEYWORDS =
      java.util.Set.of("self", "true", "false", "not", "if", "let");

  /**
   * Checks whether there are expression-starting tokens immediately after {@code spec.getStop()}
   * that were silently dropped by ANTLR error recovery. This catches patterns like {@code self.a ==
   * "x" persons::Foo.allInstances()...} where the user forgot {@code and}/{@code or}/{@code
   * implies} between two sub-expressions.
   *
   * @param spec the parsed specification context
   * @return {@code true} if a missing-operator error was reported
   */
  private boolean reportMissingOperatorBetweenExpressions(
      org.antlr.v4.runtime.ParserRuleContext spec) {
    if (tokens == null || spec.getStop() == null) {
      return false;
    }
    int idx = nextDefault(spec.getStop().getTokenIndex() + 1);
    if (idx < 0) {
      return false;
    }
    org.antlr.v4.runtime.Token next = tokens.get(idx);
    // Token types that terminate an invCS (not expression starters)
    String txt = next.getText();
    if (",".equals(txt)
        || ")".equals(txt)
        || "context".equals(txt)
        || "inv".equals(txt)
        || "<EOF>".equals(txt)) {
      return false;
    }
    // Is it an expression-starting token?
    boolean isExprStart =
        EXPR_START_KEYWORDS.contains(txt)
            // ID followed by '::' (qualified name) or '.' (navigation) or '(' (call)
            || (txt.matches("[a-zA-Z_]\\w*") && isFollowedByNavOrScope(idx))
            // string literal
            || (txt.startsWith("\"") || txt.startsWith("'"))
            // numeric literal
            || txt.matches("-?\\d+(\\.\\d+)?");
    if (!isExprStart) {
      return false;
    }
    errors.add(
        new CompileError(
            next.getLine(),
            next.getCharPositionInLine(),
            next.getLine(),
            next.getCharPositionInLine() + next.getText().length(),
            "Missing logical operator before this expression — did you mean 'and', 'or', or"
                + " 'implies'?",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));
    return true;
  }

  private boolean isFollowedByNavOrScope(int idIdx) {
    int next = nextDefault(idIdx + 1);
    if (next < 0) {
      return false;
    }
    String t = tokens.get(next).getText();
    return "::".equals(t) || ".".equals(t) || "(".equals(t);
  }

  private static final java.util.Set<String> COMPARISON_OPS =
      java.util.Set.of("==", "!=", "<", "<=", ">", ">=");

  /**
   * Checks whether the token immediately after {@code ctx.getStop()} in the stream is a comparison
   * or arithmetic operator that implies a missing right-hand operand. If so, reports the error ON
   * that operator token and returns {@code true}.
   */
  private boolean reportMissingOperandAfter(org.antlr.v4.runtime.ParserRuleContext ctx) {
    if (tokens == null || ctx.getStop() == null) {
      return false;
    }
    int idx = nextDefault(ctx.getStop().getTokenIndex() + 1);
    if (idx < 0) {
      return false;
    }
    org.antlr.v4.runtime.Token opTok = tokens.get(idx);
    if (!COMPARISON_OPS.contains(opTok.getText())) {
      return false;
    }
    errors.add(
        new CompileError(
            opTok.getLine(),
            opTok.getCharPositionInLine(),
            opTok.getLine(),
            opTok.getCharPositionInLine() + opTok.getText().length(),
            "Missing right-hand operand for '" + opTok.getText() + "'",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));
    return true;
  }

  private static final java.util.Set<String> CORR_OPS_SET =
      java.util.Set.of(OP_REJECT, OP_SELECT, OP_EXISTS);

  /**
   * Looks in the token stream immediately after {@code parsedExpr.getStop()} for an unparsed
   * navigation step of the form {@code .reject(~…)/select(~…)/exists(~…)} that ANTLR failed to
   * match (typically due to extra commas inside the correspondence filter).
   *
   * @return [opTokenIndex, closingParenTokenIndex] if found, or empty array if not found
   */
  private int[] findUnparsedCorrCallAfter(org.antlr.v4.runtime.ParserRuleContext parsedExpr) {
    if (tokens == null || parsedExpr.getStop() == null) {
      return new int[0];
    }
    int idx = parsedExpr.getStop().getTokenIndex() + 1;
    idx = nextDefault(idx);
    if (idx < 0) {
      return new int[0];
    }
    // expect '.'
    if (!".".equals(tokens.get(idx).getText())) {
      return new int[0];
    }
    idx = nextDefault(idx + 1);
    if (idx < 0) {
      return new int[0];
    }
    // expect reject / select / exists
    org.antlr.v4.runtime.Token opTok = tokens.get(idx);
    if (!CORR_OPS_SET.contains(opTok.getText())) {
      return new int[0];
    }
    final int opIdx = idx;
    idx = nextDefault(idx + 1);
    if (idx < 0) {
      return new int[0];
    }
    // expect '('
    if (!"(".equals(tokens.get(idx).getText())) {
      return new int[0];
    }
    // find matching ')'
    int depth = 0;
    for (int i = idx; i < tokens.size(); i++) {
      String txt = tokens.get(i).getText();
      if ("(".equals(txt)) {
        depth++;
      } else if (")".equals(txt)) {
        depth--;
        if (depth == 0) {
          return new int[] {opIdx, i};
        }
      }
    }
    return new int[0];
  }

  /** Returns the index of the next token on the default channel at or after {@code from}, or -1. */
  private int nextDefault(int from) {
    for (int i = from; i < tokens.size(); i++) {
      if (tokens.get(i).getChannel() == org.antlr.v4.runtime.Token.DEFAULT_CHANNEL) {
        return i;
      }
    }
    return -1;
  }

  private boolean hasExtraCommasInRange(org.antlr.v4.runtime.ParserRuleContext ctx) {
    if (tokens == null || ctx.getStart() == null) {
      return false;
    }
    int from = ctx.getStart().getTokenIndex();
    int to = ctx.getStop() != null ? ctx.getStop().getTokenIndex() : from;
    boolean prevWasComma = false;
    for (int i = from; i <= to; i++) {
      org.antlr.v4.runtime.Token t = tokens.get(i);
      if (t.getChannel() != org.antlr.v4.runtime.Token.DEFAULT_CHANNEL) {
        continue;
      }
      if (",".equals(t.getText())) {
        if (prevWasComma) {
          return true; // two consecutive commas
        }
        prevWasComma = true;
      } else {
        prevWasComma = false;
      }
    }
    return false;
  }

  /** Placeholder for message operator (^). */
  @Override
  public Type visitMessage(VitruvOCLParser.MessageContext ctx) {
    Type leftType = visit(ctx.left);

    reportWarning(ctx, "Message operator '^' not yet implemented");

    nodeTypes.put(ctx, leftType);
    return leftType;
  }

  // ==================== Delegation & Miscellaneous ====================
  @Override
  public Type visitPrefixedExpr(VitruvOCLParser.PrefixedExprContext ctx) {
    return visit(ctx.prefixedExpCS());
  }

  /**
   * Visits a literal node in the AST.
   *
   * <p>Delegates type checking to the underlying literal expression.
   *
   * @param ctx the context of the literal node in the AST
   * @return the type of the literal expression
   */
  @Override
  public Type visitLiteral(VitruvOCLParser.LiteralContext ctx) {
    return visit(ctx.literalExpCS());
  }

  /**
   * Visits a conditional (if-then-else) node in the AST.
   *
   * <p>Delegates type checking to the underlying if-expression.
   *
   * @param ctx the context of the conditional node in the AST
   * @return the type of the if-expression
   */
  @Override
  public Type visitConditional(VitruvOCLParser.ConditionalContext ctx) {
    return visit(ctx.ifExpCS());
  }

  /**
   * Visits a let-binding node in the AST.
   *
   * <p>Delegates type checking to the underlying let-expression.
   *
   * @param ctx the context of the let-binding node in the AST
   * @return the type of the let-expression
   */
  @Override
  public Type visitLetBinding(VitruvOCLParser.LetBindingContext ctx) {
    return visit(ctx.letExpCS());
  }

  /**
   * Type-checks a {@code let} expression.
   *
   * <p>Retrieves the {@link Scope} pre-annotated by Pass 1, enters it, then type-checks all
   * variable declarations in order before type-checking each body expression in sequence. The type
   * of the last body expression is registered as the result type of the {@code let} expression.
   *
   * @param ctx the parse tree node for the {@code let} expression, containing the variable
   *     declaration list and one or more body expressions
   * @return the type of the last body expression; or {@link Type#ERROR} if no scope annotation is
   *     found, or the body contains no expressions
   */
  @Override
  public Type visitLetExpCS(VitruvOCLParser.LetExpCSContext ctx) {
    Scope letScope = scopeAnnotator.getScope(ctx);
    if (letScope == null) {
      reportError(ctx, ERR_NO_SCOPE);

      return Type.ERROR;
    }

    symbolTable.enterScope(letScope);

    try {
      visit(ctx.variableDeclarations());

      List<VitruvOCLParser.ExpCSContext> allExps = ctx.expCS();
      Type bodyType = null;
      for (VitruvOCLParser.ExpCSContext exp : allExps) {
        bodyType = visit(exp);
      }

      if (bodyType == null) {
        reportError(ctx, "Let body empty");
        bodyType = Type.ERROR;
      }

      nodeTypes.put(ctx, bodyType);
      return bodyType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Visits a collection literal node in the AST.
   *
   * <p>Delegates type checking to the underlying collection literal expression.
   *
   * @param ctx the context of the collection literal node in the AST
   * @return the type of the collection literal expression
   */
  @Override
  public Type visitCollectionLiteral(VitruvOCLParser.CollectionLiteralContext ctx) {
    return visit(ctx.collectionLiteralExpCS());
  }

  /**
   * Visits a type literal node in the AST.
   *
   * <p>Delegates type checking to the underlying type literal expression.
   *
   * @param ctx the context of the type literal node in the AST
   * @return the type of the type literal expression
   */
  @Override
  public Type visitTypeLiteral(VitruvOCLParser.TypeLiteralContext ctx) {
    return visit(ctx.typeLiteralExpCS());
  }

  /**
   * Visits a nested expression node in the AST.
   *
   * <p>Delegates type checking to the underlying nested expression.
   *
   * @param ctx the context of the nested expression node in the AST
   * @return the type of the nested expression
   */
  @Override
  public Type visitNested(VitruvOCLParser.NestedContext ctx) {
    return visit(ctx.nestedExpCS());
  }

  /**
   * Visits a 'self' expression node in the AST.
   *
   * <p>Delegates type checking to the underlying self expression.
   *
   * @param ctx the context of the self expression node in the AST
   * @return the type of the self expression
   */
  @Override
  public Type visitSelf(VitruvOCLParser.SelfContext ctx) {
    return visit(ctx.selfExpCS());
  }

  /**
   * Visits a variable reference node in the AST.
   *
   * <p>Delegates type checking to the underlying variable expression.
   *
   * @param ctx the context of the variable reference node in the AST
   * @return the type of the variable expression
   */
  @Override
  public Type visitVariable(VitruvOCLParser.VariableContext ctx) {
    return visit(ctx.variableExpCS());
  }

  /**
   * Visits a nested expression node in the AST.
   *
   * <p>A nested expression may contain one or more sub-expressions. This method performs the
   * following steps:
   *
   * <ul>
   *   <li>Checks whether the nested expression contains any sub-expressions; if empty, an error is
   *       reported and {@link Type#ERROR} is returned.
   *   <li>Iterates over all sub-expressions, visiting each one and updating the resulting type to
   *       the type of the last sub-expression.
   *   <li>Stores the resulting type in {@code nodeTypes}.
   * </ul>
   *
   * @param ctx the context of the nested expression node in the AST
   * @return the type of the last sub-expression if any exist; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitNestedExpCS(VitruvOCLParser.NestedExpCSContext ctx) {
    List<VitruvOCLParser.ExpCSContext> exps = ctx.expCS();
    if (exps.isEmpty()) {
      reportError(ctx, "Empty nested expression");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    Type resultType = null;
    for (VitruvOCLParser.ExpCSContext exp : exps) {
      resultType = visit(exp);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== Correspondence Filter ====================

  /**
   * Type checks a correspondence filter expression.
   *
   * <p>A correspondence filter consists of the '~' marker and optional Type/Tag options. This
   * method validates the options and returns the target metaclass type if a Type filter is present,
   * or Type.ANY otherwise.
   *
   * <p><b>Examples:</b>
   *
   * <pre>
   *   ~                                    // no filter
   *   ~, Tag = 'Brother'                  // tag filter only
   *   ~, Type = MM::Person               // type filter only
   *   ~, Type = MM::Person, Tag = 'Sibling'  // both
   * </pre>
   *
   * @param ctx The correspondence filter node
   * @return The resolved target metaclass type, or Type.ANY if no Type filter
   */
  @Override
  public Type visitCorrespondenceFilterCS(VitruvOCLParser.CorrespondenceFilterCSContext ctx) {
    if (ctx.correspondenceOptions() == null) {
      nodeTypes.put(ctx, Type.ANY);
      return Type.ANY;
    }
    Type result = visit(ctx.correspondenceOptions());
    nodeTypes.put(ctx, result);
    return result;
  }

  // ==================== New Iterator Operations ====================

  /** Type checks {@code one()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitOneOp(VitruvOCLParser.OneOpContext ctx) {
    return iterators.one(ctx);
  }

  /** Type checks {@code any()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitAnyOp(VitruvOCLParser.AnyOpContext ctx) {
    return iterators.any(ctx);
  }

  /** Type checks {@code isUnique()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitIsUniqueOp(VitruvOCLParser.IsUniqueOpContext ctx) {
    return iterators.isUnique(ctx);
  }

  /** Type checks {@code sortedBy()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitSortedByOp(VitruvOCLParser.SortedByOpContext ctx) {
    return iterators.sortedBy(ctx);
  }

  /** Type checks {@code collectNested()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitCollectNestedOp(VitruvOCLParser.CollectNestedOpContext ctx) {
    return iterators.collectNested(ctx);
  }

  /** Type checks {@code iterate()}; see {@link IteratorOperationTyping}. */
  @Override
  public Type visitIterateOp(VitruvOCLParser.IterateOpContext ctx) {
    return iterators.iterate(ctx);
  }

  // ==================== New Collection Conversion Operations ====================

  /** Type checks {@code asSet()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitAsSetOp(VitruvOCLParser.AsSetOpContext ctx) {
    return collections.asSet(ctx);
  }

  /** Type checks {@code asBag()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitAsBagOp(VitruvOCLParser.AsBagOpContext ctx) {
    return collections.asBag(ctx);
  }

  /** Type checks {@code asSequence()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitAsSequenceOp(VitruvOCLParser.AsSequenceOpContext ctx) {
    return collections.asSequence(ctx);
  }

  /** Type checks {@code asOrderedSet()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitAsOrderedSetOp(VitruvOCLParser.AsOrderedSetOpContext ctx) {
    return collections.asOrderedSet(ctx);
  }

  // ==================== New Collection Operations ====================

  /** Type checks {@code includesAll()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitIncludesAllOp(VitruvOCLParser.IncludesAllOpContext ctx) {
    return collections.includesAll(ctx);
  }

  /** Type checks {@code excludesAll()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitExcludesAllOp(VitruvOCLParser.ExcludesAllOpContext ctx) {
    return collections.excludesAll(ctx);
  }

  /** Type checks {@code count()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitCountOp(VitruvOCLParser.CountOpContext ctx) {
    return collections.count(ctx);
  }

  /** Type checks {@code intersection()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitIntersectionOp(VitruvOCLParser.IntersectionOpContext ctx) {
    return collections.intersection(ctx);
  }

  /** Type checks {@code symmetricDifference()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitSymmetricDifferenceOp(VitruvOCLParser.SymmetricDifferenceOpContext ctx) {
    return collections.symmetricDifference(ctx);
  }

  /** Type checks {@code prepend()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitPrependOp(VitruvOCLParser.PrependOpContext ctx) {
    return collections.prepend(ctx);
  }

  /** Type checks {@code insertAt()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitInsertAtOp(VitruvOCLParser.InsertAtOpContext ctx) {
    return collections.insertAt(ctx);
  }

  /** Type checks {@code subSequence()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitSubSequenceOp(VitruvOCLParser.SubSequenceOpContext ctx) {
    return collections.subSequence(ctx);
  }

  /** Type checks {@code at()}; see {@link CollectionOperationTyping}. */
  @Override
  public Type visitAtOp(VitruvOCLParser.AtOpContext ctx) {
    return collections.at(ctx);
  }

  /** Type checks {@code ceiling()}; see {@link NumericOperationTyping}. */
  @Override
  public Type visitCeilingOp(VitruvOCLParser.CeilingOpContext ctx) {
    return numerics.ceiling(ctx);
  }

  /** Type checks {@code div()}; see {@link NumericOperationTyping}. */
  @Override
  public Type visitDivOp(VitruvOCLParser.DivOpContext ctx) {
    return numerics.div(ctx);
  }

  /** Type checks {@code mod()}; see {@link NumericOperationTyping}. */
  @Override
  public Type visitModOp(VitruvOCLParser.ModOpContext ctx) {
    return numerics.mod(ctx);
  }

  // ==================== New String Operations ====================

  /** Type checks {@code toInteger()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitToIntegerOp(VitruvOCLParser.ToIntegerOpContext ctx) {
    return strings.toInteger(ctx);
  }

  /** Type checks {@code toReal()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitToRealOp(VitruvOCLParser.ToRealOpContext ctx) {
    return strings.toReal(ctx);
  }

  /** Type checks {@code characters()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitCharactersOp(VitruvOCLParser.CharactersOpContext ctx) {
    return strings.characters(ctx);
  }

  /** Type checks {@code matches()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitMatchesOp(VitruvOCLParser.MatchesOpContext ctx) {
    return strings.matches(ctx);
  }

  /** Type checks {@code substituteAll()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitSubstituteAllOp(VitruvOCLParser.SubstituteAllOpContext ctx) {
    return strings.substituteAll(ctx);
  }

  /** Type checks {@code substituteFirst()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitSubstituteFirstOp(VitruvOCLParser.SubstituteFirstOpContext ctx) {
    return strings.substituteFirst(ctx);
  }

  /** Type checks {@code tokenize()}; see {@link StringOperationTyping}. */
  @Override
  public Type visitTokenizeOp(VitruvOCLParser.TokenizeOpContext ctx) {
    return strings.tokenize(ctx);
  }

  /**
   * Type checks the list of correspondence filter options.
   *
   * <p>Iterates all options, visits each one, and returns the metaclass type found in a Type filter
   * (if any). Tag filters return Type.ANY. If multiple Type filters are present the last one wins
   * (grammar does not forbid it; a warning is issued).
   *
   * @param ctx The correspondence options node
   * @return The resolved target metaclass type, or Type.ANY if only Tag options present
   */
  @Override
  public Type visitCorrespondenceOptions(VitruvOCLParser.CorrespondenceOptionsContext ctx) {
    Type resolvedType = Type.ANY;
    boolean hadTypeFilter = false;

    for (VitruvOCLParser.CorrespondenceOptionContext option : ctx.correspondenceOption()) {
      Type optType = visit(option);
      if (optType != Type.ANY) {
        if (hadTypeFilter) {
          reportWarning(
              option, "Duplicate Type filter in correspondence — only one Type filter allowed");
        }
        resolvedType = optType;
        hadTypeFilter = true;
      }
    }

    nodeTypes.put(ctx, resolvedType);
    return resolvedType;
  }

  /**
   * Type checks a 'Type = ...' correspondence filter option.
   *
   * <p>Resolves the given type expression to a metaclass type and validates that it is indeed a
   * metaclass (not a primitive). Returns the resolved metaclass type so the evaluator can use it
   * for instanceof filtering.
   *
   * @param ctx The corrTypeFilter node
   * @return The resolved metaclass type, or Type.ERROR if invalid
   */
  @Override
  public Type visitCorrTypeFilter(VitruvOCLParser.CorrTypeFilterContext ctx) {
    Type targetType = visit(ctx.type);

    if (targetType == Type.ERROR) {
      return Type.ERROR;
    }

    // Unwrap singleton if needed (e.g. !Person! → Person)
    Type baseType = targetType.isSingleton() ? targetType.getElementType() : targetType;

    if (!baseType.isMetaclassType() && baseType != Type.ANY) {
      org.antlr.v4.runtime.Token start = ctx.getStart();
      org.antlr.v4.runtime.Token stop = ctx.getStop();
      int endCol =
          (stop != null
              ? stop.getCharPositionInLine() + stop.getText().length()
              : start.getCharPositionInLine() + start.getText().length());
      errors.add(
          new CompileError(
              start.getLine(),
              start.getCharPositionInLine(),
              (stop != null ? stop.getLine() : start.getLine()),
              endCol,
              "Type filter must be a metaclass (e.g. persons::Person), got: " + baseType,
              ErrorSeverity.ERROR,
              PHASE_TAG,
              null));
      return Type.ERROR;
    }

    nodeTypes.put(ctx, baseType);
    return baseType;
  }

  /**
   * Type checks a 'Tag = ...' correspondence filter option.
   *
   * <p>Tags are plain string literals — no further type validation needed. Returns Type.ANY to
   * signal to the caller that no metaclass type was produced.
   *
   * @param ctx The corrTagFilter node
   * @return Type.ANY (tag carries no type information)
   */
  @Override
  public Type visitUnknownFilterOption(VitruvOCLParser.UnknownFilterOptionContext ctx) {
    visit(ctx.badVal); // visit value so its own errors surface

    String typed = ctx.badKey.getText();
    java.util.List<String> validKeys = java.util.List.of("Tag", "Type");

    String best =
        validKeys.stream()
            .min(
                java.util.Comparator.comparingInt(
                    k ->
                        EditDistance.damerauLevenshtein(
                            typed.toLowerCase(java.util.Locale.ROOT),
                            k.toLowerCase(java.util.Locale.ROOT))))
            .orElse(null);

    int dist =
        best == null
            ? Integer.MAX_VALUE
            : EditDistance.damerauLevenshtein(
                typed.toLowerCase(java.util.Locale.ROOT), best.toLowerCase(java.util.Locale.ROOT));

    String message =
        dist <= 2
            ? "Unknown filter option '" + typed + ERR_DID_YOU_MEAN + best + "'?"
            : "Unknown filter option '" + typed + "' — valid options are 'Tag' and 'Type'";

    org.antlr.v4.runtime.Token tok = ctx.badKey;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            best));

    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  @Override
  public Type visitInvalidFilterArg(VitruvOCLParser.InvalidFilterArgContext ctx) {
    org.antlr.v4.runtime.Token tok = ctx.badArg;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            "Invalid filter argument '"
                + tok.getText()
                + "' — expected 'Type = <type>' or 'Tag = <string>'",
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null));
    return Type.ERROR;
  }

  @Override
  public Type visitCorrTagFilter(VitruvOCLParser.CorrTagFilterContext ctx) {
    Type tagType = visit(ctx.tag);
    // Unwrap singleton wrapper to compare bare primitive type
    Type bare = (tagType != null && tagType.isSingleton()) ? tagType.getElementType() : tagType;
    if (bare != null && bare != Type.STRING && bare != Type.ANY && bare != Type.ERROR) {
      org.antlr.v4.runtime.Token start = ctx.getStart();
      org.antlr.v4.runtime.Token stop = ctx.getStop();
      int endCol =
          (stop != null
              ? stop.getCharPositionInLine() + stop.getText().length()
              : start.getCharPositionInLine() + start.getText().length());
      errors.add(
          new CompileError(
              start.getLine(),
              start.getCharPositionInLine(),
              (stop != null ? stop.getLine() : start.getLine()),
              endCol,
              "Tag filter value must be String, got " + bare,
              ErrorSeverity.ERROR,
              PHASE_TAG,
              null));
    }
    nodeTypes.put(ctx, Type.ANY);
    return Type.ANY;
  }

  /**
   * Visits a type literal expression node in the AST.
   *
   * <p>This method delegates type checking to the underlying type literal node. The type literal
   * represents a type as an expression (e.g., a class or primitive type).
   *
   * @param ctx the context of the type literal expression node in the AST
   * @return the type of the underlying type literal
   */
  @Override
  public Type visitTypeLiteralExpCS(VitruvOCLParser.TypeLiteralExpCSContext ctx) {
    return visit(ctx.typeLiteralCS());
  }

  /**
   * Visits a property access node in the AST.
   *
   * <p>This method performs type checking for property access expressions, e.g., accessing a field
   * or attribute of an object or element of a collection.
   *
   * @param ctx the context of the property access node in the AST
   * @return the type of the accessed property
   */
  @Override
  public Type visitPropertyAccess(VitruvOCLParser.PropertyAccessContext ctx) {
    Type receiverType = receiverStack.peek();
    if (receiverType == Type.ERROR) {
      return Type.ERROR; // already reported upstream
    }
    String propertyName = ctx.propertyName.getText();
    Type resultType = typeCheckPropertyAccess(receiverType, propertyName, ctx.propertyName);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Visits a collection operation node in the AST.
   *
   * <p>Delegates type checking to the underlying collection operation expression.
   *
   * @param ctx the context of the collection operation node in the AST
   * @return the type of the collection operation expression
   */
  @Override
  public Type visitCollectionOperation(VitruvOCLParser.CollectionOperationContext ctx) {
    return visit(ctx.collectionOpCS());
  }

  /**
   * Visits a string operation node in the AST.
   *
   * <p>Delegates type checking to the underlying string operation expression.
   *
   * @param ctx the context of the string operation node in the AST
   * @return the type of the string operation expression
   */
  @Override
  public Type visitStringOperation(VitruvOCLParser.StringOperationContext ctx) {
    return visit(ctx.stringOpCS());
  }

  /**
   * Visits an iterator operation node in the AST.
   *
   * <p>Delegates type checking to the underlying iterator operation expression.
   *
   * @param ctx the context of the iterator operation node in the AST
   * @return the type of the iterator operation expression
   */
  @Override
  public Type visitIteratorOperation(VitruvOCLParser.IteratorOperationContext ctx) {
    return visit(ctx.iteratorOpCS());
  }

  /**
   * Visits a type operation node in the AST.
   *
   * <p>Delegates type checking to the underlying type operation expression.
   *
   * @param ctx the context of the type operation node in the AST
   * @return the type of the type operation expression
   */
  @Override
  public Type visitTypeOperation(VitruvOCLParser.TypeOperationContext ctx) {
    return visit(ctx.typeOpCS());
  }

  // ==================== Unknown Operator Catch-All ====================

  /** Correspondence iterator operations — candidates for unknownCorrOp suggestions. */
  private static final java.util.List<String> CORR_OPS =
      java.util.List.of(OP_SELECT, OP_REJECT, OP_EXISTS);

  /**
   * Reports a precise error when a correspondence-style call like {@code selcft(~, ...)} is used
   * but the operation name is not one of the known correspondence iterators.
   *
   * <p>Without this rule the grammar would try to parse the {@code ~} as an expression argument
   * inside {@code unknownOpCS} and produce a cryptic "mismatched input '~' expecting {...}"
   * message. This alternative catches the pattern before that happens.
   */
  @Override
  public Type visitUnknownCorrOp(VitruvOCLParser.UnknownCorrOpContext ctx) {
    // Still validate the filter so that Tag/Type errors are reported correctly.
    visit(ctx.corrFilter);

    String typed = ctx.opName.getText();
    String typedLow = typed.toLowerCase(java.util.Locale.ROOT);

    String best =
        CORR_OPS.stream()
            .min(
                java.util.Comparator.comparingInt(
                    k ->
                        EditDistance.damerauLevenshtein(
                            typedLow, k.toLowerCase(java.util.Locale.ROOT))))
            .orElse(null);
    int dist =
        best == null
            ? Integer.MAX_VALUE
            : EditDistance.damerauLevenshtein(typedLow, best.toLowerCase(java.util.Locale.ROOT));

    int threshold = EditDistance.editThreshold(typed.length());
    String message =
        dist <= threshold
            ? ERR_UNKNOWN_OP + typed + ERR_DID_YOU_MEAN + best + "'?"
            : ERR_UNKNOWN_OP
                + typed
                + ERR_OP_DOES_NOT_EXIST
                + " (valid correspondence ops: select, reject, exists)";

    org.antlr.v4.runtime.Token tok = ctx.opName;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            best));

    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  /** Keyword binary operators — candidates for "did you mean?" suggestions. */
  private static final java.util.List<String> KNOWN_BINARY_OPS =
      java.util.List.of("and", "or", "xor", "implies");

  @Override
  public Type visitUnknownBinaryOp(VitruvOCLParser.UnknownBinaryOpContext ctx) {
    visit(ctx.left);
    visit(ctx.right);

    String typed = ctx.op.getText();
    String typedLow = typed.toLowerCase(java.util.Locale.ROOT);

    // Find the closest keyword operator (case-insensitive Levenshtein)
    String best =
        KNOWN_BINARY_OPS.stream()
            .min(
                java.util.Comparator.comparingInt(
                    k ->
                        EditDistance.damerauLevenshtein(
                            typedLow, k.toLowerCase(java.util.Locale.ROOT))))
            .orElse(null);
    int dist =
        best == null
            ? Integer.MAX_VALUE
            : EditDistance.damerauLevenshtein(typedLow, best.toLowerCase(java.util.Locale.ROOT));

    // Use "did you mean" when the name is close enough; threshold scales with length
    int threshold = EditDistance.editThreshold(typed.length());
    String message;
    String suggestion;
    if (dist <= threshold) {
      message = "Unknown operator '" + typed + ERR_DID_YOU_MEAN + best + "'?";
      suggestion = best;
    } else {
      message = "Unknown operator '" + typed + "' — this operator does not exist";
      suggestion = best; // still offer a Quick Fix to the closest keyword op
    }

    org.antlr.v4.runtime.Token tok = ctx.op;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            suggestion));

    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  @Override
  public Type visitUnknownOperation(VitruvOCLParser.UnknownOperationContext ctx) {
    return visit(ctx.unknownOpCS());
  }

  @Override
  public Type visitUnknownOpCS(VitruvOCLParser.UnknownOpCSContext ctx) {
    for (VitruvOCLParser.ExpCSContext arg : ctx.args) {
      visit(arg);
    }
    String typed = ctx.opName.getText();
    java.util.Optional<String> close = suggestOperation(typed); // within threshold
    String best = bestOperation(typed); // unconditional best

    String message;
    String suggestion; // stored for Quick Fix — always the best candidate
    if (close.isPresent()) {
      // Close enough: "did you mean?"
      message = ERR_UNKNOWN_OP + typed + ERR_DID_YOU_MEAN + close.get() + "'?";
      suggestion = close.get();
    } else if (best != null) {
      // Too far for a confident suggestion, but we still offer a fix in the tooltip
      message = ERR_UNKNOWN_OP + typed + ERR_OP_DOES_NOT_EXIST;
      suggestion = best;
    } else {
      message = ERR_UNKNOWN_OP + typed + ERR_OP_DOES_NOT_EXIST;
      suggestion = null;
    }

    org.antlr.v4.runtime.Token tok = ctx.opName;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            PHASE_TAG,
            null,
            suggestion));

    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  // ==================== Operation Name Suggestion ====================

  /**
   * All operation names known to VitruvOCL, from the shared {@link OclOperations} catalog.
   *
   * <p>Used by {@link #suggestOperation} to propose the closest match when an unknown operation
   * name is encountered.
   */
  private static final java.util.List<String> KNOWN_OPERATIONS = OclOperations.names();

  /**
   * Returns the single closest known operation name to {@code typed}, regardless of distance.
   *
   * <p>Used to populate the Quick Fix even when the name is too different for a confident "did you
   * mean?" message. Returns {@code null} only when {@code KNOWN_OPERATIONS} is empty.
   */
  private static String bestOperation(String typed) {
    String lower = typed.toLowerCase(java.util.Locale.ROOT);
    String best = null;
    int bestDist = Integer.MAX_VALUE;
    for (String known : KNOWN_OPERATIONS) {
      int dist = EditDistance.damerauLevenshtein(lower, known.toLowerCase(java.util.Locale.ROOT));
      if (dist < bestDist) {
        bestDist = dist;
        best = known;
      }
    }
    return best;
  }

  /**
   * Returns the closest known operation name to {@code typed} if it is within the edit-distance
   * threshold, otherwise returns empty.
   *
   * <p>Threshold scales with name length:
   *
   * <ul>
   *   <li>1–3 chars → max distance 1
   *   <li>4–6 chars → max distance 2
   *   <li>7+ chars → max distance 3
   * </ul>
   *
   * <p>Comparison is case-insensitive so "Select" suggests "select".
   */
  private static java.util.Optional<String> suggestOperation(String typed) {
    int threshold = EditDistance.editThreshold(typed.length());
    String lower = typed.toLowerCase(java.util.Locale.ROOT);

    String best = null;
    int bestDist = threshold + 1;

    for (String known : KNOWN_OPERATIONS) {
      int dist = EditDistance.damerauLevenshtein(lower, known.toLowerCase(java.util.Locale.ROOT));
      if (dist < bestDist) {
        bestDist = dist;
        best = known;
      }
    }

    return java.util.Optional.ofNullable(best);
  }

  /** Recursively checks whether any node in the subtree is an ANTLR {@link ErrorNode}. */
  static boolean hasErrorNode(ParseTree tree) {
    if (tree instanceof ErrorNode) {
      return true;
    }
    if (tree instanceof org.antlr.v4.runtime.ParserRuleContext prc && prc.exception != null) {
      return true;
    }
    for (int i = 0; i < tree.getChildCount(); i++) {
      if (hasErrorNode(tree.getChild(i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Visits a 'onespace' lexical token in the AST.
   *
   * <p>This node represents a whitespace token and has type {@link Type#ANY}.
   *
   * @param ctx the context of the onespace token in the AST
   * @return {@link Type#ANY} since it represents a lexical token
   */
  @Override
  public Type visitOnespace(VitruvOCLParser.OnespaceContext ctx) {
    return Type.ANY; // Lexical token
  }

  // ==================== Accessors ====================
  /**
   * Returns the computed type annotations for the parse tree.
   *
   * <p>This property is used by the evaluation phase to access pre-computed type information for
   *
   * <p>type-dependent operations.
   *
   * @return The parse tree property mapping nodes to types
   */
  public ParseTreeProperty<Type> getNodeTypes() {
    return nodeTypes;
  }

  /**
   * Sets the token stream for keyword-based parsing.
   *
   * <p>Required for {@link #visitIfExpCS} to determine expression partitioning.
   *
   * @param tokens The ANTLR token stream
   */
  public void setTokenStream(org.antlr.v4.runtime.TokenStream tokens) {
    this.tokens = tokens;
    // Eagerly fill the token buffer so all tokens are accessible by index,
    // even tokens that ANTLR's error recovery consumed (or skipped) during parsing.
    if (tokens instanceof org.antlr.v4.runtime.BufferedTokenStream bts) {
      bts.fill();
    }
  }

  /** Exposes this visitor's state to the operation-family type checkers. */
  private final class TypingContext implements OperationTypingContext {
    @Override
    public Type receiver() {
      return receiverStack.peek();
    }

    @Override
    public Type check(ParserRuleContext expression) {
      return visit(expression);
    }

    @Override
    public Type checkInitializer(ParserRuleContext initializer, VariableSymbol... unbound) {
      return TypeCheckVisitor.this.checkInitializer(initializer, unbound);
    }

    @Override
    public void error(ParserRuleContext ctx, String message) {
      reportError(ctx, message);
    }

    @Override
    public void warning(ParserRuleContext ctx, String message) {
      reportWarning(ctx, message);
    }

    @Override
    public void recordType(ParserRuleContext ctx, Type type) {
      nodeTypes.put(ctx, type);
    }

    @Override
    public SymbolTable symbolTable() {
      return symbolTable;
    }

    @Override
    public ScopeAnnotator scopes() {
      return scopeAnnotator;
    }
  }
}
