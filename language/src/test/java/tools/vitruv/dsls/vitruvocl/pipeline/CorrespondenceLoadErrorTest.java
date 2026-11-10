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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A malformed {@code .correspondence} file must surface as a load error instead of being ignored,
 * which would silently evaluate correspondence constraints against an empty correspondence model.
 */
class CorrespondenceLoadErrorTest {

  @Test
  void malformedCorrespondenceFileIsReported(@TempDir Path dir) throws IOException {
    Path broken = dir.resolve("broken.correspondence");
    Files.writeString(broken, "<correspondences><unclosed>");
    MetamodelWrapper wrapper = new MetamodelWrapper();

    IOException error = assertThrows(IOException.class, () -> wrapper.loadModelInstance(broken));
    assertTrue(error.getMessage().contains("broken.correspondence"), error.getMessage());
  }

  @Test
  void missingCorrespondenceFileIsReported(@TempDir Path dir) {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    assertThrows(
        IOException.class, () -> wrapper.loadModelInstance(dir.resolve("absent.correspondence")));
  }
}
