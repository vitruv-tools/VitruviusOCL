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

package tools.vitruv.dsls.vitruvocl.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.operations.OclOperation;
import tools.vitruv.dsls.vitruvocl.operations.OclOperation.Family;
import tools.vitruv.dsls.vitruvocl.operations.OclOperations;

/** Hover docs and completion must cover every operation of the shared catalog. */
class OclOperationDocsCoverageTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");

  @Test
  void everyCatalogOperationIsDocumented() {
    assertThat(OclOperations.names())
        .allSatisfy(name -> assertThat(OclOperationDocs.lookup(name)).as(name).isPresent());
  }

  @Test
  void collectionCompletionOffersAllCollectionIteratorAndTypeOperations() throws IOException {
    MetamodelWrapper wrapper = new MetamodelWrapper();
    wrapper.loadMetamodel(SPACEMISSION_ECORE);
    String text = "context spaceMission::Spacecraft inv x:\n  Set{1, 2}.size() > 0";
    DocumentAnalysis analysis = new DocumentAnalyzer(wrapper).analyze(text);

    // cursor right after "  Set{1, 2}." - the receiver is a Set
    List<CompletionItem> items =
        new CompletionProvider(wrapper).getCompletions(text, new Position(1, 12), analysis);

    List<String> expected =
        OclOperations.ofFamilies(Family.COLLECTION, Family.ITERATOR, Family.TYPE).stream()
            .map(OclOperation::name)
            .toList();
    assertThat(items).extracting(CompletionItem::getFilterText).containsAll(expected);
  }
}
