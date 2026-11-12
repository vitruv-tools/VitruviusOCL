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

package tools.vitruv.dsls.vitruvocl.architecture;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.net.URL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.common.AbstractPhaseVisitor;

/**
 * Package dependency rules for the {@code language} module.
 *
 * <p>The package cycles that exist today are frozen: they are recorded in the violation store
 * ({@code src/test/archunit_store}, configured in {@code archunit.properties}). Only a new package
 * cycle, i.e. a new sequence of packages, fails the build; new class dependencies along an already
 * frozen cycle are tolerated (see {@link CyclePathViolationMatcher}).
 *
 * <p>Maintaining the store:
 *
 * <ul>
 *   <li>A cycle that disappears is removed from the store automatically. Commit the changed store
 *       together with the change that removed the cycle.
 *   <li>To accept a new cycle deliberately, run the test once with {@code
 *       -Darchunit.freeze.refreeze=true} and commit the resulting store.
 *   <li>CI can pass {@code -Darchunit.freeze.store.default.allowStoreUpdate=false} so that a store
 *       which should have shrunk but was not committed fails the build.
 * </ul>
 */
class ArchitectureTest {

  private static final String ROOT_PACKAGE = "tools.vitruv.dsls.vitruvocl";

  private static JavaClasses productionClasses;

  /**
   * Imports only this module's production classes. Importing by package would also pick up
   * {@code vitruvocl-allinstances}, which shares the root package and, depending on how the build
   * is invoked, would be read from its {@code target/classes} or from a jar in the local repository.
   */
  @BeforeAll
  static void importClasses() {
    URL moduleClasses =
        AbstractPhaseVisitor.class.getProtectionDomain().getCodeSource().getLocation();
    productionClasses = new ClassFileImporter().importUrl(moduleClasses);
  }

  @Test
  void importCoversOnlyThisModule() {
    assertThat(productionClasses.containPackage(ROOT_PACKAGE + ".common")).isTrue();
    assertThat(productionClasses.stream().map(JavaClass::getPackageName))
        .noneMatch(name -> name.startsWith(ROOT_PACKAGE + ".allinstances"));
  }

  @Test
  void packagesHaveNoNewCycles() {
    ArchRule noCycles =
        slices()
            .matching(ROOT_PACKAGE + ".(**)")
            .namingSlices("$1")
            .should()
            .beFreeOfCycles()
            .as("packages below " + ROOT_PACKAGE + " should be free of cycles");

    FreezingArchRule.freeze(noCycles).check(productionClasses);
  }
}
