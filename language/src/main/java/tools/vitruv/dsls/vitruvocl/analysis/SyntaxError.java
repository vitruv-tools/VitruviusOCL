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

package tools.vitruv.dsls.vitruvocl.analysis;

import org.antlr.v4.runtime.Token;

/**
 * A syntax error reported by the lexer or the parser.
 *
 * @param line the line, starting at 1
 * @param column the column, starting at 0
 * @param message the message
 * @param offendingToken the token the parser could not continue with, or {@code null} for a lexer
 *     error
 * @param kind who reported it
 */
public record SyntaxError(int line, int column, String message, Token offendingToken, Kind kind) {

  /** Who reported a syntax error. */
  public enum Kind {
    /** The lexer: text it cannot tokenize. */
    LEXER,
    /** The parser, through ANTLR's error reporting. */
    PARSER,
    /** The analyzer: input after the point where the parser stopped (the grammar has no EOF). */
    TRAILING_INPUT
  }

  /** Whether the lexer reported it. */
  public boolean fromLexer() {
    return kind == Kind.LEXER;
  }
}
