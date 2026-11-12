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

package tools.vitruv.dsls.vitruvocl.symboltable;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.AbstractPhaseVisitor;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.ErrorSeverity;
import tools.vitruv.dsls.vitruvocl.pipeline.MetamodelWrapperInterface;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Phase 1 visitor that constructs the symbol table by collecting variable declarations and building
 * scope hierarchies.
 *
 * <p>This is the first pass in the OCL compiler's 3-pass architecture, executed before type
 * checking (Pass 2) and evaluation (Pass 3). It walks the ANTLR parse tree to populate the symbol
 * table with all variable bindings and their declared types.
 *
 * <h2>Core Responsibilities</h2>
 *
 * <ul>
 *   <li><b>Scope management:</b> Creates nested scope hierarchy (global, context, let, iterator)
 *   <li><b>Variable definition:</b> Registers variables in appropriate scopes (self, let variables,
 *       iterator variables)
 *   <li><b>Duplicate detection:</b> Reports errors when variables are redeclared in same scope
 *       (shadowing parent scopes is allowed)
 *   <li><b>Type resolution:</b> Resolves declared types from grammar nodes (primitives,
 *       collections, metamodel types)
 *   <li><b>Parse tree annotation:</b> Attaches scope information to grammar nodes for Pass 2 lookup
 * </ul>
 *
 * <h2>What This Pass Does NOT Do</h2>
 *
 * <ul>
 *   <li><b>Type inference:</b> Iterator variables get placeholder Type.ANY (refined in Pass 2)
 *   <li><b>Type checking:</b> No conformance validation or operation signature checking
 *   <li><b>Expression evaluation:</b> Does not compute values or execute operations
 *   <li><b>Cross-reference resolution:</b> Does not validate that referenced variables exist (Pass
 *       2 responsibility)
 * </ul>
 *
 * <h2>Scope Hierarchy Example</h2>
 *
 * <pre>{@code
 * context Person inv:                    // Creates context scope
 *   let x = 5 in                         //   Creates let scope (child of context)
 *     self.friends.select(f |            //     Creates iterator scope (child of let)
 *       f.age > x)
 *
 * Resulting scope tree:
 * GlobalScope
 *   └─ ContextScope(Person)              [self: Person]
 *        └─ LetScope                     [x: Integer]
 *             └─ IteratorScope           [f: Person]
 * }</pre>
 *
 * <h2>Variable Shadowing Rules</h2>
 *
 * <p>Inner scopes can shadow variables from outer scopes, but redeclaration within the same scope
 * is an error:
 *
 * <pre>{@code
 * let x = 5 in              // Defines x in let scope
 *   let x = "hello" in      // ✓ ALLOWED: shadows outer x
 *     x                     // Resolves to inner x: String
 *
 * let x = 5, x = 10 in      // ✗ ERROR: duplicate in same scope
 *   x
 * }</pre>
 *
 * <h2>Type Resolution Strategy</h2>
 *
 * <p>Pass 1 performs basic type resolution for declared types but defers full type inference:
 *
 * <ul>
 *   <li><b>Primitive types:</b> Resolved immediately (Integer, String, Boolean, Real)
 *   <li><b>Collection types:</b> Element type resolved recursively (Set{Integer}, Sequence[T])
 *   <li><b>Metamodel types:</b> Looked up via TypeRegistry (spacemissionmodel::Spacecraft)
 *   <li><b>Iterator variables:</b> Assigned Type.ANY placeholder (refined to element type in Pass
 *       2)
 *   <li><b>Unresolvable types:</b> Default to Type.ANY to allow compilation to continue
 * </ul>
 *
 * <h2>Usage in Compiler Pipeline</h2>
 *
 * <pre>{@code
 * // Initialize components
 * ErrorCollector errors = new ErrorCollector();
 * SymbolTable symbolTable = new SymbolTableImpl(metamodelWrapper);
 * ScopeAnnotator scopeAnnotator = new ScopeAnnotator();
 *
 * // Pass 1: Symbol Table Construction
 * SymbolTableBuilder builder = new SymbolTableBuilder(
 *     symbolTable, metamodelWrapper, errors, scopeAnnotator);
 * builder.visit(parseTree);
 *
 * if (errors.hasErrors()) {
 *     // Report errors and abort compilation
 *     return;
 * }
 *
 * // Pass 2: Type Checking (uses completed symbol table)
 * TypeCheckVisitor typeChecker = new TypeCheckVisitor(
 *     symbolTable, metamodelWrapper, errors, scopeAnnotator);
 * typeChecker.visit(parseTree);
 * }</pre>
 *
 * <h2>Parse Tree Annotation</h2>
 *
 * <p>This pass annotates grammar nodes with their associated scopes using {@link ScopeAnnotator}.
 * Pass 2 retrieves these annotations to determine which scope to use for variable lookups.
 *
 * <h2>Error Handling</h2>
 *
 * <p>Errors are collected but do not halt traversal. This allows detecting multiple errors in a
 * single pass. Common errors:
 *
 * <ul>
 *   <li>Duplicate variable in same scope
 *   <li>Unknown context type in context declaration
 *   <li>Invalid context declaration syntax
 *   <li>Iterator without variables
 * </ul>
 *
 * @author Max
 * @see SymbolTable The symbol table being populated
 * @see "TypeCheckVisitor: Pass 2 type checking visitor"
 * @see ScopeAnnotator Parse tree scope annotation utility
 * @see AbstractPhaseVisitor Base class with common error handling
 */
