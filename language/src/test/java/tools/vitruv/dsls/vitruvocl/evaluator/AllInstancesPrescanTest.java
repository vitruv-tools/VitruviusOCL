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

package tools.vitruv.dsls.vitruvocl.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.api.VitruvOclEngine;
import tools.vitruv.dsls.vitruvocl.result.ConstraintResult;

/**
 * Tests the static query analysis step (Wei &amp; Kolovos, BigMDE 2015, Section 3.2): before a
 * constraint is evaluated, every type it needs instances of (its context classifiers and all
 * {@code allInstances()} receivers) is collected from the type-checked AST, so that all of them are
 * fetched with one request instead of one request per type.
 */
class AllInstancesPrescanTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("src/test/resources/test-metamodels/spaceMission.ecore");
  private static final Path SATELLITE_ECORE =
      Path.of("src/test/resources/test-metamodels/satelliteSystem.ecore");
  private static final Path SPACECRAFT_VOYAGER =
      Path.of("src/test/resources/test-models/spacecraft-voyager.spacemission");
  private static final Path SATELLITE_VOYAGER =
      Path.of("src/test/resources/test-models/satellite-voyager.satellitesystem");

  /** Counts single-type and batch requests. */
  private static final class CountingWrapper extends MetamodelWrapper {
    int singleRequests;
    int batchRequests;
    final Set<EClass> batchTypes = new LinkedHashSet<>();

    @Override
    public List<EObject> getAllInstances(EClass eClass) {
      singleRequests++;
      return super.getAllInstances(eClass);
    }

    @Override
    public Map<EClass, List<EObject>> getAllInstances(Collection<EClass> types) {
      batchRequests++;
      batchTypes.addAll(types);
      return super.getAllInstances(types);
    }
  }

  private static CountingWrapper loadWrapper() throws IOException {
    CountingWrapper wrapper = new CountingWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    wrapper.loadMetamodel(SATELLITE_ECORE);
    wrapper.loadModelInstance(SPACECRAFT_VOYAGER);
    wrapper.loadModelInstance(SATELLITE_VOYAGER);
    return wrapper;
  }

  @Test
  void contextAndAllInstancesTypes_areFetchedWithOneBatchRequest() throws IOException {
    CountingWrapper wrapper = loadWrapper();
    String constraint =
        "context spaceMission::Spacecraft inv prescan:"
            + " spaceMission::Payload.allInstances().size() >= 0"
            + " and satelliteSystem::Satellite.allInstances().size() == 1";

    ConstraintResult result = VitruvOclEngine.forModel(wrapper).evaluate(constraint);

    assertTrue(result.isSuccess(), () -> String.valueOf(result.getCompilerErrors()));
    assertTrue(result.isSatisfied());
    assertEquals(1, wrapper.batchRequests);
    assertEquals(0, wrapper.singleRequests);
    assertEquals(
        Set.of(
            wrapper.resolveEClass("spaceMission", "Spacecraft"),
            wrapper.resolveEClass("spaceMission", "Payload"),
            wrapper.resolveEClass("satelliteSystem", "Satellite")),
        wrapper.batchTypes);
  }
}
