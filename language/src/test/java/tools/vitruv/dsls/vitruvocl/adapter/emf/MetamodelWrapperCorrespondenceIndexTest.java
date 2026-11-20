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

package tools.vitruv.dsls.vitruvocl.adapter.emf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;

/**
 * Correspondences loaded from a {@code .correspondence} file are resolved once into an index, so
 * that the evaluator's memoized and pre-filtered correspondence fast paths apply to file-based
 * models too, instead of computing EMF URIs for every pair it checks.
 */
class MetamodelWrapperCorrespondenceIndexTest {

  private static final Path MODELS = Path.of("src/test/resources/test-models");
  private static final String TAG = "voyager-match";

  private static MetamodelWrapper load() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(Path.of("src/test/resources/test-metamodels/spaceMission.ecore"));
    wrapper.loadMetamodel(Path.of("src/test/resources/test-metamodels/satelliteSystem.ecore"));
    wrapper.loadModelInstance(MODELS.resolve("spacecraft-voyager.spacemission").toAbsolutePath());
    wrapper.loadModelInstance(
        MODELS.resolve("satellite-voyager.satellitesystem").toAbsolutePath());
    wrapper.loadModelInstance(MODELS.resolve("correspondences.correspondence").toAbsolutePath());
    return wrapper;
  }

  @Test
  void enablesTheEvaluatorsFastPaths() throws IOException {
    MetamodelWrapper wrapper = load();
    EObject spacecraft = wrapper.getContextObjects().get(0);
    EObject satellite = wrapper.getContextObjects().get(1);

    assertTrue(wrapper.hasTaggedCorrespondenceLookup());
    assertEquals(
        Optional.of(Set.of(spacecraft, satellite)), wrapper.getAllObjectsInACorrespondence());
    assertEquals(Map.of(TAG, Set.of(satellite)), wrapper.getCorrespondingObjectsByTag(spacecraft));
    assertEquals(Set.of(spacecraft), wrapper.getCorrespondingObjectsWithTag(satellite, TAG));
  }

  @Test
  void answersLikeTheUriBasedLookup() throws IOException {
    MetamodelWrapper wrapper = load();
    EObject spacecraft = wrapper.getContextObjects().get(0);
    EObject satellite = wrapper.getContextObjects().get(1);

    assertEquals(Set.of(satellite), wrapper.getCorrespondingObjects(spacecraft));
    assertEquals(Set.of(spacecraft), wrapper.getCorrespondingObjects(satellite));
    assertTrue(wrapper.correspondenceHasTag(spacecraft, satellite, TAG));
    assertTrue(wrapper.correspondenceHasTag(satellite, spacecraft, TAG));
    assertFalse(wrapper.correspondenceHasTag(spacecraft, satellite, "other"));
  }
}
