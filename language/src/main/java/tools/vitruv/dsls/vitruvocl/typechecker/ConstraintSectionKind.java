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

/**
 * Which kind of constraint block ({@code inv}/{@code pre}/{@code post}) is being type-checked.
 * Restricts {@code @pre} and the {@code OCLisNew}/{@code OCLisModified}/{@code OCLisDeleted}
 * lifecycle predicates to {@code post} blocks (see {@link LifecycleTyping}). {@link
 * TypeCheckVisitor} sets it at the start of every {@code inv}/{@code pre}/{@code post} block; these
 * blocks never nest, so a single field (rather than a stack) is sufficient.
 */
enum ConstraintSectionKind {
  INV,
  PRE,
  POST
}
