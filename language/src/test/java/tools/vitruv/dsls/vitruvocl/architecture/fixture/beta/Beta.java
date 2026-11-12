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

package tools.vitruv.dsls.vitruvocl.architecture.fixture.beta;

import tools.vitruv.dsls.vitruvocl.architecture.fixture.alpha.Alpha;

/** Other half of the deliberate package cycle; see {@link Alpha}. */
public class Beta {
  Alpha alpha;

  void useAlpha() {
    alpha.hashCode();
  }
}
