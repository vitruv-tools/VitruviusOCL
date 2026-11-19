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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.symboltable.AbstractPhaseVisitor;

/**
 * Package dependency rules for the {@code language} module: every class belongs to a layer, the
 * layers depend only downwards, and there are no package cycles.
 *
 * <p>The layer rule fixes the intended direction of every dependency between layers, so an upward
 * edge fails on its own and does not have to close a cycle first. Cycles between packages of the
 * same layer (e.g. within {@code evaluator..} or {@code incremental..}) are left to the cycle rule.
 * ArchUnit's cycle report does not list every elementary cycle of a package graph, which is why the
 * rules are not reduced to the cycle rule alone.
 *
 * <p>The layers follow the target picture of the architecture plan as far as its packages exist;
 * the integrations (CLI and Vitruv checker) form the top layer above the instance API.
 */
class ArchitectureTest {

  private static final String ROOT_PACKAGE = "tools.vitruv.dsls.vitruvocl";

  /**
   * A layer and its packages, relative to the root package ({@code ..} includes subpackages, the
   * empty string is the root package itself).
   */
  private record Layer(String name, String... packages) {}

  /** The layers from bottom to top; a layer may only be used by the layers above it. */
  private static final List<Layer> LAYERS =
      List.of(
          new Layer("Parser", ""), // ANTLR-generated lexer and parser in the root package
          new Layer("Common", "common"),
          new Layer("Source", "source"),
          new Layer("Model", "model"),
          new Layer("Types", "types"),
          new Layer("SymbolTable", "symboltable"),
          new Layer("TypeChecker", "typechecker", "operations"),
          new Layer("Evaluator", "evaluator.."),
          new Layer("Result", "result"),
          new Layer("Compiler", "compiler"),
          new Layer("Incremental", "incremental.."),
          new Layer("Adapter", "adapter.."),
          new Layer("Api", "api"),
          new Layer("Top", "cli", "vitruv"));

  private static JavaClasses productionClasses;

  /**
   * Imports only this module's production classes. Importing by package would also pick up {@code
   * vitruvocl-allinstances}, which shares the root package and, depending on how the build is
   * invoked, would be read from its {@code target/classes} or from a jar in the local repository.
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
  void packagesAreFreeOfCycles() {
    slices()
        .matching(ROOT_PACKAGE + ".(**)")
        .namingSlices("$1")
        .should()
        .beFreeOfCycles()
        .as("packages below " + ROOT_PACKAGE + " should be free of cycles")
        .check(productionClasses);
  }

  @Test
  void layersDependOnlyDownwards() {
    LayeredArchitecture architecture = layeredArchitecture().consideringOnlyDependenciesInLayers();
    for (Layer layer : LAYERS) {
      architecture = architecture.layer(layer.name()).definedBy(qualified(layer.packages()));
    }
    for (int i = 0; i < LAYERS.size() - 1; i++) {
      architecture =
          architecture.whereLayer(LAYERS.get(i).name()).mayOnlyBeAccessedByLayers(namesAbove(i));
    }
    architecture
        .whereLayer(LAYERS.get(LAYERS.size() - 1).name())
        .mayNotBeAccessedByAnyLayer()
        .check(productionClasses);
  }

  /** Without this, a new package would escape the layer rule, which ignores unlisted packages. */
  @Test
  void everyClassBelongsToALayer() {
    String[] allowed =
        LAYERS.stream()
            .flatMap(layer -> Arrays.stream(qualified(layer.packages())))
            .toArray(String[]::new);

    classes()
        .should()
        .resideInAnyPackage(allowed)
        .as("every class belongs to a layer of " + ROOT_PACKAGE)
        .check(productionClasses);
  }

  /**
   * A layer that lists several packages stays non-empty when one of them disappears, so the layer
   * rule would not notice a renamed or removed package; this test does.
   */
  @Test
  void everyListedPackageExists() {
    List<String> missing =
        LAYERS.stream()
            .flatMap(layer -> Arrays.stream(qualified(layer.packages())))
            .map(pkg -> pkg.endsWith("..") ? pkg.substring(0, pkg.length() - 2) : pkg)
            .filter(pkg -> !productionClasses.containPackage(pkg))
            .toList();

    assertThat(missing).isEmpty();
  }

  private static String[] qualified(String... packages) {
    return Arrays.stream(packages)
        .map(p -> p.isEmpty() ? ROOT_PACKAGE : ROOT_PACKAGE + "." + p)
        .toArray(String[]::new);
  }

  private static String[] namesAbove(int index) {
    return LAYERS.subList(index + 1, LAYERS.size()).stream()
        .map(Layer::name)
        .toArray(String[]::new);
  }
}
