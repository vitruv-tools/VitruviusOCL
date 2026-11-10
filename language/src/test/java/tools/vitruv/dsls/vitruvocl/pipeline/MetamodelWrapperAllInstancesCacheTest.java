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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.Test;

/**
 * Tests the result cache of {@link MetamodelWrapper#getAllInstances}: one single-pass traversal
 * (Wei &amp; Kolovos, BigMDE 2015, Section 3.4) serves every requested type, its results are reused
 * across calls, and any change that can alter an extension invalidates them.
 */
class MetamodelWrapperAllInstancesCacheTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("src/test/resources/test-metamodels/spaceMission.ecore");
  private static final Path SATELLITE_ECORE =
      Path.of("src/test/resources/test-metamodels/satelliteSystem.ecore");
  private static final Path SPACECRAFT_VOYAGER =
      Path.of("src/test/resources/test-models/spacecraft-voyager.spacemission");
  private static final Path SPACECRAFT_ACTIVE =
      Path.of("src/test/resources/test-models/spacecraft-active.spacemission");
  private static final Path SATELLITE_VOYAGER =
      Path.of("src/test/resources/test-models/satellite-voyager.satellitesystem");

  private static MetamodelWrapper loadWrapper(Path... models) throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    wrapper.loadMetamodel(SATELLITE_ECORE);
    for (Path model : models) {
      wrapper.loadModelInstance(model);
    }
    return wrapper;
  }

  @Test
  void batchQuery_returnsEveryRequestedTypeFromOnePass() throws IOException {
    MetamodelWrapper wrapper =
        loadWrapper(SPACECRAFT_VOYAGER, SPACECRAFT_ACTIVE, SATELLITE_VOYAGER);
    EClass spacecraft = wrapper.resolveEClass("spaceMission", "Spacecraft");
    EClass satellite = wrapper.resolveEClass("satelliteSystem", "Satellite");

    Map<EClass, List<EObject>> result = wrapper.getAllInstances(List.of(spacecraft, satellite));

    assertEquals(2, result.get(spacecraft).size());
    assertEquals(1, result.get(satellite).size());
  }

  @Test
  void repeatedQuery_reusesTheCachedResult() throws IOException {
    MetamodelWrapper wrapper = loadWrapper(SPACECRAFT_VOYAGER, SATELLITE_VOYAGER);
    EClass spacecraft = wrapper.resolveEClass("spaceMission", "Spacecraft");

    List<EObject> first = wrapper.getAllInstances(spacecraft);
    List<EObject> second = wrapper.getAllInstances(spacecraft);

    assertSame(first, second);
  }

  @Test
  void containmentChange_invalidatesTheCachedResult() throws IOException {
    MetamodelWrapper wrapper = loadWrapper(SPACECRAFT_VOYAGER);
    EClass payload = wrapper.resolveEClass("spaceMission", "Payload");
    assertEquals(0, wrapper.getAllInstances(payload).size());

    EObject voyager = wrapper.getContextObjects().get(0);
    EReference payloads = (EReference) voyager.eClass().getEStructuralFeature("payloads");
    @SuppressWarnings("unchecked")
    List<EObject> payloadList = (List<EObject>) voyager.eGet(payloads);
    EObject camera = EcoreUtil.create(payload);
    payloadList.add(camera);

    assertEquals(List.of(camera), wrapper.getAllInstances(payload));

    payloadList.remove(camera);
    assertEquals(0, wrapper.getAllInstances(payload).size());
  }

  @Test
  void loadingAnotherModel_invalidatesTheCachedResult() throws IOException {
    MetamodelWrapper wrapper = loadWrapper(SPACECRAFT_VOYAGER);
    EClass spacecraft = wrapper.resolveEClass("spaceMission", "Spacecraft");
    assertEquals(1, wrapper.getAllInstances(spacecraft).size());

    wrapper.loadModelInstance(SPACECRAFT_ACTIVE);

    assertEquals(2, wrapper.getAllInstances(spacecraft).size());
  }

  @Test
  void typeFirstRequestedLater_isAddedToTheCacheConfiguration() throws IOException {
    MetamodelWrapper wrapper = loadWrapper(SPACECRAFT_VOYAGER, SATELLITE_VOYAGER);
    EClass spacecraft = wrapper.resolveEClass("spaceMission", "Spacecraft");
    EClass satellite = wrapper.resolveEClass("satelliteSystem", "Satellite");
    assertEquals(1, wrapper.getAllInstances(spacecraft).size());

    assertEquals(1, wrapper.getAllInstances(satellite).size());
    assertEquals(1, wrapper.getAllInstances(spacecraft).size());
  }
}
