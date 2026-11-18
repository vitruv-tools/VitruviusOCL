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

package tools.vitruv.dsls.vitruvocl;

import java.nio.file.Path;

/** Location of the test model instances, relative to the module directory. */
public final class TestModels {

  /**
   * The directory that relative model file names in tests refer to; pass it to {@code
   * VitruvOclEngine#withModelDirectory}.
   */
  public static final Path DIRECTORY = Path.of("src/test/resources/test-models");

  private TestModels() {}
}
