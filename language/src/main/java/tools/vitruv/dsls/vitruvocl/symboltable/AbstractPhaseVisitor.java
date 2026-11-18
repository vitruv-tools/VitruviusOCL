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

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import tools.vitruv.dsls.vitruvocl.VitruvOCLBaseVisitor;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.ErrorSeverity;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;

/**
 * Abstract base class for visitor implementations used across compilation phases.
 *
 * <p>Provides shared infrastructure for both type checking (Pass 2) and runtime evaluation (Pass 3)
 * phases, including:
 *
 * <ul>
 *   <li>Unified symbol table access for variable resolution
 *   <li>Centralized error collection and reporting
 *   <li>Access to metamodel information via VSUM wrapper
 * </ul>
 *
 * <p>The generic type parameter {@code T} allows phase-specific return types: {@code Type} for
 * type checking and {@code Value} for evaluation.
 *
 * <p>Subclasses must implement {@link #handleUndefinedSymbol(String, ParserRuleContext)} to provide
 * phase-appropriate error handling for unresolved variables. The phase implementations are {@code
 * TypeCheckVisitor} (Pass 2, type checking) and {@code EvaluationVisitor} (Pass 3, runtime
 * evaluation).
 *
 * @param <T> Return type of visitor methods - {@code Type} for type checking phase, {@code Value}
 *     for evaluation phase
 */
public abstract class AbstractPhaseVisitor<T> extends VitruvOCLBaseVisitor<T> {

  /** Symbol table for variable resolution across scopes. */
  protected final SymbolTable symbolTable;

  /** Collector for compilation errors and warnings. */
  protected final ErrorCollector errors;

  /** Interface to VSUM for metamodel and EObject access. */
  protected final ModelAccess specification;

  /**
   * Constructs a phase visitor with required compiler infrastructure.
   *
   * @param symbolTable Symbol table containing variable bindings
   * @param specification VSUM wrapper for metamodel access
   * @param errors Error collector for this compilation phase
   */
  protected AbstractPhaseVisitor(
      SymbolTable symbolTable, ModelAccess specification, ErrorCollector errors) {
    this.symbolTable = symbolTable;
    this.specification = specification;
    this.errors = errors;
  }

  /**
   * Resolves a variable name to its symbol table entry.
   *
   * <p>Searches the symbol table for the given variable name, invoking phase-specific error
   * handling if the symbol cannot be resolved.
   *
   * @param name Variable name to resolve
   * @param ctx Parse tree context for error reporting
   * @return Resolved symbol, or {@code null} if not found (after recording error)
   */
  protected Symbol resolveSymbol(String name, ParserRuleContext ctx) {
    Symbol symbol = symbolTable.resolveVariable(name);
    if (symbol == null) {
      handleUndefinedSymbol(name, ctx);
    }
    return symbol;
  }

  /**
   * Handles undefined symbol errors in a phase-specific manner.
   *
   * <p>Type checking phase may record a type error, while evaluation phase may throw a runtime
   * exception or return a sentinel value.
   *
   * @param name The undefined variable name
   * @param ctx Parse tree context for error location information
   */
  protected abstract void handleUndefinedSymbol(String name, ParserRuleContext ctx);

  /**
   * Source tag identifying the current compilation phase in error reports.
   *
   * @return phase tag (e.g. {@code "type-checker"} or {@code "evaluator"})
   */
  protected abstract String phaseTag();

  /**
   * Records an error at the start position of the given context.
   *
   * @param ctx Parse tree context providing the error location
   * @param message Human-readable error message
   */
  protected void reportError(ParserRuleContext ctx, String message) {
    errors.add(
        ctx.getStart().getLine(),
        ctx.getStart().getCharPositionInLine(),
        message,
        ErrorSeverity.ERROR,
        phaseTag());
  }

  /**
   * Records an error that covers exactly the text of the given token.
   *
   * @param token Token whose text is the error range
   * @param message Human-readable error message
   */
  protected void reportError(Token token, String message) {
    reportError(token, message, null);
  }

  /**
   * Records an error that covers exactly the text of the given token and offers a quick fix.
   *
   * @param token Token whose text is the error range
   * @param message Human-readable error message
   * @param suggestion Replacement text for a quick fix, or {@code null} for none
   */
  protected void reportError(Token token, String message, String suggestion) {
    reportSpanError(
        token,
        token.getLine(),
        token.getCharPositionInLine() + token.getText().length(),
        message,
        suggestion);
  }

  /**
   * Records an error that starts at the given token and ends at an explicit position.
   *
   * @param start Token whose start position is the start of the error range
   * @param endLine Line where the error range ends
   * @param endColumn Column (exclusive) where the error range ends
   * @param message Human-readable error message
   */
  protected void reportSpanError(Token start, int endLine, int endColumn, String message) {
    reportSpanError(start, endLine, endColumn, message, null);
  }

  /**
   * Records an error that starts at the given token, ends at an explicit position and offers a
   * quick fix.
   *
   * @param start Token whose start position is the start of the error range
   * @param endLine Line where the error range ends
   * @param endColumn Column (exclusive) where the error range ends
   * @param message Human-readable error message
   * @param suggestion Replacement text for a quick fix, or {@code null} for none
   */
  protected void reportSpanError(
      Token start, int endLine, int endColumn, String message, String suggestion) {
    errors.add(
        new CompileError(
            start.getLine(),
            start.getCharPositionInLine(),
            endLine,
            endColumn,
            message,
            ErrorSeverity.ERROR,
            phaseTag(),
            null,
            suggestion));
  }

  /**
   * Records a warning at the start position of the given context.
   *
   * @param ctx Parse tree context providing the warning location
   * @param message Human-readable warning message
   */
  protected void reportWarning(ParserRuleContext ctx, String message) {
    errors.add(
        ctx.getStart().getLine(),
        ctx.getStart().getCharPositionInLine(),
        message,
        ErrorSeverity.WARNING,
        phaseTag());
  }

  /**
   * Checks whether any errors were collected during this phase.
   *
   * @return {@code true} if errors were recorded, {@code false} otherwise
   */
  public boolean hasErrors() {
    return errors.hasErrors();
  }

  /**
   * Returns the error collector for this compilation phase.
   *
   * @return Error collector containing all recorded errors and warnings
   */
  public ErrorCollector getErrorCollector() {
    return errors;
  }
}
