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

package tools.vitruv.dsls.vitruvocl.adapter.vsum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;

class BatchCachingWrapperTest {

  @Test
  void delegatesMetamodelPackages() {
    EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
    ModelAccess delegate = mock(ModelAccess.class);
    when(delegate.metamodelPackages()).thenReturn(List.of(pkg));

    assertThat(new BatchCachingWrapper(delegate).metamodelPackages()).containsExactly(pkg);
  }
}
