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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Correspondences are stored as URIs. An ID attribute or a resource URI is part of an object's URI,
 * so changing either must be reflected by the correspondence index, as the per-call URI lookup did.
 */
class CorrespondenceIndexInvalidationTest {

  private static final String ECORE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
          name="corrids" nsURI="http://test/corrids" nsPrefix="corrids">
        <eClassifiers xsi:type="ecore:EClass" name="Node">
          <eStructuralFeatures xsi:type="ecore:EAttribute" name="key" iD="true"
              eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString"/>
          <eStructuralFeatures xsi:type="ecore:EReference" name="next" eType="#//Node"/>
        </eClassifiers>
      </ecore:EPackage>
      """;

  private static final String CORRESPONDENCES =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <correspondence:Correspondences xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:correspondence="http://vitruv.tools/metamodels/change/correspondence/1.0">
        <correspondences xsi:type="correspondence:ManualCorrespondence" tag="t">
          <leftEObjects href="left.xmi#a"/>
          <rightEObjects href="right.xmi#b"/>
        </correspondences>
      </correspondence:Correspondences>
      """;

  @TempDir Path dir;
  private MetamodelWrapper wrapper;
  private Path ecore;
  private EObject left;
  private EObject right;

  private static String node(String key) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <corrids:Node xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
            xmlns:corrids="http://test/corrids" key="%s"/>
        """
        .formatted(key);
  }

  @BeforeEach
  void load() throws IOException {
    ecore = dir.resolve("corrids.ecore");
    Files.writeString(ecore, ECORE);
    Files.writeString(dir.resolve("left.xmi"), node("a"));
    Files.writeString(dir.resolve("right.xmi"), node("b"));
    Files.writeString(dir.resolve("c.correspondence"), CORRESPONDENCES);
    wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(ecore);
    wrapper.loadModelInstance(dir.resolve("left.xmi"));
    wrapper.loadModelInstance(dir.resolve("right.xmi"));
    wrapper.loadModelInstance(dir.resolve("c.correspondence"));
    left = wrapper.getContextObjects().get(0);
    right = wrapper.getContextObjects().get(1);
    assertEquals(Set.of(right), wrapper.getCorrespondingObjects(left));
  }

  @AfterEach
  void unload() {
    wrapper.unloadMetamodel(ecore);
  }

  @Test
  void changedIdAttributeIsSeen() {
    left.eSet(left.eClass().getEStructuralFeature("key"), "z");

    assertEquals(Set.of(), wrapper.getCorrespondingObjects(left));
  }

  @Test
  void changedResourceUriIsSeen() {
    left.eResource().setURI(URI.createFileURI(dir.resolve("moved.xmi").toString()));

    assertEquals(Set.of(), wrapper.getCorrespondingObjects(left));
  }

  @Test
  void replacedRootIsSeen() {
    EObject replacement = org.eclipse.emf.ecore.util.EcoreUtil.create(right.eClass());
    replacement.eSet(right.eClass().getEStructuralFeature("key"), "b");
    right.eResource().getContents().set(0, replacement);

    assertEquals(Set.of(replacement), wrapper.getCorrespondingObjects(left));
  }

  @Test
  void resourceLoadedThroughAProxyIsSeen() throws IOException {
    Files.writeString(dir.resolve("late.xmi"), node("late"));
    Files.writeString(
        dir.resolve("start.xmi"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <corrids:Node xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
            xmlns:corrids="http://test/corrids" key="start">
          <next href="late.xmi#late"/>
        </corrids:Node>
        """);
    Files.writeString(
        dir.resolve("late.correspondence"),
        CORRESPONDENCES
            .replace("left.xmi#a", "start.xmi#start")
            .replace("right.xmi#b", "late.xmi#late"));
    wrapper.loadModelInstance(dir.resolve("start.xmi"));
    wrapper.loadModelInstance(dir.resolve("late.correspondence"));
    EObject start = wrapper.getContextObjects().get(2);
    assertEquals(Set.of(), wrapper.getCorrespondingObjects(start)); // late.xmi not loaded yet

    EObject late = (EObject) start.eGet(start.eClass().getEStructuralFeature("next"));

    assertEquals(Set.of(late), wrapper.getCorrespondingObjects(start));
  }
}
