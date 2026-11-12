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

import org.antlr.v4.runtime.ParserRuleContext;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;

/**
 * What an operation-family evaluator (e.g. {@link StringOperationEvaluation}) needs from {@link
 * EvaluationVisitor}: the receiver value, recursive evaluation of argument expressions and error
 * reporting.
 */
interface OperationEvaluationContext {

  /** Returns the value of the receiver of the operation currently being evaluated. */
  Value receiver();

  /** Evaluates an argument expression. */
  Value evaluate(ParserRuleContext expression);

  /** Reports an evaluation error at {@code ctx}. */
  void error(ParserRuleContext ctx, String message);

  /** Returns the static type the type checker recorded for {@code ctx}, or {@code null}. */
  tools.vitruv.dsls.vitruvocl.typechecker.Type nodeType(ParserRuleContext ctx);

  /**
   * Whether evaluating {@code expression} may report a runtime error. Lazy operations must then be
   * evaluated strictly, so that skipping elements does not hide errors eager evaluation reports.
   */
  boolean mayReportErrors(ParserRuleContext expression);

  /** Returns the symbol table (iterators bind their variables in fresh local scopes). */
  SymbolTable symbolTable();
}
