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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.incremental.support.EdgeFixture;
import tools.vitruv.dsls.vitruvocl.pipeline.MetamodelWrapperInterface;

/**
 * A session whose initial evaluation fails must not leave its worker threads running: they are
 * non-daemon threads of a fixed pool and would keep the JVM alive.
 */
class SessionThreadLeakTest {

  /** {@code delegate}, except that collecting instances fails. */
  @SuppressWarnings("unchecked")
  private static MetamodelWrapperInterface failingInstances(MetamodelWrapperInterface delegate) {
    return (MetamodelWrapperInterface)
        Proxy.newProxyInstance(
            MetamodelWrapperInterface.class.getClassLoader(),
            new Class<?>[] {MetamodelWrapperInterface.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getAllInstances")) {
                throw new IllegalStateException("model access failed");
              }
              if (method.getName().equals("batch")) {
                // evaluate against this failing proxy, not a view of the delegate
                return ((java.util.function.Function<Object, Object>) args[0]).apply(proxy);
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static Set<Thread> liveNonDaemonThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.isAlive() && !t.isDaemon())
        .collect(Collectors.toSet());
  }

  @Test
  void failedInitializationStopsTheWorkerThreads() throws InterruptedException {
    MetamodelWrapperInterface wrapper = failingInstances(EdgeFixture.create().model().wrapper());
    IncrementalConfig config =
        IncrementalConfig.of(IncrementalLevel.RELEVANT_INSTANCES).withThreads(2);
    Set<Thread> before = liveNonDaemonThreads();

    assertThrows(
        RuntimeException.class,
        () ->
            IncrementalSession.open(
                List.of(
                    "context edge::Item inv: self.value >= 0",
                    "context edge::Root inv: self.items.notEmpty()"),
                wrapper,
                config));

    long deadline = System.currentTimeMillis() + 5_000;
    Set<Thread> leaked = liveNonDaemonThreads();
    leaked.removeAll(before);
    while (!leaked.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
      leaked = liveNonDaemonThreads();
      leaked.removeAll(before);
    }
    assertTrue(leaked.isEmpty(), "worker threads still running: " + leaked);
  }
}
