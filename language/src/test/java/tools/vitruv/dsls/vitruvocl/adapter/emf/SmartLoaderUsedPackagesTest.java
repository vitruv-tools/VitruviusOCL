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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A model can use packages the constraints do not refer to, such as a PCM repository using the
 * packages of an extension; {@link SmartLoader} loads their metamodels too, so that the model can
 * be loaded at all.
 */
class SmartLoaderUsedPackagesTest {

  private static final String BASE_ECORE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore" name="base" nsURI="http://test/base"
          nsPrefix="base">
        <eClassifiers xsi:type="ecore:EClass" name="Thing">
          <eStructuralFeatures xsi:type="ecore:EReference" name="children" upperBound="-1"
              eType="#//Thing" containment="true"/>
        </eClassifiers>
      </ecore:EPackage>
      """;

  /** An extension of {@code base}, referring to it relatively as Eclipse plugins do. */
  private static final String EXTENSION_ECORE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore" name="ext" nsURI="http://test/ext"
          nsPrefix="ext">
        <eClassifiers xsi:type="ecore:EClass" name="Special" eSuperTypes="base.ecore#//Thing"/>
      </ecore:EPackage>
      """;

  private static final String MODEL =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <base:Thing xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:base="http://test/base"
          xmlns:ext="http://test/ext">
        <children xsi:type="ext:Special"/>
      </base:Thing>
      """;

  @Test
  void theMetamodelsOfAllPackagesAModelUsesAreLoaded(@TempDir Path folder) throws IOException {
    Path base = Files.writeString(folder.resolve("base.ecore"), BASE_ECORE);
    Path extension = Files.writeString(folder.resolve("ext.ecore"), EXTENSION_ECORE);
    Path model = Files.writeString(folder.resolve("model.base"), MODEL);

    SmartLoader.LoadResult result =
        SmartLoader.loadForConstraints(
            List.of("context base::Thing inv a: true"),
            new Path[] {base, extension},
            new Path[] {model});

    assertThat(result.fileErrors).isEmpty();
    assertThat(result.wrapper.getAvailableMetamodels()).contains("base", "ext");
    assertThat(result.wrapper.getAllRootObjects())
        .filteredOn(root -> !(root instanceof EPackage))
        .singleElement()
        .satisfies(root -> assertThat(root.eContents()).hasSize(1));
  }

  @Test
  void aPackageWithoutMetamodelOnlyFailsTheModel(@TempDir Path folder) throws IOException {
    Path base = Files.writeString(folder.resolve("base.ecore"), BASE_ECORE);
    // a namespace URI of its own: EMF's package registry is global, also across tests
    Path model =
        Files.writeString(
            folder.resolve("model.base"), MODEL.replace("http://test/ext", "http://test/missing"));

    SmartLoader.LoadResult result =
        SmartLoader.loadForConstraints(
            List.of("context base::Thing inv a: true"), new Path[] {base}, new Path[] {model});

    assertThat(result.fileErrors)
        .singleElement()
        .satisfies(error -> assertThat(error.toString()).contains("http://test/missing"));
  }
}
