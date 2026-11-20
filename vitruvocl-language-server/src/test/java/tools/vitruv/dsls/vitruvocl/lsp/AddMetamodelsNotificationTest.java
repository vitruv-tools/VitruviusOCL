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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The notification {@code vitruvocl/addMetamodels}, with which the VS Code extension adds the
 * metamodels of a project's Maven dependencies.
 */
class AddMetamodelsNotificationTest {

  private static final Path SPACEMISSION_ECORE =
      Path.of("../language/src/test/resources/test-metamodels/spaceMission.ecore");
  private static final String URI = "file:///tmp/added.ocl";

  private final OCLLanguageServer server = new OCLLanguageServer();
  private final LanguageClient client = mock(LanguageClient.class);

  @AfterEach
  void tearDown() {
    server.shutdown();
  }

  @Test
  void addedMetamodelsAreKnownWhenTheOpenDocumentsAreAnalyzedAgain() {
    server.connect(client);
    server
        .getTextDocumentService()
        .didOpen(
            new DidOpenTextDocumentParams(
                new TextDocumentItem(
                    URI, "vitruvocl", 1, "context spaceMission::Spacecraft inv a: self.mass > 0")));
    verify(client, timeout(10_000))
        .publishDiagnostics(argThat(p -> URI.equals(p.getUri()) && !p.getDiagnostics().isEmpty()));

    server.addMetamodels(
        new OCLLanguageServer.MetamodelFiles(
            List.of(SPACEMISSION_ECORE.toAbsolutePath().toString())));

    verify(client, timeout(10_000))
        .publishDiagnostics(argThat(p -> URI.equals(p.getUri()) && p.getDiagnostics().isEmpty()));
  }

  @Test
  void anEmptyOrMissingListChangesNothing() {
    server.connect(client);
    server.addMetamodels(null);
    server.addMetamodels(new OCLLanguageServer.MetamodelFiles(null));
    server.addMetamodels(new OCLLanguageServer.MetamodelFiles(List.of()));
    verifyNoInteractions(client);
  }

  @Test
  void aMetamodelAddedLaterResolvesItsPlatformReferenceToOneAddedBefore(@TempDir Path folder)
      throws IOException {
    Path base =
        Files.writeString(
            folder.resolve("base.ecore"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore" name="base"
                nsURI="http://test/lsbase" nsPrefix="base">
              <eClassifiers xsi:type="ecore:EClass" name="Thing">
                <eStructuralFeatures xsi:type="ecore:EAttribute" name="weight"
                    eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EInt"/>
              </eClassifiers>
            </ecore:EPackage>
            """);
    Path extension =
        Files.writeString(
            folder.resolve("ext.ecore"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore" name="lsext"
                nsURI="http://test/lsext" nsPrefix="lsext">
              <eClassifiers xsi:type="ecore:EClass" name="Special"
                  eSuperTypes="platform:/plugin/test.base/model/base.ecore#//Thing"/>
            </ecore:EPackage>
            """);
    server.connect(client);
    server.addMetamodels(new OCLLanguageServer.MetamodelFiles(List.of(base.toString())));
    server.addMetamodels(new OCLLanguageServer.MetamodelFiles(List.of(extension.toString())));

    String uri = "file:///tmp/inherited.ocl";
    server
        .getTextDocumentService()
        .didOpen(
            new DidOpenTextDocumentParams(
                new TextDocumentItem(
                    uri, "vitruvocl", 1, "context lsext::Special inv a: self.weight > 0")));

    // the inherited attribute is only known if the supertype in base.ecore was resolved
    verify(client, timeout(10_000))
        .publishDiagnostics(argThat(p -> uri.equals(p.getUri()) && p.getDiagnostics().isEmpty()));
  }

  @Test
  void theNotificationIsServedAndItsParameterRead() {
    assertThat(ServiceEndpoints.getSupportedMethods(OCLLanguageServer.class))
        .containsKey(OCLLanguageServer.ADD_METAMODELS);

    MessageJsonHandler handler =
        new MessageJsonHandler(ServiceEndpoints.getSupportedMethods(OCLLanguageServer.class));
    NotificationMessage message =
        (NotificationMessage)
            handler.parseMessage(
                "{\"jsonrpc\":\"2.0\",\"method\":\"vitruvocl/addMetamodels\","
                    + "\"params\":{\"paths\":[\"C:/a/pcm.ecore\"]}}");

    assertThat(message.getParams())
        .isEqualTo(new OCLLanguageServer.MetamodelFiles(List.of("C:/a/pcm.ecore")));
  }
}