public class SymbolTableBuilder extends AbstractPhaseVisitor<Void> {

  private static final String PHASE_TAG = "symbol-table-builder";

  /** Utility for annotating parse tree nodes with scope references for Pass 2 lookup. */
  private final ScopeAnnotator scopeAnnotator;

  /**
   * Constructs a SymbolTableBuilder for Phase 1 symbol table construction.
   *
   * @param st The symbol table to populate with variable definitions
   * @param wrapper The metamodel wrapper for resolving metamodel types
   * @param errors The error collector for reporting symbol-related errors
   * @param scopeAnnotator The scope annotator for attaching scope info to parse tree nodes
   */
  public SymbolTableBuilder(
      SymbolTable st,
      MetamodelWrapperInterface wrapper,
      ErrorCollector errors,
      ScopeAnnotator scopeAnnotator) {
    super(st, wrapper, errors);
    this.scopeAnnotator = scopeAnnotator;
  }

  /**
   * Handles undefined symbol errors during symbol table construction.
   *
   * <p>Note: This should rarely occur in Pass 1 since we're defining symbols, not looking them up.
   * Most undefined symbol errors occur in Pass 2 during type checking.
   *
   * @param name The undefined symbol name
   * @param ctx The parse tree context where the error occurred
   */
  @Override
  protected void handleUndefinedSymbol(String name, ParserRuleContext ctx) {
    errors.add(
        ctx.getStart().getLine(),
        ctx.getStart().getCharPositionInLine(),
        "Undefined symbol in Pass 1: " + name,
        ErrorSeverity.ERROR,
        PHASE_TAG);
  }

  @Override
  protected String phaseTag() {
    return PHASE_TAG;
  }

  // ==================== Context Declaration ====================

