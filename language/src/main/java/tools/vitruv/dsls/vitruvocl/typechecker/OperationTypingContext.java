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

package tools.vitruv.dsls.vitruvocl.typechecker;

import org.antlr.v4.runtime.ParserRuleContext;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTable;
import tools.vitruv.dsls.vitruvocl.symboltable.VariableSymbol;

/**
 * What an operation-family type checker (e.g. {@link StringOperationTyping}) needs from {@link
 * TypeCheckVisitor}: the receiver type, recursive checking of argument expressions, error reporting
 * and recording the resulting node type.
 */
interface OperationTypingContext {

  /** Returns the type of the receiver of the operation currently being checked. */
  Type receiver();

  /** Type checks an argument expression and returns its type. */
  Type check(ParserRuleContext expression);

  /**
   * Type checks an initializer that is evaluated before {@code unbound} are bound; inside it, their
   * names refer to the variables they shadow, and are an error if there are none.
   */
  Type checkInitializer(ParserRuleContext initializer, VariableSymbol... unbound);

  /** Reports a type error at {@code ctx}. */
  void error(ParserRuleContext ctx, String message);

  /** Reports a type warning at {@code ctx}. */
  void warning(ParserRuleContext ctx, String message);

  /** Records {@code type} as the type of {@code ctx} (consumed by later passes and the LSP). */
  void recordType(ParserRuleContext ctx, Type type);

  /** Returns the symbol table (iterators enter their Pass-1 scopes and type their variables). */
  SymbolTable symbolTable();

  /** Returns the scope annotations recorded by Pass 1 ({@code SymbolTableBuilder}). */
  ScopeAnnotator scopes();
}
