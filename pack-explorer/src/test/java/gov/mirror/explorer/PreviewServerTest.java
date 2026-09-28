package gov.mirror.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreviewServerTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newHttpClient();
    PreviewServer open() throws Exception {
        Files.writeString(directory.resolve("index.html"), "<html>Model explorer</html>");
        var server = new PreviewServer(Path.of("..", "domain-pack"), directory, 0);
        server.start();
        return server;
    }
    HttpResponse<String> get(PreviewServer server, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(PreviewServer server, String action, Object params, String token, String role, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/actions/" + action))
                .header("Content-Type", "application/json").header("X-Preview-Token", token).header("X-Preview-Role", role)
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(params))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void modelObjectsRelationsHistoryAndStaticFilesShareOneOrigin() throws Exception {
        try (var server = open()) {
            assertEquals(200, get(server, "/").statusCode());
            JsonNode model = json.readTree(get(server, "/api/model").body());
            assertEquals(37, model.path("schema").path("objectTypes").size());
            assertEquals(14, model.path("schema").path("actionTypes").size());
            assertEquals(79, model.path("schema").path("linkTypes").size());
            var people = json.readTree(get(server, "/api/objects/Person").body());
            assertEquals(2, people.path("items").size());
            var detailResponse = get(server, "/api/objects/Person/p");
            assertEquals(200, detailResponse.statusCode(), detailResponse.body());
            JsonNode detail = json.readTree(detailResponse.body());
            assertTrue(detail.path("relationships").size() >= 4);
            assertEquals(1, detail.path("history").size());
            assertTrue(detail.path("history").get(0).path("recordedAt").isTextual());
            assertFalse(detailResponse.body().contains("PRIVATE-DEMO"));
            assertEquals(400, get(server, "/api/objects/Unknown").statusCode());
            assertEquals(404, get(server, "/api/objects/Person/missing").statusCode());
            assertEquals(404, get(server, "/../pom.xml").statusCode());
        }
    }

    @Test
    void browserWritesRequirePreviewTokenAndOperatorAndCannotUseCrud() throws Exception {
        try (var server = open()) {
            String token = json.readTree(get(server, "/api/model").body()).path("token").asText();
            var values = Map.of("personTag", "pt", "expectedVersion", 1, "note", "Demo suppression");
            assertEquals(403, post(server, "SuppressPersonTag", values, "wrong", "operator", "r").statusCode());
            assertEquals(403, post(server, "SuppressPersonTag", values, token, "reader", "r").statusCode());
            var forged = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/actions/SuppressPersonTag"))
                    .header("Origin", "https://foreign.example").header("X-Preview-Token", token).header("X-Preview-Role", "operator")
                    .header("Idempotency-Key", "r").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(values))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, forged.statusCode());
            assertEquals(0, json.readTree(get(server, "/api/events").body()).path("audit").size());
            var crud = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/objects/Person/p"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, crud.statusCode());
        }
    }

    @Test
    void actualActionCreatesHistoryAndOneAuditEventAcrossReplay() throws Exception {
        try (var server = open()) {
            String token = json.readTree(get(server, "/api/model").body()).path("token").asText();
            var values = Map.of("personTag", "pt", "expectedVersion", 1, "note", "Demo suppression");
            var result = post(server, "SuppressPersonTag", values, token, "operator", "stable-request");
            assertEquals(200, result.statusCode(), result.body());
            var replay = post(server, "SuppressPersonTag", values, token, "operator", "stable-request");
            assertEquals(200, replay.statusCode(), replay.body());
            assertEquals(json.readTree(result.body()).path("result").path("actionId"), json.readTree(replay.body()).path("result").path("actionId"));
            var detail = json.readTree(get(server, "/api/objects/PersonTag/pt").body());
            assertEquals("SUPPRESSED", detail.path("object").path("properties").path("suppression").asText());
            assertEquals(2, detail.path("history").size());
            var events = json.readTree(get(server, "/api/events").body());
            assertEquals(1, events.path("audit").size());
            assertEquals(1, events.path("outbox").size());
            assertEquals(403, post(server, "SuppressPersonTag", values, token, "reader", "stable-request").statusCode());
        }
    }

    @Test
    void parametersAreResolvedFromIdsAndPreconditionsAreVisible() throws Exception {
        try (var server = open()) {
            String token = json.readTree(get(server, "/api/model").body()).path("token").asText();
            assertEquals(400, post(server, "SuppressPersonTag", Map.of("personTag", Map.of("id", "pt"), "expectedVersion", 1), token, "operator", "forged").statusCode());
            var stale = post(server, "SuppressPersonTag", Map.of("personTag", "pt", "expectedVersion", 99), token, "operator", "stale");
            assertEquals(422, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains("Stale person-tag version"));
            assertEquals(0, json.readTree(get(server, "/api/events").body()).path("audit").size());
        }
    }

    @Test
    void sampleReceiverUsesGeneratedIdentityAndCanReadDespiteFailedDelivery() throws Exception {
        try (var server = open()) {
            String token = json.readTree(get(server, "/api/model").body()).path("token").asText();
            var values = Map.of("recipient", "recipient", "version", "published", "expectedVersion", 1, "renderedContentHash", "hash");
            var result = post(server, "RecordFirstRead", values, token, "operator", "read");
            assertEquals(200, result.statusCode(), result.body());
            assertEquals(1, json.readTree(get(server, "/api/objects/ReadReceipt").body()).path("items").size());
            assertEquals(200, post(server, "RecordFirstRead", values, token, "operator", "read").statusCode());
            var forged = new java.util.LinkedHashMap<String, Object>(values);
            forged.put("readKey", "forged");
            assertEquals(400, post(server, "RecordFirstRead", forged, token, "operator", "other").statusCode());
        }
    }
}