  /**
   * Creates a context scope and defines the 'self' variable.
   *
   * <p>This handles both qualified and unqualified context declarations:
   *
   * <pre>{@code
   * context Person inv:                    // Unqualified
   *   self.age >= 0
   *
   * context model::Employee inv:           // Qualified with metamodel name
   *   self.salary > 0
   * }</pre>
   *
   * <p>The context type must be resolvable via the TypeRegistry (either a primitive or a metamodel
   * type). If resolution fails, an error is reported and processing continues.
   *
   * <p>The created context scope is annotated on the parse tree node for Pass 2 retrieval.
   *
   * @param ctx The classifier context parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitClassifierContextCS(VitruvOCLParser.ClassifierContextCSContext ctx) {
    // Resolve context type (qualified or unqualified name)
    Type contextType;
    if (ctx.metamodel != null && ctx.className != null) {
      // Qualified: metamodel::ClassName
      String qualifiedName = ctx.metamodel.getText() + "::" + ctx.className.getText();
      contextType = symbolTable.lookupType(qualifiedName);
    } else if (ctx.contextName != null) {
      // Unqualified: ClassName
      contextType = symbolTable.lookupType(ctx.contextName.getText());
    } else {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Invalid context declaration",
          ErrorSeverity.ERROR,
          PHASE_TAG);
      return null;
    }

    if (contextType == null) {
      if (ctx.metamodel != null && ctx.className != null) {
        String mmName = ctx.metamodel.getText();
        String clsName = ctx.className.getText();
        if (!specification.getAvailableMetamodels().contains(mmName)) {
          errors.add(
              ctx.metamodel, "Unknown metamodel '" + mmName + "'", ErrorSeverity.ERROR, PHASE_TAG);
        } else {
          errors.add(
              ctx.className,
              "Unknown class '" + clsName + "' in metamodel '" + mmName + "'",
              ErrorSeverity.ERROR,
              PHASE_TAG);
        }
      } else if (ctx.contextName != null) {
        errors.add(
            ctx.contextName,
            "Unknown context type '" + ctx.contextName.getText() + "'",
            ErrorSeverity.ERROR,
            PHASE_TAG);
      }
      return null;
    }

    // Create context scope as child of global scope
    Scope contextScope = new LocalScope(symbolTable.getCurrentScope());
    symbolTable.enterScope(contextScope);
    symbolTable.defineVariable(new VariableSymbol("self", contextType, contextScope, false));

    // Operation-context params (context Type::operation(name: Type, ...)) are type-checked as
    // ordinary variables in pre/post bodies. There is no method-invocation mechanism in this
    // codebase's evaluator, so at evaluation time these are bound to an empty value rather than a
    // real argument — see EvaluationVisitor.
    if (ctx.operationParamListCS() != null) {
      for (VitruvOCLParser.OperationParamCSContext param :
          ctx.operationParamListCS().operationParamCS()) {
        Type paramType = resolveTypeExpression(param.paramType);
        symbolTable.defineVariable(
            new VariableSymbol(param.paramName.getText(), paramType, contextScope, false));
      }
    }

    // Annotate parse tree node with scope for Pass 2 variable lookup
    scopeAnnotator.annotate(ctx, contextScope);

    try {
      // Visit all invariants/preconditions/postconditions to collect nested let/iterator variables
      for (VitruvOCLParser.InvCSContext inv : ctx.invCS()) {
        visit(inv);
      }
      for (VitruvOCLParser.PreCSContext pre : ctx.preCS()) {
        visit(pre);
      }
      for (VitruvOCLParser.PostCSContext post : ctx.postCS()) {
        visit(post);
      }
      return null;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Visits an invariant declaration node.
   *
   * <p>Delegates to specification nodes to process constraint expressions.
   *
   * @param ctx The invariant parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitInvCS(VitruvOCLParser.InvCSContext ctx) {
    for (VitruvOCLParser.SpecificationCSContext spec : ctx.specificationCS()) {
      visit(spec);
    }
    return null;
  }

  /**
   * Visits a precondition declaration node. Mirrors {@link #visitInvCS}.
   *
   * @param ctx The precondition parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitPreCS(VitruvOCLParser.PreCSContext ctx) {
    for (VitruvOCLParser.SpecificationCSContext spec : ctx.specificationCS()) {
      visit(spec);
    }
    return null;
  }

  /**
   * Visits a postcondition declaration node. Mirrors {@link #visitInvCS}.
   *
   * @param ctx The postcondition parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitPostCS(VitruvOCLParser.PostCSContext ctx) {
    for (VitruvOCLParser.SpecificationCSContext spec : ctx.specificationCS()) {
      visit(spec);
    }
    return null;
  }

  // ==================== Let Expressions ====================

  /**
   * Creates a let scope and defines let-bound variables.
   *
   * <p>Let expressions introduce new variables scoped to their body:
   *
   * <pre>{@code
   * let x = 5, y = "hello" in     // Creates scope, defines x: Integer, y: String
   *   x + y.size()                // Body uses let variables
   * }</pre>
   *
   * <p>Let variables can shadow variables from outer scopes but cannot duplicate variables within
   * the same let declaration.
   *
   * <p>Type resolution for let variables:
   *
   * <ul>
   *   <li>Explicit type annotation: {@code let x: Integer = 5} uses Integer
   *   <li>No annotation: {@code let x = 5} uses Type.ANY in Pass 1 (inferred in Pass 2)
   * </ul>
   *
   * @param ctx The let expression parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitLetExpCS(VitruvOCLParser.LetExpCSContext ctx) {
    // Create let scope as child of current scope
    LocalScope letScope = new LocalScope(symbolTable.getCurrentScope());
    symbolTable.enterScope(letScope);

    // Annotate parse tree node with scope for Pass 2 variable lookup
    scopeAnnotator.annotate(ctx, letScope);

    try {
      // Process variable declarations (defines variables in let scope)
      visit(ctx.variableDeclarations());

      // Visit body expressions to collect nested lets/iterators
      for (VitruvOCLParser.ExpCSContext exp : ctx.expCS()) {
        visit(exp);
      }

      return null;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Defines a let-bound variable in the current scope.
   *
   * <p>Handles both explicitly typed and untyped variable declarations:
   *
   * <pre>{@code
   * let x: Integer = 5         // Explicit type: uses Integer
   * let y = "hello"            // No type: uses Type.ANY (inferred in Pass 2)
   * }</pre>
   *
   * <p>Performs duplicate checking within the current scope only - shadowing outer scope variables
   * is allowed:
   *
   * <pre>{@code
   * let x = 5 in
   *   let x = "hello" in      // ✓ ALLOWED: shadows outer x
   *     x
   *
   * let x = 5, x = 10 in      // ✗ ERROR: duplicate in same scope
   *   x
   * }</pre>
   *
   * @param ctx The variable declaration parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitVariableDeclaration(VitruvOCLParser.VariableDeclarationContext ctx) {
    String varName = ctx.varName.getText();

    // Check for duplicate ONLY in current scope (allow shadowing of parent scopes)
    LocalScope currentScope = (LocalScope) symbolTable.getCurrentScope();
    if (currentScope.hasVariableInCurrentScope(varName)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Variable '" + varName + "' already defined in this scope",
          ErrorSeverity.ERROR,
          PHASE_TAG);
      return null;
    }

    // Resolve declared type (or use Type.ANY if no type annotation)
    Type declaredType = Type.ANY;
    if (ctx.varType != null) {
      declaredType = resolveTypeExpression(ctx.varType);
    }

    // Define variable in current scope
    symbolTable.defineVariable(new VariableSymbol(varName, declaredType, currentScope, false));

    // Visit initializer to collect nested lets/iterators
    visit(ctx.varInit);

    return null;
  }

  /**
   * Resolves a type expression from the grammar to a Type object.
   *
   * <p>Handles both named types and type literals:
   *
   * <ul>
   *   <li>Type names: {@code Integer}, {@code model::Employee}
   *   <li>Type literals: {@code Set{Integer}}, {@code Sequence[String]}
   * </ul>
   *
   * <p>Returns Type.ANY if resolution fails to allow compilation to continue.
   *
   * @param ctx The type expression parse tree node
   * @return The resolved Type, or Type.ANY if unresolvable
   */
  private Type resolveTypeExpression(VitruvOCLParser.TypeExpCSContext ctx) {
    if (ctx.typeNameExpCS() != null) {
      return resolveTypeName(ctx.typeNameExpCS());
    }
    if (ctx.typeLiteralCS() != null) {
      return resolveTypeLiteral(ctx.typeLiteralCS());
    }
    return Type.ANY;
  }

