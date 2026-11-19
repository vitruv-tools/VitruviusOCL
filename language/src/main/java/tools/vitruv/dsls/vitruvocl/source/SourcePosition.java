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

/**
 * A position in an {@code .ocl} document, as the lexer counts it: lines are separated by line
 * breaks, columns count Unicode code points (not UTF-16 units).
 *
 * @param line the line, starting at 1
 * @param column the column, starting at 0
 */
public record SourcePosition(int line, int column) {}
