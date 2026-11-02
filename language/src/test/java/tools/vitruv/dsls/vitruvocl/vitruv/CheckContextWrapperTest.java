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

package tools.vitruv.dsls.vitruvocl.vitruv;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.eclipse.emf.ecore.EcorePackage;
import org.junit.jupiter.api.Test;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;
import tools.vitruv.dsls.vitruvocl.pipeline.VsumWrapper;

/** Tests for a {@link VsumWrapper} created for a single constraint check. */
class CheckContextWrapperTest {

  @Test
  void rejectsTransactionListeners() {
    VsumWrapper wrapper = new VsumWrapper(EmptyCheckContext.INSTANCE);
    ChangePropagationListener listener = mock(ChangePropagationListener.class);

    assertThrows(IllegalStateException.class, () -> wrapper.addTransactionListener(listener));
    assertThrows(IllegalStateException.class, () -> wrapper.removeTransactionListener(listener));
  }

  @Test
  void findsNoInstancesWithoutModels() {
    VsumWrapper wrapper = new VsumWrapper(EmptyCheckContext.INSTANCE);

    assertTrue(wrapper.getAllInstances(EcorePackage.Literals.ECLASS).isEmpty());
    assertTrue(wrapper.getAllRootObjects().isEmpty());
  }
}
