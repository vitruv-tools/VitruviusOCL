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

package tools.vitruv.dsls.vitruvocl.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.eclipse.emf.ecore.EClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading model instances while another thread queries {@code allInstances()} must neither fail
 * nor lose instances: both read and change the same instance list and caches.
 */
class MetamodelWrapperConcurrencyTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("src/test/resources/test-metamodels/spaceMission.ecore");
  private static final Path SPACECRAFT_VOYAGER =
      Path.of("src/test/resources/test-models/spacecraft-voyager.spacemission");

  @TempDir Path dir;

  @Test
  void loadingWhileQueryingIsSafe() throws Exception {
    int models = 200;
    Path[] copies = new Path[models];
    for (int i = 0; i < models; i++) {
      copies[i] = dir.resolve("voyager" + i + ".spacemission");
      Files.copy(SPACECRAFT_VOYAGER, copies[i]);
    }
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    EClass spacecraft = wrapper.resolveEClass("spaceMission", "Spacecraft");
    CountDownLatch start = new CountDownLatch(1);

    CompletableFuture<Void> loader =
        CompletableFuture.runAsync(
            () -> {
              try {
                start.await();
                for (Path copy : copies) {
                  wrapper.loadModelInstance(copy);
                }
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    CompletableFuture<Void> querier =
        CompletableFuture.runAsync(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
              while (!loader.isDone()) {
                wrapper.getAllInstances(spacecraft);
              }
            });
    start.countDown();
    CompletableFuture.allOf(loader, querier).get();

    assertEquals(models, wrapper.getAllInstances(spacecraft).size());
  }
}
