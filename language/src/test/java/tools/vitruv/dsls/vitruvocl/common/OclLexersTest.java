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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

/**
 * The evaluator reads identifier texts (variable and property names) from tokens once per element
 * it evaluates; tokens created by {@link OclLexers#create} store their text at lexing time, so
 * reading it never re-extracts (and re-allocates) it from the input stream.
 */
class OclLexersTest {

  @Test
  void tokenText_isStoredOnceAndReturnedWithoutCopying() {
    CommonTokenStream tokens =
        new CommonTokenStream(OclLexers.create(CharStreams.fromString("self.name.size()")));
    tokens.fill();
    List<Token> all = tokens.getTokens();

    Token name = all.stream().filter(t -> "name".equals(t.getText())).findFirst().orElseThrow();

    assertEquals("name", name.getText());
    assertSame(name.getText(), name.getText());
  }
}
