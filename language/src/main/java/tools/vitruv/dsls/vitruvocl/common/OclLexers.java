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
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenFactory;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.misc.Pair;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;

/** Creates the lexers of constraints that are analyzed and evaluated. */
public final class OclLexers {

  /**
   * Stores the text of every token except end of input, which keeps ANTLR's {@code <EOF>} so that
   * syntax errors name it.
   */
  private static final TokenFactory<CommonToken> TEXT_STORING =
      new CommonTokenFactory(true) {
        @Override
        public CommonToken create(
            Pair<TokenSource, CharStream> source,
            int type,
            String text,
            int channel,
            int start,
            int stop,
            int line,
            int charPositionInLine) {
          if (type == Token.EOF) {
            return CommonTokenFactory.DEFAULT.create(
                source, type, text, channel, start, stop, line, charPositionInLine);
          }
          return super.create(source, type, text, channel, start, stop, line, charPositionInLine);
        }
      };

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
    lexer.setTokenFactory(TEXT_STORING);
    return lexer;
  }
}
