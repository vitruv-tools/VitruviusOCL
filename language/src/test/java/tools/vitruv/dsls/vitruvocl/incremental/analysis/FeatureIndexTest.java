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

package tools.vitruv.dsls.vitruvocl.incremental.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.pipeline.MetamodelWrapper;

/**
 * Which metamodels {@link FeatureIndex#forWrapper} indexes. Besides the global registry it must
 * include every package the model access knows, also when the access is a decorator around the
 * wrapper that loaded the package.
 */
class FeatureIndexTest {

  private static final String NS_URI = "http://vitruv.tools/test/featureindex/probe";
  private static final String FEATURE = "featureIndexProbeName";

  private static final String ECORE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
          name="featureindexprobe" nsURI="%s" nsPrefix="probe">
        <eClassifiers xsi:type="ecore:EClass" name="Probe">
          <eStructuralFeatures xsi:type="ecore:EAttribute" name="%s"
              eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString"/>
        </eClassifiers>
      </ecore:EPackage>
      """
          .formatted(NS_URI, FEATURE);

  @TempDir Path dir;

  @AfterEach
  void unregisterProbe() {
    EPackage.Registry.INSTANCE.remove(NS_URI);
  }

  /**
   * A wrapper whose metamodel is no longer in the global registry, as when another package took
   * over its nsURI or the entry was removed.
   */
  private MetamodelWrapper wrapperWithUnregisteredMetamodel() throws IOException {
    Path ecore = Files.writeString(dir.resolve("probe.ecore"), ECORE);
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(ecore);
    EPackage.Registry.INSTANCE.remove(NS_URI);
    return wrapper;
  }

  @Test
  void indexesTheMetamodelsOfTheWrapper() throws IOException {
    MetamodelWrapper wrapper = wrapperWithUnregisteredMetamodel();

    assertThat(FeatureIndex.forWrapper(wrapper).resolve(FEATURE, null)).hasSize(1);
  }

  @Test
  void indexesTheMetamodelsBehindADecorator() throws IOException {
    MetamodelWrapper wrapper = wrapperWithUnregisteredMetamodel();
    ModelAccess decorator = mock(ModelAccess.class, delegatesTo(wrapper));

    assertThat(FeatureIndex.forWrapper(decorator).resolve(FEATURE, null)).hasSize(1);
  }
}
