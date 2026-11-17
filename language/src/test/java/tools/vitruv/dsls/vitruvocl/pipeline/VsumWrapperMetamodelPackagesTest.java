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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Collection;
import java.util.List;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.Test;
import tools.vitruv.change.correspondence.Correspondence;
import tools.vitruv.change.correspondence.view.CorrespondenceModelView;
import tools.vitruv.dsls.vitruvocl.incremental.analysis.FeatureIndex;
import tools.vitruv.framework.vsum.constraints.ConstraintCheckContext;

/**
 * The metamodel packages of a {@link VsumWrapper} built for one constraint check include the
 * packages of the model elements, which need not be registered globally.
 */
class VsumWrapperMetamodelPackagesTest {

  private static final String FEATURE = "vsumWrapperProbeName";

  @Test
  void includesUnregisteredPackagesOfTheModels() {
    EPackage pkg = unregisteredPackage();
    VsumWrapper wrapper = new VsumWrapper(contextWithRootOf(pkg));

    assertThat(wrapper.metamodelPackages()).contains(pkg);
    assertThat(FeatureIndex.forWrapper(wrapper).resolve(FEATURE, null)).hasSize(1);
  }

  private static EPackage unregisteredPackage() {
    EcoreFactory factory = EcoreFactory.eINSTANCE;
    EAttribute attribute = factory.createEAttribute();
    attribute.setName(FEATURE);
    attribute.setEType(EcorePackage.Literals.ESTRING);
    EClass probe = factory.createEClass();
    probe.setName("VsumProbe");
    probe.getEStructuralFeatures().add(attribute);
    EPackage pkg = factory.createEPackage();
    pkg.setName("vsumwrapperprobe");
    pkg.setNsURI("http://vitruv.tools/test/vsumwrapper/probe");
    pkg.getEClassifiers().add(probe);
    return pkg;
  }

  @SuppressWarnings("unchecked")
  private static ConstraintCheckContext contextWithRootOf(EPackage pkg) {
    Resource resource = new ResourceImpl();
    resource.getContents().add(EcoreUtil.create((EClass) pkg.getEClassifiers().get(0)));
    CorrespondenceModelView<Correspondence> correspondences = mock(CorrespondenceModelView.class);
    return new ConstraintCheckContext() {
      @Override
      public Collection<Resource> getModelResources() {
        return List.of(resource);
      }

      @Override
      public CorrespondenceModelView<Correspondence> getCorrespondenceModel() {
        return correspondences;
      }
    };
  }
}
