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

package tools.vitruv.dsls.vitruvocl.common;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenFactory;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;

/** Creates lexers for constraints that are going to be evaluated. */
public final class OclLexers {

  private OclLexers() {}

  /**
   * Creates a lexer whose tokens store their text when they are created.
   *
   * <p>ANTLR's default tokens re-extract their text from the input stream on every {@code
   * getText()} call, allocating a new string each time. The evaluator reads identifier texts
   * (variable and property names) once per element it evaluates, so storing the text once at lexing
   * time avoids one string allocation per element and navigation.
   *
   * @param input the constraint source
   * @return a lexer producing text-storing tokens
   */
  public static VitruvOCLLexer create(CharStream input) {
    VitruvOCLLexer lexer = new VitruvOCLLexer(input);
    lexer.setTokenFactory(new CommonTokenFactory(true));
    return lexer;
  }
}
