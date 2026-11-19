/* ******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package tools.vitruv.dsls.vitruvocl.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The request {@code vitruvocl/constraintUnits}, which the VS Code extension runs units with. */
class ConstraintUnitsRequestTest {

  private static final String URI = "file:///tmp/units.ocl";

  private static final String DOCUMENT =
      String.join(
          "\n",
          "import \"spaceMission.ecore\"",
          "-- comment",
          "context spaceMission::Spacecraft",
          "  inv massPositive:",
          "    -- inside",
          "    self.mass > 0",
          "  pre ready: true",
          "context spaceMission::Mission");

  private final OCLLanguageServer server = new OCLLanguageServer();

  @AfterEach
  void tearDown() {
    server.shutdown();
  }

  @Test
  void returnsTheUnitsOfAnOpenDocumentWithTheirDocumentLines() {
    open(DOCUMENT);

    assertThat(server.constraintUnits(new TextDocumentIdentifier(URI)).join())
        .containsExactly(
            new ConstraintUnits.Unit(
                "INV",
                "massPositive",
                "spaceMission::Spacecraft",
                "context spaceMission::Spacecraft\ninv massPositive:\n    self.mass > 0",
                List.of(3, 4, 6),
                4,
                2),
            new ConstraintUnits.Unit(
                "PRE",
                "ready",
                "spaceMission::Spacecraft",
                "context spaceMission::Spacecraft\npre ready: true",
                List.of(3, 7),
                7,
                2),
            new ConstraintUnits.Unit(
                "EMPTY_CONTEXT",
                null,
                "spaceMission::Mission",
                "context spaceMission::Mission",
                List.of(8),
                8,
                0));
  }

  @Test
  void anUnknownDocumentHasNoUnits() {
    assertThat(server.constraintUnits(new TextDocumentIdentifier("file:///tmp/none.ocl")).join())
        .isEmpty();
  }

  @Test
  void theRequestIsServedAndItsResultSerialized() {
    assertThat(ServiceEndpoints.getSupportedMethods(OCLLanguageServer.class))
        .containsKey(ConstraintUnits.METHOD);

    MessageJsonHandler handler =
        new MessageJsonHandler(ServiceEndpoints.getSupportedMethods(OCLLanguageServer.class));
    ResponseMessage response = new ResponseMessage();
    response.setId("1");
    response.setResult(ConstraintUnits.of("context A inv a: true"));

    assertThat(handler.serialize(response))
        .contains(
            "\"result\":[{\"kind\":\"INV\",\"name\":\"a\",\"contextType\":\"A\","
                + "\"text\":\"context A\\ninv a: true\",\"textLines\":[1,1],\"line\":1,"
                + "\"column\":10}]");
  }

  @Test
  void aMissingNameIsLeftOutOfTheJson() {
    MessageJsonHandler handler =
        new MessageJsonHandler(ServiceEndpoints.getSupportedMethods(OCLLanguageServer.class));
    ResponseMessage response = new ResponseMessage();
    response.setId("1");
    response.setResult(ConstraintUnits.of("context A inv: true"));

    // the extension must treat an absent name like null
    assertThat(handler.serialize(response))
        .contains("\"result\":[{\"kind\":\"INV\",\"contextType\":\"A\",");
  }

  private void open(String text) {
    server
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI, "vitruvocl", 1, text)));
  }
}
