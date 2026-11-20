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

package tools.vitruv.dsls.vitruvocl.source;

import java.util.List;

/**
 * One constraint block of an {@code .ocl} document together with the header of its context, so that
 * it can be compiled and evaluated on its own.
 *
 * @param kind the kind of block
 * @param name the block's name ({@code a} in {@code inv a: ...}; also a reserved word such as
 *     {@code count}, which the grammar rejects), or {@code null} if unnamed
 * @param contextType the context type as written, e.g. {@code shop::Item} for {@code context
 *     shop::Item::setPrice(p : Integer)}, or {@code null} if the declaration is malformed
 * @param header the context declaration as written, from {@code context} up to the first block of
 *     the context, including comments in between; stripped
 * @param body the block as written, from its keyword to its last token; empty for {@link
 *     Kind#EMPTY_CONTEXT}
 * @param text the compilable constraint: {@code header}, a line break and {@code body}, both
 *     without blank and comment lines, with {@code \n} line breaks
 * @param textLines the document line of every line of {@code text}, starting at 1
 * @param severity the value of the block's first {@code @severity} annotation as written (not
 *     validated), or {@code null}
 * @param severityPosition the position of the {@code @severity} annotation, or {@code null}
 * @param start the position of the block's keyword, or of {@code context} for {@link
 *     Kind#EMPTY_CONTEXT}
 */
public record ConstraintUnit(
    Kind kind,
    String name,
    String contextType,
    String header,
    String body,
    String text,
    List<Integer> textLines,
    String severity,
    SourcePosition severityPosition,
    SourcePosition start) {

  /** The kind of a constraint block. */
  public enum Kind {
    /** An invariant ({@code inv}). */
    INV,
    /** A precondition ({@code pre}). */
    PRE,
    /** A postcondition ({@code post}). */
    POST,
    /** A context declaration without any block; it is kept so that callers can report it. */
    EMPTY_CONTEXT
  }

  /** Copies the line list so that the unit stays immutable. */
  public ConstraintUnit {
    textLines = List.copyOf(textLines);
  }
}
