/* ******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package tools.vitruv.dsls.vitruvocl.lsp;

import java.util.List;
import tools.vitruv.dsls.vitruvocl.source.ConstraintUnit;
import tools.vitruv.dsls.vitruvocl.source.OclSourceSplitter;

/**
 * The constraint units of a document, as the custom request {@value #METHOD} returns them: the
 * units the CLI evaluates, in the same order, so that the VS Code extension needs no splitting of
 * its own and can map the CLI's error lines back to the document.
 */
final class ConstraintUnits {

  /** The JSON-RPC method of the request; its parameter is a {@code TextDocumentIdentifier}. */
  static final String METHOD = "vitruvocl/constraintUnits";

  private ConstraintUnits() {}

  /**
   * One unit of a document.
   *
   * @param kind {@code INV}, {@code PRE}, {@code POST} or {@code EMPTY_CONTEXT}
   * @param name the block's name, or {@code null} if unnamed
   * @param contextType the context type as written, or {@code null} if malformed
   * @param text the compilable constraint the CLI evaluates for this unit
   * @param textLines the document line of every line of {@code text}, starting at 1
   * @param line the line of the block's keyword ({@code context} for an empty context), from 1
   * @param column the column of that keyword, from 0, in Unicode code points
   */
  record Unit(
      String kind,
      String name,
      String contextType,
      String text,
      List<Integer> textLines,
      int line,
      int column) {}

  /** The units of the document text, in document order. */
  static List<Unit> of(String text) {
    return OclSourceSplitter.unitsOf(text).stream().map(ConstraintUnits::unit).toList();
  }

  private static Unit unit(ConstraintUnit unit) {
    return new Unit(
        unit.kind().name(),
        unit.name(),
        unit.contextType(),
        unit.text(),
        unit.textLines(),
        unit.start().line(),
        unit.start().column());
  }
}
