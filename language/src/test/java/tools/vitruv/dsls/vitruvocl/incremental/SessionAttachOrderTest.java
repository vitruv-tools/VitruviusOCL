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

package tools.vitruv.dsls.vitruvocl.incremental;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.incremental.support.LiveModel;
import tools.vitruv.framework.views.ViewSource;

/**
 * Attaching a session to a VSUM must not miss a commit that lands while the session initializes:
 * the listener is registered before the initial evaluation reads the model, and a commit delivered
 * before the initialization finished is covered by it instead of failing.
 */
class SessionAttachOrderTest {

  private static final List<String> CONSTRAINTS =
      List.of("context edge::Item inv: self.value >= 0");

  @Test
  void listenerIsRegisteredBeforeTheModelIsRead() {
    LiveModel live = EdgeFixture.create().model();
    try (IncrementalSession session =
        IncrementalSession.openAttached(
            CONSTRAINTS,
            live.wrapper(),
            IncrementalConfig.of(IncrementalLevel.RELEVANT_INSTANCES),
            r -> {},
            e -> {})) {
      InOrder order = inOrder(live.vsum());
      order.verify(live.vsum()).addChangePropagationListener(any());
      order.verify((ViewSource) live.vsum(), org.mockito.Mockito.atLeastOnce())
          .getViewSourceModels();
    }
  }

  @Test
  void commitBeforeInitializationFinishedDoesNotFail() {
    LiveModel live = EdgeFixture.create().model();
    // the VSUM delivers a commit right after the listener was registered
    doAnswer(
            inv -> {
              ChangePropagationListener listener = inv.getArgument(0);
              listener.finishedChangePropagation(List.of());
              return null;
            })
        .when(live.vsum())
        .addChangePropagationListener(any());
    List<RuntimeException> failures = new ArrayList<>();
    try (IncrementalSession session =
        IncrementalSession.openAttached(
            CONSTRAINTS,
            live.wrapper(),
            IncrementalConfig.of(IncrementalLevel.RELEVANT_INSTANCES),
            r -> {},
            failures::add)) {
      assertTrue(failures.isEmpty(), "unexpected failures: " + failures);
      assertTrue(session.latestResult() != null);
    }
  }
}
