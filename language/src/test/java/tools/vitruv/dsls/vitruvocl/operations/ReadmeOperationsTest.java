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

package tools.vitruv.dsls.vitruvocl.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every operation of the catalog is listed in the README's "Supported Operations" section. */
class ReadmeOperationsTest {

  private static String supportedOperations;

  @BeforeAll
  static void readSection() throws IOException {
    String readme = Files.readString(readme(), StandardCharsets.UTF_8);
    int start = readme.indexOf("### Supported Operations");
    assertThat(start).as("README has a Supported Operations section").isNotNegative();
    Matcher nextHeading = Pattern.compile("\n#{1,3} ").matcher(readme);
    int end = nextHeading.find(start + 1) ? nextHeading.start() : readme.length();
    supportedOperations = readme.substring(start, end);
  }

  /** The README of the repository: the first README.md from the working directory upwards. */
  private static Path readme() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve("README.md");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no README.md above " + Path.of("").toAbsolutePath());
  }

  static Iterable<String> operations() {
    return OclOperations.names();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("operations")
  void operationIsListed(String name) {
    // as a word followed by its parameter list, in the text or in an example
    Pattern listed = Pattern.compile("\\b" + Pattern.quote(name) + "\\(");
    assertThat(listed.matcher(supportedOperations).find())
        .as("README lists %s(...)", name)
        .isTrue();
  }
}
