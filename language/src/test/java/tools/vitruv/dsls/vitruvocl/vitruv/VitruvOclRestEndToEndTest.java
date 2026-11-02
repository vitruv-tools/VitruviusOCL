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

package tools.vitruv.dsls.vitruvocl.vitruv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.description.VitruviusChangeResolverFactory;
import tools.vitruv.change.composite.recording.ChangeRecorder;
import tools.vitruv.change.correspondence.CorrespondencePackage;
import tools.vitruv.change.interaction.UserInteractionFactory;
import tools.vitruv.framework.remote.server.VitruvServer;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

/**
 * Manages and validates VitruvOCL constraints of a virtual model through the REST API of the
 * Vitruv-Server: a constraint created with {@code POST /vsum/constraints} is validated by {@code
 * POST /vsum/constraints/validate}, whose violations reference elements of the returned models.
 */
class VitruvOclRestEndToEndTest {
  private static final String FILE_EXTENSION = "shop";
  private static final String CHEAP = "shop::Item::cheap";

  @TempDir Path folder;

  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private ShopMetamodel shop;
  private InternalVirtualModel vsum;
  private VitruvServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws Exception {
    shop = new ShopMetamodel();
    shop.register();
    Resource.Factory.Registry.INSTANCE
        .getExtensionToFactoryMap()
        .put(FILE_EXTENSION, new XMIResourceFactoryImpl());
    EPackage.Registry.INSTANCE.put(CorrespondencePackage.eNS_URI, CorrespondencePackage.eINSTANCE);
    vsum =
        new VirtualModelBuilder()
            .withStorageFolder(folder.resolve("vsum"))
            .withUserInteractor(
                UserInteractionFactory.instance.createUserInteractor(
                    UserInteractionFactory.instance.createPredefinedInteractionResultProvider(
                        null)))
            .withConstraintChecker(VitruvOclConstraintChecker.fromString(""))
            .buildAndInitialize();
    propagateItems(shop.createItem("book", 20), shop.createItem("laptop", 1500));
    int port = freePort();
    server = new VitruvServer(() -> vsum, port, "localhost");
    server.start();
    baseUrl = "http://localhost:" + port;
  }

  @AfterEach
  void stopServer() {
    server.stop();
    vsum.dispose();
    Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().remove(FILE_EXTENSION);
    shop.unregister();
  }

  @Test
  @DisplayName("a constraint created over REST is validated with violations in the returned models")
  void createdConstraintIsValidated() throws Exception {
    HttpResponse<String> created =
        send(
            "POST",
            "/vsum/constraints",
            "{\"language\": \"vitruvocl\","
                + " \"source\": \"context shop::Item inv cheap: self.price < 1000\"}");
    assertEquals(201, created.statusCode(), created.body());
    assertEquals(CHEAP, json.readTree(created.body()).get("id").asText());

    JsonNode validation =
        json.readTree(send("POST", "/vsum/constraints/validate?includeModels", "").body());

    assertFalse(validation.get("satisfied").asBoolean());
    JsonNode result = validation.get("results").get(0);
    assertEquals(CHEAP, result.get("constraintId").asText());
    JsonNode violations = result.get("violations");
    assertEquals(1, violations.size());
    String uuid = violations.get(0).get("uuid").asText();
    List<String> ids = new ArrayList<>();
    validation.get("models").findValues("_id").forEach(id -> ids.add(id.asText()));
    assertTrue(ids.contains(uuid), "the violating element is part of the returned models");
  }

  @Test
  @DisplayName("updated and deleted constraints change the validation over REST")
  void updatedAndDeletedConstraints() throws Exception {
    send(
        "POST",
        "/vsum/constraints",
        "{\"language\": \"vitruvocl\","
            + " \"source\": \"context shop::Item inv cheap: self.price < 1000\"}");
    String path = "/vsum/constraints/" + URLEncoder.encode(CHEAP, StandardCharsets.UTF_8);

    HttpResponse<String> updated =
        send("PUT", path, "{\"source\": \"context shop::Item inv cheap: self.price < 2000\"}");
    assertEquals(200, updated.statusCode(), updated.body());
    assertTrue(
        json.readTree(send("POST", "/vsum/constraints/validate", "").body())
            .get("satisfied")
            .asBoolean());

    assertEquals(204, send("DELETE", path, null).statusCode());
    assertEquals(
        0, json.readTree(send("POST", "/vsum/constraints/validate", "").body()).get("results").size());
  }

  @Test
  @DisplayName("invalid VitruvOCL is rejected over REST with the position of the error")
  void invalidConstraintIsRejected() throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/vsum/constraints",
            "{\"language\": \"vitruvocl\", \"source\": \"context shop::Item inv w: self.weight > 0\"}");

    assertEquals(400, response.statusCode());
    JsonNode diagnostic = json.readTree(response.body()).get("diagnostics").get(0);
    assertEquals(1, diagnostic.get("line").asInt());
    assertEquals("context shop::Item inv w: self.".length(), diagnostic.get("column").asInt());
    assertEquals(0, json.readTree(send("GET", "/vsum/constraints", null).body()).size());
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.BodyPublisher publisher =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void propagateItems(EObject... items) {
    ResourceSet resourceSet = new ResourceSetImpl();
    VitruviusChange<Uuid> change;
    try (ChangeRecorder recorder = new ChangeRecorder(resourceSet)) {
      recorder.addToRecording(resourceSet);
      recorder.beginRecording();
      Resource resource =
          resourceSet.createResource(
              org.eclipse.emf.common.util.URI.createFileURI(
                  folder.resolve("items." + FILE_EXTENSION).toString()));
      resource.getContents().addAll(List.of(items));
      change =
          VitruviusChangeResolverFactory.forUuids(UuidResolver.create(resourceSet))
              .assignIds(recorder.endRecording());
    }
    vsum.propagateChange(change);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