  /**
   * Resolves a type name (qualified or unqualified).
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code Integer} → Type.INTEGER
   *   <li>{@code String} → Type.STRING
   *   <li>{@code model::Employee} → looked up in TypeRegistry
   * </ul>
   *
   * @param ctx The type name parse tree node
   * @return The resolved Type, or Type.ANY if unresolvable
   */
  private Type resolveTypeName(VitruvOCLParser.TypeNameExpCSContext ctx) {
    String typeName;

    if (ctx.metamodel != null && ctx.className != null) {
      // Qualified: metamodel::ClassName
      typeName = ctx.metamodel.getText() + "::" + ctx.className.getText();
    } else if (ctx.unqualified != null) {
      // Unqualified: ClassName
      typeName = ctx.unqualified.getText();
    } else {
      return Type.ANY;
    }

    // Primitive names (Double, Float, ...) and metamodel types; see SymbolTable#lookupType
    Type resolvedType = symbolTable.lookupType(typeName);
    return (resolvedType != null) ? resolvedType : Type.ANY;
  }

  /**
   * Resolves a type literal (primitive or collection type).
   *
   * @param ctx The type literal parse tree node
   * @return The resolved Type, or Type.ANY if unresolvable
   */
  private Type resolveTypeLiteral(VitruvOCLParser.TypeLiteralCSContext ctx) {
    if (ctx.primitiveTypeCS() != null) {
      return resolvePrimitiveType(ctx.primitiveTypeCS());
    }
    if (ctx.collectionTypeCS() != null) {
      return resolveCollectionType(ctx.collectionTypeCS());
    }
    return Type.ANY;
  }

