package gov.objectlibrary.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_content_media;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true",
        "mirror.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ContentMediaWorkflowTest {
    private static final Path MEDIA_ROOT = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StorageProvider storage;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "test");

    @DynamicPropertySource
    static void mediaDirectory(DynamicPropertyRegistry properties) {
        properties.add("mirror.media-directory", MEDIA_ROOT::toString);
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("mirror-media-test-"); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private String key() { return UUID.randomUUID().toString(); }
    private ResultActions request(MockHttpServletRequestBuilder builder, MockHttpSession session, Object body) throws Exception {
        return mvc.perform(builder.session(session).with(csrf()).header("Idempotency-Key", key())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }
    private JsonNode node(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", name, "password", "TestOnlyPassword-123"))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
    }
    private byte[] png(int pixel) throws IOException {
        var image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, pixel);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
    private JsonNode upload(MockHttpSession session, int pixel) throws Exception {
        return node(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "picture.png", "image/png", png(pixel)))
                .session(session).with(csrf()).header("Idempotency-Key", key())));
    }
    private Map<String, Object> draft(String html) {
        return new HashMap<>(Map.of("expectedVersion", 0, "title", "配图提醒", "bodyHtml", html,
                "category", "履责", "readingWindow", "1d", "restoreIds", List.of(), "filter",
                Map.of("organizationIds", List.of(), "tagIds", List.of(), "tagOperator", "ANY",
                        "personIds", List.of("demo-person-001"), "excludedIds", List.of())));
    }
    private Map<String, Object> confirm(JsonNode saved, List<String> tokens) {
        return Map.of("expectedVersion", saved.path("version").asLong(), "selectionDigest", saved.path("selectionDigest").asText(),
                "recipientCount", saved.path("recipientCount").asInt(), "contentDigest", saved.path("contentDigest").asText(),
                "singleRecipientAcknowledged", true, "contentAcknowledged", true, "duplicateAcknowledged", true,
                "confirmedMediaDigests", tokens);
    }

    @Test
    void uploadedImageIsPrivateAndBytesDetermineTheActualFormat() throws Exception {
        var unit = login("unit");
        String key = key();
        byte[] bytes = png(0x123456);
        var file = new MockMultipartFile("file", "untrusted-name.exe", "text/plain", bytes);
        JsonNode asset = node(mvc.perform(multipart("/api/media").file(file).session(unit).with(csrf()).header("Idempotency-Key", key)));
        assertEquals("image/png", asset.path("mimeType").asText());
        String id = asset.path("id").asText();
        assertEquals(asset, node(mvc.perform(multipart("/api/media").file(file).session(unit).with(csrf()).header("Idempotency-Key", key))));
        mvc.perform(get("/api/media/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/media/" + id).session(login("area"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/media/" + id).session(login("reviewer"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/media/" + id).session(unit)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control", "no-store"));
        jdbc.update("UPDATE mirror_accounts SET tenant_id='other-tenant' WHERE username='unit'");
        try {
            mvc.perform(get("/api/media/" + id).session(unit)).andExpect(status().isForbidden());
        } finally {
            jdbc.update("UPDATE mirror_accounts SET tenant_id='mirror' WHERE username='unit'");
        }
        mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "picture.png", "image/png", png(0x654321)))
                .session(unit).with(csrf()).header("Idempotency-Key", key)).andExpect(status().isConflict());
    }

    @Test
    void fakeOversizedExternalAndTooManyImagesAreRejected() throws Exception {
        var unit = login("unit");
        mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "fake.png", "image/png", "<svg/>".getBytes()))
                .session(unit).with(csrf()).header("Idempotency-Key", key())).andExpect(status().isConflict());
        mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "big.png", "image/png", new byte[2 * 1024 * 1024 + 1]))
                .session(unit).with(csrf()).header("Idempotency-Key", key())).andExpect(status().isConflict());
        request(post("/api/reminders"), unit, draft("<img src='https://example.org/photo.png'>")).andExpect(status().isConflict());
        var asset = upload(unit, 123);
        request(post("/api/reminders"), unit, draft(("<img src='/api/media/" + asset.path("id").asText() + "'>").repeat(7)))
                .andExpect(status().isConflict());
    }

    @Test
    void everyImageOccurrenceMustBeConfirmedAndReviewerGetsOnlyTheFrozenTaskMedia() throws Exception {
        var unit = login("unit");
        var reviewer = login("reviewer");
        var asset = upload(unit, 234);
        String mediaId = asset.path("id").asText();
        var saved = node(request(post("/api/reminders"), unit, draft(("<img src='/api/media/" + mediaId + "'>").repeat(2))));
        String id = saved.path("id").asText();
        JsonNode detail = node(mvc.perform(get("/api/reminders/" + id).session(unit)));
        assertEquals(2, detail.path("images").size());
        String first = detail.path("images").get(0).path("confirmationKey").asText();
        String second = detail.path("images").get(1).path("confirmationKey").asText();
        assertNotEquals(first, second);
        request(post("/api/reminders/" + id + "/confirm"), unit, confirm(saved, List.of(first))).andExpect(status().isConflict());
        var confirmed = node(request(post("/api/reminders/" + id + "/confirm"), unit, confirm(saved, List.of(first, second))));
        node(request(post("/api/reminders/" + id + "/submit"), unit, Map.of("expectedVersion", confirmed.path("version").asLong())));
        mvc.perform(get("/api/media/" + mediaId).session(reviewer)).andExpect(status().isOk());
        var version = storage.getObject(CONTEXT, "ReminderTaskVersion", id + "-v1");
        String stored = version.properties().get("bodySnapshot").toString();
        assertTrue(stored.contains("data-media-id"));
        assertFalse(stored.contains("src="), "Stored snapshots must not contain public/render URLs");
        var checks = storage.getLinks(CONTEXT, version.key(), "SafetyCheckForVersion", StorageProvider.Direction.INBOUND, QueryOptions.defaults());
        assertEquals(2, storage.getLinks(CONTEXT, checks.getFirst().from(), "MediaConfirmationForCheck",
                StorageProvider.Direction.INBOUND, QueryOptions.defaults()).size());
    }

    @Test
    void replacingImageInvalidatesPriorConfirmation() throws Exception {
        var unit = login("unit");
        var first = upload(unit, 111);
        var second = upload(unit, 222);
        var payload = draft("<img src='/api/media/" + first.path("id").asText() + "'>");
        var saved = node(request(post("/api/reminders"), unit, payload));
        String id = saved.path("id").asText();
        var detail = node(mvc.perform(get("/api/reminders/" + id).session(unit)));
        var oldTokens = List.of(detail.path("images").get(0).path("confirmationKey").asText());
        var confirmed = node(request(post("/api/reminders/" + id + "/confirm"), unit, confirm(saved, oldTokens)));
        payload.put("expectedVersion", confirmed.path("version").asLong());
        payload.put("bodyHtml", "<img src='/api/media/" + second.path("id").asText() + "'>");
        var edited = node(request(put("/api/reminders/" + id), unit, payload));
        request(post("/api/reminders/" + id + "/confirm"), unit, confirm(edited, oldTokens)).andExpect(status().isConflict());
        request(post("/api/reminders/" + id + "/submit"), unit, Map.of("expectedVersion", edited.path("version").asLong())).andExpect(status().isConflict());
    }

    @Test
    void examplesAreAdminMaintainedAndTaskCopiesSurviveSourceChanges() throws Exception {
        var admin = login("admin");
        var unit = login("unit");
        var asset = upload(admin, 987);
        String html = "<p>原始示例内容</p><img src='/api/media/" + asset.path("id").asText() + "'>";
        var input = new HashMap<String, Object>(Map.of("expectedVersion", 0, "title", "内容示例", "category", "履责",
                "bodyHtml", html, "tagIds", List.of()));
        request(post("/api/content-examples"), unit, input).andExpect(status().isForbidden());
        var example = node(request(post("/api/content-examples"), admin, input));
        String exampleId = example.path("id").asText();
        assertEquals(0, node(mvc.perform(get("/api/content-examples").session(unit))).size());
        var enabled = node(request(post("/api/content-examples/" + exampleId + "/availability"), admin,
                Map.of("expectedVersion", example.path("version").asLong(), "state", "ENABLED")));
        var payload = draft(html.replace("原始示例内容", "本次任务独立修改"));
        payload.put("sourceContentVersionId", example.path("contentVersionId").asText());
        var task = node(request(post("/api/reminders"), unit, payload));
        String taskId = task.path("id").asText();
        input.put("expectedVersion", enabled.path("version").asLong());
        input.put("bodyHtml", "<p>新的示例正文</p>");
        var changed = node(request(put("/api/content-examples/" + exampleId), admin, input));
        node(request(post("/api/content-examples/" + exampleId + "/availability"), admin,
                Map.of("expectedVersion", changed.path("version").asLong(), "state", "DISABLED")));
        var detail = node(mvc.perform(get("/api/reminders/" + taskId).session(unit)));
        assertTrue(detail.path("bodyHtml").asText().contains("本次任务独立修改"));
        assertFalse(detail.path("bodyHtml").asText().contains("新的示例正文"));
        assertTrue(node(mvc.perform(get("/api/content-examples/" + exampleId).session(admin))).path("used").asBoolean(),
                "Usage of an older content version must remain visible after editing the example");
        mvc.perform(get("/api/media/" + asset.path("id").asText()).session(unit)).andExpect(status().isOk());
        request(post("/api/reminders"), unit, payload).andExpect(status().isConflict());
        var link = storage.getLinks(CONTEXT, new EntityKey("ReminderTaskVersion", taskId + "-v1"), "TaskVersionFromContent",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults());
        assertEquals(example.path("contentVersionId").asText(), link.getFirst().to().id());
    }
}
