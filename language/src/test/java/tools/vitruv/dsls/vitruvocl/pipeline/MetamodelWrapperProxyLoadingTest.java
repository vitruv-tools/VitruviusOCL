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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolving a cross-file proxy loads a resource into the wrapper's resource set. That happens
 * during evaluation, outside the wrapper's methods, so it must take the wrapper's lock: otherwise
 * it races with loading models through the wrapper.
 */
class MetamodelWrapperProxyLoadingTest {

  private static final String ECORE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
          name="proxyload" nsURI="http://test/proxyload" nsPrefix="proxyload">
        <eClassifiers xsi:type="ecore:EClass" name="Node">
          <eStructuralFeatures xsi:type="ecore:EReference" name="next" eType="#//Node"/>
        </eClassifiers>
      </ecore:EPackage>
      """;

  private static final String HEAD =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <proxyload:Node xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:proxyload="http://test/proxyload"\
      """;

  @TempDir Path dir;

  @Test
  void proxyResolutionWaitsForTheWrapperLock() throws IOException, InterruptedException {
    Path ecore = dir.resolve("proxyload.ecore");
    Files.writeString(ecore, ECORE);
    Files.writeString(dir.resolve("b.xmi"), HEAD + "/>\n");
    Path a = dir.resolve("a.xmi");
    Files.writeString(a, HEAD + ">\n  <next href=\"b.xmi#/\"/>\n</proxyload:Node>\n");
    MetamodelWrapper wrapper = new MetamodelWrapper();
    try {
      wrapper.loadMetamodel(ecore);
      wrapper.loadModelInstance(a);
      EObject root = wrapper.getContextObjects().get(0);
      AtomicReference<Object> resolved = new AtomicReference<>();
      Thread evaluation =
          new Thread(() -> resolved.set(root.eGet(root.eClass().getEStructuralFeature("next"))));

      Thread.State whileLocked;
      synchronized (wrapper) {
        evaluation.start();
        long deadline = System.currentTimeMillis() + 2_000;
        while (evaluation.getState() != Thread.State.BLOCKED
            && evaluation.isAlive()
            && System.currentTimeMillis() < deadline) {
          Thread.sleep(10);
        }
        whileLocked = evaluation.getState();
      }
      evaluation.join(5_000);

      assertEquals(Thread.State.BLOCKED, whileLocked, "loading b.xmi must wait for the lock");
      assertFalse(((EObject) resolved.get()).eIsProxy());
    } finally {
      wrapper.unloadMetamodel(ecore);
    }
  }
}