  /**
   * Resolves a primitive type literal.
   *
   * <p>Maps OCL primitive type names to OCL Type constants:
   *
   * <ul>
   *   <li>Boolean → Type.BOOLEAN
   *   <li>Integer, UnlimitedNatural → Type.INTEGER
   *   <li>Real → Type.DOUBLE
   *   <li>String, ID → Type.STRING
   *   <li>OclAny → Type.ANY
   * </ul>
   *
   * @param ctx The primitive type parse tree node
   * @return The primitive Type constant
   */
  private Type resolvePrimitiveType(VitruvOCLParser.PrimitiveTypeCSContext ctx) {
    Type primitiveType = Type.fromPrimitiveKeyword(ctx.getText());
    return primitiveType != null ? primitiveType : Type.ANY;
  }

  /**
   * Resolves a collection type literal.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code Set{Integer}} → Type.set(Type.INTEGER)
   *   <li>{@code Sequence[String]} → Type.sequence(Type.STRING)
   *   <li>{@code Bag{{Real}}} → Type.bag(Type.DOUBLE)
   * </ul>
   *
   * <p>Element type is resolved recursively, defaulting to Type.ANY if unresolvable.
   *
   * @param ctx The collection type parse tree node
   * @return The collection Type
   */
  private Type resolveCollectionType(VitruvOCLParser.CollectionTypeCSContext ctx) {
    String kind = ctx.collectionKind.getText();
    Type elementType = Type.ANY;

    if (ctx.typeExpCS() != null) {
      elementType = resolveTypeExpression(ctx.typeExpCS());
    }

    return switch (kind) {
      case "Set" -> Type.set(elementType);
      case "Sequence" -> Type.sequence(elementType);
      case "Bag" -> Type.bag(elementType);
      case "OrderedSet" -> Type.orderedSet(elementType);
      case "Collection" -> Type.set(elementType); // Generic collection defaults to Set
      default -> Type.set(Type.ANY);
    };
  }

  // ==================== Iterator Operations ====================

