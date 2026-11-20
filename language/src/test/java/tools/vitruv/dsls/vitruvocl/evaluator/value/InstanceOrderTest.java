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

package tools.vitruv.dsls.vitruvocl.evaluator.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.Test;

/** The identity tie-break of {@link InstanceOrder} for instances with colliding hash codes. */
class InstanceOrderTest {

  private final EClass eClass = newEClass();
  private final EObject first = EcoreUtil.create(eClass);
  private final EObject second = EcoreUtil.create(eClass);

  private static EClass newEClass() {
    EClass eClass = EcoreFactory.eINSTANCE.createEClass();
    eClass.setName("Node");
    EcoreFactory.eINSTANCE.createEPackage().getEClassifiers().add(eClass);
    return eClass;
  }

  @Test
  void identityHashCollisionIsBrokenByStableTieBreak() {
    int forward = InstanceOrder.compare(first, second, 42, 42);
    int backward = InstanceOrder.compare(second, first, 42, 42);
    assertNotEquals(0, forward, "distinct instances with colliding hashes must differ");
    assertEquals(-Integer.signum(forward), Integer.signum(backward));
    assertEquals(forward, InstanceOrder.compare(first, second, 42, 42), "order must be stable");
    assertEquals(0, InstanceOrder.compare(first, first, 42, 42));
  }
}
