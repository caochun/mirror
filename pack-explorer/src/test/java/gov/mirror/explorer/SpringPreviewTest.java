package gov.mirror.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpringPreviewTest {
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    @Test
    void springServesFoundryRestAndGeneratedGraphqlFromTheSamePackApplication() throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PreviewSpringApplication.class)
                .properties("server.port=0", "mirror.preview.pack-directory=../domain-pack", "mirror.preview.assets-directory=../pack-explorer/web/dist")
                .run()) {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port", "0"));
            assertTrue(port > 0);
            String base = "http://127.0.0.1:" + port;
            var model = request(base + "/api/model");
            JsonNode metadata = json.readTree(model.body());
            assertEquals(14, metadata.path("schema").path("actionTypes").size());
            assertEquals(200, request(base + "/api/v1/Person").statusCode());
            JsonNode person = json.readTree(request(base + "/api/v1/Person/p").body());
            assertEquals("p", person.path("id").asText());
            JsonNode history = json.readTree(request(base + "/api/v1/Person/p/history").body());
            assertTrue(history.isArray());
            var gql = request(base + "/graphql", "{\"query\":\"{ person(id: \\\"p\\\") { id name } }\"}");
            assertEquals(200, gql.statusCode(), gql.body());
            assertEquals("p", json.readTree(gql.body()).path("data").path("person").path("id").asText());
            var action = request(base + "/api/v1/actions/SuppressPersonTag", "{\"personTag\":\"pt\",\"expectedVersion\":1,\"note\":\"spring\"}", Map.of("X-Preview-Token", metadata.path("token").asText(), "X-Preview-Role", "operator", "Idempotency-Key", "spring-action"));
            assertEquals(200, action.statusCode(), action.body());
            var changed = json.readTree(request(base + "/api/v1/PersonTag/pt").body());
            assertEquals("SUPPRESSED", changed.path("properties").path("suppression").asText());
        }
    }

    private HttpResponse<String> request(String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> request(String uri, String body) throws Exception { return request(uri, body, Map.of()); }
    private HttpResponse<String> request(String uri, String body, Map<String, String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(uri)).header("Content-Type", "application/json");headers.forEach(builder::header);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