  /**
   * Handles select operation: {@code collection.select(x | x > 5)}.
   *
   * <p>Creates iterator scope and defines iterator variable(s).
   *
   * @param ctx The select operation parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitSelectOp(VitruvOCLParser.SelectOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Handles reject operation: {@code collection.reject(x | x <= 5)}.
   *
   * @param ctx The reject operation parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitRejectOp(VitruvOCLParser.RejectOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Handles collect operation: {@code collection.collect(x | x.property)}.
   *
   * @param ctx The collect operation parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitCollectOp(VitruvOCLParser.CollectOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Handles forAll operation: {@code collection.forAll(x | x > 0)}.
   *
   * @param ctx The forAll operation parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitForAllOp(VitruvOCLParser.ForAllOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Handles exists operation: {@code collection.exists(x | x < 0)}.
   *
   * @param ctx The exists operation parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitExistsOp(VitruvOCLParser.ExistsOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Type-checks the {@code one()} iterator operation.
   *
   * <p>Delegates to {@link #visitIteratorOp} with the iterator variable list and body expression.
   * The result type is unconditionally {@code BOOLEAN} singleton, registered by {@code
   * visitIteratorOp} based on the operation kind.
   *
   * @param ctx the parse tree node for the {@code one()} operation, including the iterator variable
   *     list and body expression
   * @return {@code null} (visitor return convention)
   */
  @Override
  public Void visitOneOp(VitruvOCLParser.OneOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Type-checks the {@code any()} iterator operation.
   *
   * <p>Delegates to {@link #visitIteratorOp} with the iterator variable list and body expression.
   * The result type is an {@code Optional} of the receiver's element type, registered by {@code
   * visitIteratorOp} based on the operation kind.
   *
   * @param ctx the parse tree node for the {@code any()} operation, including the iterator variable
   *     list and body expression
   * @return {@code null} (visitor return convention)
   */
  @Override
  public Void visitAnyOp(VitruvOCLParser.AnyOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Type-checks the {@code isUnique()} iterator operation.
   *
   * <p>Delegates to {@link #visitIteratorOp} with the iterator variable list and body expression.
   * The result type is unconditionally {@code BOOLEAN} singleton, registered by {@code
   * visitIteratorOp} based on the operation kind.
   *
   * @param ctx the parse tree node for the {@code isUnique()} operation, including the iterator
   *     variable list and body expression
   * @return {@code null} (visitor return convention)
   */
  @Override
  public Void visitIsUniqueOp(VitruvOCLParser.IsUniqueOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Type-checks the {@code sortedBy()} iterator operation.
   *
   * <p>Delegates to {@link #visitIteratorOp} with the iterator variable list and body expression.
   * The result type is an {@code OrderedSet} of the receiver's element type, registered by {@code
   * visitIteratorOp} based on the operation kind.
   *
   * @param ctx the parse tree node for the {@code sortedBy()} operation, including the iterator
   *     variable list and body expression
   * @return {@code null} (visitor return convention)
   */
  @Override
  public Void visitSortedByOp(VitruvOCLParser.SortedByOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Type-checks the {@code collectNested()} iterator operation.
   *
   * <p>Delegates to {@link #visitIteratorOp} with the iterator variable list and body expression.
   * The result type is a {@code Bag} of the receiver's element type, registered by {@code
   * visitIteratorOp} based on the operation kind.
   *
   * @param ctx the parse tree node for the {@code collectNested()} operation, including the
   *     iterator variable list and body expression
   * @return {@code null} (visitor return convention)
   */
  @Override
  public Void visitCollectNestedOp(VitruvOCLParser.CollectNestedOpContext ctx) {
    return visitIteratorOp(ctx, ctx.iteratorVars, ctx.body);
  }

  /**
   * Type-checks the {@code iterate()} operation.
   *
   * <p>Opens a fresh {@link LocalScope}, annotates the node with it, and registers both the
   * iterator variable and the accumulator variable (each typed as {@code ANY} as placeholders)
   * before visiting the accumulator initializer and the body expression. The scope is exited in a
   * {@code finally} block to ensure cleanup on error.
   *
   * @param ctx the parse tree node for the {@code iterate()} operation, including the {@link
   *     VitruvOCLParser.IterateVarSpecContext} with iterator variable, accumulator variable,
   *     initializer expression, and body
   * @return {@code null} (visitor return convention)
   */
  @Override
  public Void visitIterateOp(VitruvOCLParser.IterateOpContext ctx) {
    VitruvOCLParser.IterateVarSpecContext varSpec = ctx.iterateVarSpec();

    LocalScope iterScope = new LocalScope(symbolTable.getCurrentScope());
    symbolTable.enterScope(iterScope);
    scopeAnnotator.annotate(ctx, iterScope);

    try {
      symbolTable.defineVariable(
          new VariableSymbol(varSpec.iterVar.getText(), Type.ANY, iterScope, true));
      symbolTable.defineVariable(
          new VariableSymbol(varSpec.accVar.getText(), Type.ANY, iterScope, false));
      visit(varSpec.accInit);
      visit(ctx.body);
      return null;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Generic handler for all iterator operations (select, reject, collect, forAll, exists).
   *
   * <p>Creates an iterator scope and defines iterator variables with placeholder type Type.ANY.
   * Pass 2 will refine these to the actual element type of the source collection.
   *
   * <p>Examples:
   *
   * <pre>{@code
   * collection.select(x | x > 5)           // Single iterator: x
   * collection.forAll(a, b | a != b)       // Multiple iterators: a, b
   * }</pre>
   *
   * <p>The iterator scope is annotated on the parse tree node for Pass 2 retrieval.
   *
   * @param ctx The iterator operation parse tree node
   * @param iteratorVars The iterator variable list
   * @param body The iterator body expression
   * @return null (void visitor)
   */
  private Void visitIteratorOp(
      ParserRuleContext ctx,
      VitruvOCLParser.IteratorVarListContext iteratorVars,
      VitruvOCLParser.ExpCSContext body) {

    // Extract iterator variable names
    List<String> iterVars = new ArrayList<>();
    if (iteratorVars != null) {
      for (TerminalNode id : iteratorVars.ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Iterator requires at least one variable",
          ErrorSeverity.ERROR,
          PHASE_TAG);
      return null;
    }

    // Create iterator scope as child of current scope
    LocalScope iterScope = new LocalScope(symbolTable.getCurrentScope());
    symbolTable.enterScope(iterScope);

    // Annotate parse tree node with scope for Pass 2 variable lookup
    scopeAnnotator.annotate(ctx, iterScope);

    try {
      // Define all iterator variables with placeholder type
      // (Pass 2 will refine to actual element type)
      for (String iterVar : iterVars) {
        symbolTable.defineVariable(new VariableSymbol(iterVar, Type.ANY, iterScope, true));
      }

      // Visit body to collect nested lets/iterators
      visit(body);

      return null;
    } finally {
      symbolTable.exitScope();
    }
  }

  // ==================== Traversal ====================
  // Every node without an override here is traversed by ANTLR's default visitChildren(), which
  // visits all child rules in tree order. That reaches every nested let expression and iterator
  // (the only constructs that open scopes), so pure pass-through overrides are unnecessary. The
  // overrides below exist because they deliberately visit only part of their subtree.

  /**
   * Visits a navigation chain (property access or operation call).
   *
   * @param ctx The navigation chain parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitNavigationChainCS(VitruvOCLParser.NavigationChainCSContext ctx) {
    if (ctx.navigationTargetCS() == null) {
      return null; // '@pre' postfix — no nested navigation target, nothing to traverse
    }
    return visit(ctx.navigationTargetCS());
  }

  /**
   * Visits collection literal expression.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code Set{1, 2, 3}}
   *   <li>{@code Sequence["a", "b", "c"]}
   *   <li>{@code Bag{1, 1, 2, 3}}
   * </ul>
   *
   * @param ctx The collection literal expression parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitCollectionLiteralExpCS(VitruvOCLParser.CollectionLiteralExpCSContext ctx) {
    visit(ctx.collectionKind);
    if (ctx.arguments != null) {
      visit(ctx.arguments);
    }
    return null;
  }

  /**
   * Visits property navigation: {@code object.property}.
   *
   * <p>No traversal needed - property access resolved in Pass 2.
   *
   * @param ctx The property navigation parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitPropertyNav(VitruvOCLParser.PropertyNavContext ctx) {
    return null;
  }

  // ==================== Type Expression Nodes (No Symbol Table Impact) ====================

  /**
   * Visits type expression node.
   *
   * <p>Type expressions don't affect symbol table - they're resolved during type checking in Pass
   * 2.
   *
   * @param ctx The type expression parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitTypeExpCS(VitruvOCLParser.TypeExpCSContext ctx) {
    return null; // Type expressions don't affect symbol table
  }

  /**
   * Visits type literal node.
   *
   * <p>Type literals are resolved during type checking, not during symbol table construction.
   *
   * @param ctx The type literal parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitTypeLiteralCS(VitruvOCLParser.TypeLiteralCSContext ctx) {
    return null;
  }

  /**
   * Visits collection type node.
   *
   * <p>Collection types are resolved when needed, not during symbol table construction.
   *
   * @param ctx The collection type parse tree node
   * @return null (void visitor)
   */
  @Override
  public Void visitCollectionTypeCS(VitruvOCLParser.CollectionTypeCSContext ctx) {
    return null;
  }
}
