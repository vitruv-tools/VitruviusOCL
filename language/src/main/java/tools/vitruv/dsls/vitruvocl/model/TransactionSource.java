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

package tools.vitruv.dsls.vitruvocl.model;

import tools.vitruv.change.composite.propagation.ChangePropagationListener;

/**
 * A model source whose committed changes can be observed, such as a Vitruvius virtual model. An
 * incremental evaluation session registers a listener here to process every transaction.
 */
public interface TransactionSource {

  /**
   * Registers {@code listener} for the change propagations of this source.
   *
   * @param listener receives one {@code finishedChangePropagation} callback per committed change
   * @throws IllegalStateException if this source cannot report transactions
   */
  void addTransactionListener(ChangePropagationListener listener);

  /** Removes a listener registered with {@link #addTransactionListener}. */
  void removeTransactionListener(ChangePropagationListener listener);
}
