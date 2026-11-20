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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.analysis.OclAnalyzer.Mode;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorSeverity;

class OclAnalyzerTest {

  private static final String HEADER = "context spaceMission::Spacecraft inv a: ";

  private static MetamodelWrapper metamodels;

  @BeforeAll
  static void loadMetamodels() throws IOException {
    metamodels = new MetamodelWrapper();
    metamodels.loadMetamodel(Path.of("src/test/resources/test-metamodels/spaceMission.ecore"));
  }

  @Test
  void aValidConstraintCompiles() {
    OclAnalysis analysis = OclAnalyzer.analyze(HEADER + "self.mass >= 0", metamodels, Mode.STRICT);

    assertThat(analysis.compiled()).isTrue();
    assertThat(analysis.nodeTypes()).isNotNull();
    assertThat(analysis.symbolTable()).isNotNull();
    assertThat(analysis.syntaxErrors()).isEmpty();
    assertThat(analysis.errors()).isEmpty();
  }

  @Test
  void strictCollectsParserErrorsAndDoesNotCompile() {
    OclAnalysis analysis = OclAnalyzer.analyze("context inv a: true", metamodels, Mode.STRICT);

    assertThat(analysis.compiled()).isFalse();
    assertThat(analysis.nodeTypes()).isNull();
    assertThat(analysis.syntaxErrors()).isNotEmpty();
    assertThat(analysis.syntaxErrors().get(0))
        .satisfies(
            error -> {
              assertThat(error.fromLexer()).isFalse();
              assertThat(error.line()).isEqualTo(1);
              assertThat(error.column()).isEqualTo(8);
              assertThat(error.offendingToken().getText()).isEqualTo("inv");
            });
  }

  @Test
  void lexerErrorsPreventCompiling() {
    OclAnalysis analysis = OclAnalyzer.analyze(HEADER + "true §", metamodels, Mode.STRICT);

    assertThat(analysis.syntaxErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.fromLexer()).isTrue();
              assertThat(error.offendingToken()).isNull();
            });
    assertThat(analysis.compiled()).isFalse();
    assertThat(analysis.allErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getSource()).isEqualTo("lexer");
              assertThat(error.getColumn()).isEqualTo(45);
              assertThat(error.getEndColumn()).isEqualTo(46);
            });
  }

  @Test
  void inputAfterTheConstraintIsASyntaxError() {
    OclAnalysis analysis =
        OclAnalyzer.analyze(HEADER + "self.mass >= 0 )", metamodels, Mode.STRICT);

    assertThat(analysis.compiled()).isFalse();
    assertThat(analysis.allErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getSource()).isEqualTo("parser");
              assertThat(error.getMessage()).isEqualTo("unexpected input ')'");
              assertThat(error.getColumn()).isEqualTo(55);
              assertThat(error.getEndColumn()).isEqualTo(56);
            });
  }

  @Test
  void aPassErrorAtTheSamePositionReplacesTheSyntaxError() {
    OclAnalysis analysis = OclAnalyzer.analyze(HEADER + "self.mass >=", metamodels, Mode.STRICT);

    assertThat(analysis.syntaxErrors())
        .singleElement()
        .satisfies(error -> assertThat(error.message()).isEqualTo("unexpected input '>='"));
    assertThat(analysis.allErrors())
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getMessage()).isEqualTo("Missing right-hand operand for '>='"));
  }

  @Test
  void aLogicalOperatorAtTheEndMissesItsOperand() {
    OclAnalysis analysis =
        OclAnalyzer.analyze(HEADER + "self.mass >= 0 and", metamodels, Mode.STRICT);

    assertThat(analysis.syntaxErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.kind()).isEqualTo(SyntaxError.Kind.TRAILING_INPUT);
              assertThat(error.message()).isEqualTo("Missing right-hand operand for 'and'");
            });
  }

  @Test
  void endOfInputIsNamedInSyntaxErrors() {
    OclAnalysis analysis = OclAnalyzer.analyze("", metamodels, Mode.STRICT);

    assertThat(analysis.allErrors())
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getMessage())
                    .isEqualTo("mismatched input '<EOF>' expecting 'context'"));
  }

  @Test
  void warningsAloneDoNotPreventCompiling() {
    OclAnalysis analysis =
        OclAnalyzer.analyze(HEADER + "Set{1}.includes(\"x\")", metamodels, Mode.STRICT);

    assertThat(analysis.errors())
        .singleElement()
        .satisfies(warning -> assertThat(warning.getSeverity()).isEqualTo(ErrorSeverity.WARNING));
    assertThat(analysis.compiled()).isTrue();
  }

  @Test
  void strictSkipsTypeCheckingAfterSymbolTableErrors() {
    OclAnalysis analysis =
        OclAnalyzer.analyze(
            "context spaceMission::Nope inv a: self.noSuch > 0", metamodels, Mode.STRICT);

    assertThat(analysis.compiled()).isFalse();
    assertThat(analysis.nodeTypes()).isNull();
    assertThat(analysis.errors())
        .extracting(CompileError::getSource)
        .containsOnly("symbol-table-builder");
  }

  @Test
  void tolerantRunsBothPassesDespiteSyntaxErrors() {
    OclAnalysis analysis =
        OclAnalyzer.analyze(HEADER + "self.noSuch > 0 )", metamodels, Mode.TOLERANT);

    assertThat(analysis.nodeTypes()).isNotNull();
    assertThat(analysis.errors()).extracting(CompileError::getSource).contains("type-checker");
  }

  @Test
  void tolerantRecordsACrashingPassAndContinues() {
    String crashing = HEADER + "true let";

    OclAnalysis analysis = OclAnalyzer.analyze(crashing, metamodels, Mode.TOLERANT);

    assertThat(analysis.crashes())
        .singleElement()
        .satisfies(crash -> assertThat(crash.pass()).isEqualTo("symbol table construction"));
    assertThat(analysis.compiled()).isFalse();
    // the parser error stops a strict analysis before the crashing pass
    OclAnalysis strict = OclAnalyzer.analyze(crashing, metamodels, Mode.STRICT);
    assertThat(strict.syntaxErrors()).isNotEmpty();
    assertThat(strict.crashes()).isEmpty();
    assertThat(strict.compiled()).isFalse();
  }
}
