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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.dsls.vitruvocl.incremental.support.FakeCorrespondenceModel;
import tools.vitruv.dsls.vitruvocl.incremental.support.LiveModel;

/**
 * The pruned {@code allInstances()} traversal must know every loaded package, also one whose name
 * is shadowed by another package in the name-keyed registry: otherwise a subtype from it, held by
 * a reference the analysis considers irrelevant, hides the instances below it.
 */
class AllInstancesPackageCoverageTest {

  private static final String HEADER =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
      """;

  private static final String BASE_ECORE =
      HEADER
          + """
              name="w1shared" nsURI="http://test/w1/a" nsPrefix="w1a">
            <eClassifiers xsi:type="ecore:EClass" name="Root">
              <eStructuralFeatures xsi:type="ecore:EReference" name="items" upperBound="-1"
                  eType="#//Base" containment="true"/>
            </eClassifiers>
            <eClassifiers xsi:type="ecore:EClass" name="Base"/>
            <eClassifiers xsi:type="ecore:EClass" name="X"/>
          </ecore:EPackage>
          """;

  /** Same package name as {@link #BASE_ECORE}, so the name-keyed registry keeps only one. */
  private static final String EXTENSION_ECORE =
      HEADER
          + """
              name="w1shared" nsURI="http://test/w1/b" nsPrefix="w1b">
            <eClassifiers xsi:type="ecore:EClass" name="Sub" eSuperTypes="http://test/w1/a#//Base">
              <eStructuralFeatures xsi:type="ecore:EReference" name="xs" upperBound="-1"
                  eType="ecore:EClass http://test/w1/a#//X" containment="true"/>
            </eClassifiers>
          </ecore:EPackage>
          """;

  private static final String MODEL =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <w1a:Root xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:w1a="http://test/w1/a" xmlns:w1b="http://test/w1/b">
        <items xsi:type="w1b:Sub">
          <xs/>
        </items>
      </w1a:Root>
      """;

  @TempDir Path dir;

  @Test
  void instancesBelowASubtypeFromAShadowedPackageAreFound() throws IOException {
    Path base = dir.resolve("w1base.ecore");
    Path extension = dir.resolve("w1ext.ecore");
    Path model = dir.resolve("model.xmi");
    Files.writeString(base, BASE_ECORE);
    Files.writeString(extension, EXTENSION_ECORE);
    Files.writeString(model, MODEL);

    MetamodelWrapper wrapper = new MetamodelWrapper();
    try {
      // loaded last, the base package wins the name and shadows the extension
      wrapper.loadMetamodel(extension);
      wrapper.loadMetamodel(base);
      wrapper.loadModelInstance(model);
      EClass x = wrapper.resolveEClass("w1shared", "X");

      List<EObject> instances = wrapper.getAllInstances(x);

      assertEquals(1, instances.size(), "the X below w1b:Sub must be found");
    } finally {
      wrapper.unloadMetamodel(extension);
      wrapper.unloadMetamodel(base);
    }
  }

  @Test
  void subtypeFromAPackageOnlyInTheGlobalRegistryIsFound() throws IOException {
    Path base = dir.resolve("w1base.ecore");
    Path extension = dir.resolve("w1ext.ecore");
    Path model = dir.resolve("model.xmi");
    Files.writeString(base, BASE_ECORE);
    Files.writeString(extension, EXTENSION_ECORE);
    Files.writeString(model, MODEL);

    // another wrapper registers the extension globally (by nsURI); this one never loads it
    MetamodelWrapper other = new MetamodelWrapper();
    MetamodelWrapper wrapper = new MetamodelWrapper();
    try {
      other.loadMetamodel(extension);
      wrapper.loadMetamodel(base);
      wrapper.loadModelInstance(model);
      EClass x = wrapper.resolveEClass("w1shared", "X");

      assertEquals(1, wrapper.getAllInstances(x).size(), "the X below w1b:Sub must be found");
    } finally {
      wrapper.unloadMetamodel(base);
      other.unloadMetamodel(extension);
    }
  }

  private static EPackage pkg(String name, String nsUri) {
    EPackage p = EcoreFactory.eINSTANCE.createEPackage();
    p.setName(name);
    p.setNsURI(nsUri);
    return p;
  }

  private static EClass eClass(EPackage p, String name) {
    EClass c = EcoreFactory.eINSTANCE.createEClass();
    c.setName(name);
    p.getEClassifiers().add(c);
    return c;
  }

  private static EReference containment(EClass owner, String name, EClass type) {
    EReference r = EcoreFactory.eINSTANCE.createEReference();
    r.setName(name);
    r.setEType(type);
    r.setContainment(true);
    r.setUpperBound(-1);
    owner.getEStructuralFeatures().add(r);
    return r;
  }

  @Test
  @SuppressWarnings("unchecked")
  void liveVsumSeesAPackageRegisteredAfterTheFirstQuery() {
    EPackage first = pkg("w1live", "http://test/w1/live/a");
    EClass root = eClass(first, "Root");
    EClass base = eClass(first, "Base");
    EClass x = eClass(first, "X");
    EReference items = containment(root, "items", base);
    EPackage.Registry.INSTANCE.put(first.getNsURI(), first);

    EPackage second = pkg("w1live", "http://test/w1/live/b");
    EClass sub = eClass(second, "Sub");
    sub.getESuperTypes().add(base);
    EReference xs = containment(sub, "xs", x);
    try {
      ResourceSet rs = new ResourceSetImpl();
      Resource resource = new ResourceImpl(URI.createURI("memory:/w1"));
      rs.getResources().add(resource);
      EObject r = EcoreUtil.create(root);
      resource.getContents().add(r);
      LiveModel live = LiveModel.of(rs, List.of(resource), new FakeCorrespondenceModel());
      VsumWrapper wrapper = live.wrapper();
      assertEquals(0, wrapper.getAllInstances(x).size()); // builds the engine

      EPackage.Registry.INSTANCE.put(second.getNsURI(), second);
      EObject s = EcoreUtil.create(sub);
      ((List<EObject>) s.eGet(xs)).add(EcoreUtil.create(x));
      ((List<EObject>) r.eGet(items)).add(s);

      assertEquals(1, wrapper.getAllInstances(x).size(), "the X below Sub must be found");
    } finally {
      EPackage.Registry.INSTANCE.remove(first.getNsURI());
      EPackage.Registry.INSTANCE.remove(second.getNsURI());
    }
  }
}
