package gov.mirror.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MirrorApplicationTest {
    @TempDir Path directory;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "test-only-strong-password";
    static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    private ConfigurableApplicationContext start() {
        return start(false);
    }

    private ConfigurableApplicationContext start(boolean revokeEditor) {
        return start(revokeEditor, false);
    }

    private ConfigurableApplicationContext start(boolean revokeEditor, boolean seedReference) {
        var args = new ArrayList<>(List.of("--server.port=0", "--logging.level.root=WARN",
                "--mirror.foundry.seed-demo=true",
                "--mirror.foundry.seed-reference=" + seedReference,
                "--mirror.foundry.jdbc-url=jdbc:h2:file:" + directory.resolve("foundry")));
        String[] names = {"admin", "editor", "reader", "other", "directory", "tags"};
        String[] roles = {"ADMIN", "DIRECTORY,TAG_EDITOR", "READER", "DIRECTORY,TAG_EDITOR", "DIRECTORY", "TAG_EDITOR"};
        for (int i = 0; i < names.length; i++) {
            String prefix = "--mirror.security.users[" + i + "].";
            args.add(prefix + "username=" + names[i]);
            args.add(prefix + "password-hash=" + HASH);
            args.add(prefix + "roles=" + (revokeEditor && i == 1 ? "READER" : roles[i]));
            args.add(prefix + "organization=" + (i == 3 ? "org2" : "org"));
            args.add(prefix + "organizations=" + (i == 3 ? "org2" : "org"));
        }
        return new SpringApplicationBuilder(MirrorApplication.class).run(args.toArray(String[]::new));
    }

    @Test
    void objectDescriptionsExplainAppointmentsMembershipAndTagMeaning() throws Exception {
        try (var context = start(false, true)) {
            var admin = new Browser(context, "admin");
            String path = "/api/objects/Appointment/ref-person-p001-appointment/presentation";
            var appointment = admin.get(path, 200).path("presentation");
            assertTrue(appointment.path("title").asText().contains("演示人员001的任职"));
            assertTrue(appointment.path("title").asText().contains("综合管理"));
            assertEquals("演示人员001在党政办公室担任“综合管理”岗位，目前在任。", appointment.path("summary").asText());
            assertEquals("ref-person-p001", appointment.path("facts").get(0).path("target").path("id").asText());
            var sidebar = admin.get("/api/objects/Appointment/presentations?limit=20", 200);
            assertEquals(15, sidebar.size());
            assertEquals(15, java.util.stream.StreamSupport.stream(sidebar.spliterator(), false)
                    .map(row -> row.path("presentation").path("title").asText()).distinct().count());
            var membership = admin.get("/api/objects/ObjectMembership/ref-person-p001-membership/presentation", 200);
            assertEquals("演示人员001的管理状态", membership.path("presentation").path("title").asText());
            assertTrue(membership.path("presentation").path("summary").asText().contains("正常管理"));
            var relations = admin.get("/api/objects/Person/ref-person-p001/relationships", 200);
            for (var relation : relations.path("items")) {
                if (relation.path("type").asText().equals("PersonTagPerson")) {
                    assertTrue(relation.path("targetPresentation").path("title").asText().contains("药品耗材与设备采购及管理"));
                    assertTrue(relation.path("targetPresentation").path("summary").asText().contains("当前生效"));
                }
            }
            var cancelled = admin.get("/api/objects/Person/ref-person-p003/relationships?relationshipType=PersonTagPerson", 200);
            assertTrue(cancelled.path("items").get(0).path("targetPresentation").path("summary").asText().contains("已人工取消"));
            var storage = context.getBean(FoundryRuntime.class).storage();
            try (var tx = storage.beginTransaction(RequestContext.system(MirrorAccounts.TENANT, "fixture"))) {
                var jobLink = tx.findLinks("AppointmentPosition", new EntityKey("Appointment", "ref-person-p001-appointment"), null).getFirst();
                tx.deleteLink(jobLink.type(), jobLink.id(), jobLink.version());
                tx.commit();
            }
            assertTrue(admin.get(path, 200).path("presentation").path("summary").asText().contains("岗位待确认"));
            new Browser(context, "reader").get(path, 403);
            new Browser(context, "reader").get("/api/objects/Appointment/presentations", 403);
            new Browser(context, null).get(path, 401);
        }
    }

    @Test
    void relationshipBrowserIncludesInboundLinksEndedEdgesHistoryAndScopedPagination() throws Exception {
        try (var context = start(false, true)) {
            var admin = new Browser(context, "admin");
            String path = "/api/objects/Person/ref-person-p001/relationships";
            var all = admin.get(path, 200);
            assertEquals(5, all.path("total").asInt());
            var types = new HashSet<String>();
            JsonNode oldOrganization = null;
            for (var link : all.path("items")) {
                types.add(link.path("type").asText());
                if (link.path("type").asText().equals("AppointmentPerson")) {
                    assertEquals("INBOUND", link.path("direction").asText());
                    assertEquals("Appointment", link.path("target").path("type").asText());
                }
                if (link.path("type").asText().equals("PersonCurrentOrganization")) oldOrganization = link;
            }
            assertEquals(Set.of("AppointmentPerson", "MembershipPerson", "PersonCurrentOrganization", "PersonTagPerson", "IdentityPerson"), types);
            assertFalse(all.toString().contains("synthetic:ref-person-p001"));
            assertNotNull(oldOrganization);
            var pagedIds = new HashSet<String>();
            for (int offset : List.of(0, 2, 4)) {
                var page = admin.get(path + "?offset=" + offset + "&limit=2", 200);
                assertEquals(5, page.path("total").asInt());
                for (var link : page.path("items")) assertTrue(pagedIds.add(link.path("id").asText()));
            }
            assertEquals(5, pagedIds.size());
            admin.get(path + "?relationshipType=RecipientTask", 400);
            new Browser(context, "reader").get(path, 403);
            new Browser(context, null).get(path, 401);
            var storage = context.getBean(FoundryRuntime.class).storage();
            String oldId = oldOrganization.path("id").asText();
            try (var tx = storage.beginTransaction(RequestContext.system(MirrorAccounts.TENANT, "relationship-test"))) {
                tx.acquireWrite();
                tx.deleteLink("PersonCurrentOrganization", oldId, 1);
                tx.createLink("PersonCurrentOrganization", "test-transfer", new EntityKey("Person", "ref-person-p001"), new EntityKey("Organization", "org2"), Map.of());
                tx.commit();
            }
            assertEquals(5, admin.get(path, 200).path("total").asInt());
            var ended = admin.get(path + "?includeEnded=true&relationshipType=PersonCurrentOrganization", 200);
            assertEquals(2, ended.path("total").asInt());
            assertTrue(java.util.stream.StreamSupport.stream(ended.path("items").spliterator(), false)
                    .anyMatch(link -> link.path("id").asText().equals(oldId) && link.path("ended").asBoolean() && !link.path("validTo").isNull()));
            String historyPath = path + "/PersonCurrentOrganization/" + oldId + "/history";
            var history = admin.get(historyPath, 200);
            assertEquals(2, history.size());
            assertTrue(history.toString().contains("DELETED"));
            assertTrue(history.toString().contains("relationship-test"));
            admin.get(historyPath.replace("ref-person-p001/relationships", "ref-person-p002/relationships"), 404);
            new Browser(context, "reader").get(historyPath, 403);
            var reverse = admin.get("/api/objects/Organization/org2/relationships?relationshipType=PersonCurrentOrganization", 200);
            assertEquals("INBOUND", reverse.path("items").get(0).path("direction").asText());
            assertEquals("ref-person-p001", reverse.path("items").get(0).path("target").path("id").asText());
        }
    }

    @Test
    void referenceFixturesAreVisibleWithIndependentAdmissionAndLabelStates() throws Exception {
        try (var context = start(false, true)) {
            var admin = new Browser(context, "admin");
            assertEquals(15, admin.get("/api/mirror/people", 200).path("total").asInt());
            assertEquals(3, admin.get("/api/mirror/people?status=PENDING", 200).path("total").asInt());
            assertTrue(admin.get("/api/mirror/people/ref-person-p001", 200).path("tags").get(0).path("effective").asBoolean());
            for (String id : List.of("p002", "p003", "p005", "p008", "p015")) {
                assertFalse(admin.get("/api/mirror/people/ref-person-" + id, 200).path("tags").get(0).path("effective").asBoolean());
            }
            assertTrue(admin.get("/api/mirror/people/ref-person-p007", 200).path("tags").get(0).path("effective").asBoolean());
            assertEquals(0, new Browser(context, "reader").get("/api/mirror/people", 200).path("total").asInt());
            assertEquals(2, admin.get("/api/events", 200).path("audit").size());
            assertEquals(0, admin.get("/api/events", 200).path("outbox").size());
        }
    }

    @Test
    void revokedLocalAccountCannotReplayAfterConfigurationReload() throws Exception {
        var input = Map.<String, Object>of("name", "Revocation", "organization", "org", "note", "manual");
        try (var context = start()) {
            new Browser(context, "editor").command("RegisterManualPerson", input, "original", 200);
        }
        try (var context = start(true)) {
            new Browser(context, "editor").command("RegisterManualPerson", input, "original", 403);
            assertEquals(1, new Browser(context, "admin").get("/api/events", 200).path("outbox").size());
        }
    }

    @Test
    void businessLoopPersistsHistoryReceiptsAuditAndOutboxAcrossRestart() throws Exception {
        String person;
        Map<String, Object> registration = Map.of("name", "Test Person", "organization", "org", "note", "verified manual source");
        JsonNode original;
        int auditCount;
        int outboxCount;
        try (var context = start()) {
            var admin = new Browser(context, "admin");
            original = admin.command("RegisterManualPerson", registration, "register", 200);
            person = affected(original, "Person");
            var detail = admin.get("/api/mirror/people/" + person, 200);
            assertEquals("PENDING", detail.path("status").asText());
            var assign = assignment(detail);
            admin.command("AssignManualTag", assign, "early-tag", 409);
            admin.command("DecideObjectMembership", decision(detail, "IN_SCOPE"), "approve", 200);
            detail = admin.get("/api/mirror/people/" + person, 200);
            assign = assignment(detail);
            var tagged = admin.command("AssignManualTag", assign, "tag", 200);
            assertEquals(tagged, admin.command("AssignManualTag", assign, "tag", 200));
            detail = admin.get("/api/mirror/people/" + person, 200);
            assertTrue(detail.path("tags").get(0).path("effective").asBoolean());
            var pt = detail.path("tags").get(0);
            var suppress = Map.<String, Object>of("personTag", pt.path("id").asText(), "expectedVersion", pt.path("version").asInt(), "note", "manual suppression");
            admin.command("SuppressPersonTag", suppress, "suppress", 200);
            assertEquals(409, admin.raw("/api/mirror/commands/SuppressPersonTag", "POST", suppress, "different-suppress").statusCode());
            detail = admin.get("/api/mirror/people/" + person, 200);
            assertFalse(detail.path("tags").get(0).path("effective").asBoolean());
            pt = detail.path("tags").get(0);
            admin.command("ApplyManualTagContribution", Map.of("personTag", pt.path("id").asText(),
                    "expectedVersion", pt.path("version").asInt(), "tagVersion", "tag-v1", "note", "explicit restore"), "restore", 200);
            detail = admin.get("/api/mirror/people/" + person, 200);
            assertTrue(detail.path("tags").get(0).path("effective").asBoolean());
            assertEquals(2, detail.path("tags").get(0).path("contributions").size());
            admin.command("DecideObjectMembership", decision(detail, "SUSPENDED"), "suspend", 200);
            detail = admin.get("/api/mirror/people/" + person, 200);
            assertFalse(detail.path("tags").get(0).path("effective").asBoolean());
            assertTrue(detail.path("history").size() > 10);
            assertTrue(detail.path("history").toString().contains("admin"));
            var events = admin.get("/api/events", 200);
            auditCount = events.path("audit").size();
            outboxCount = events.path("outbox").size();
            assertEquals(7, auditCount); // explicit synthetic bootstrap plus six business commands
            assertEquals(6, outboxCount);
        }
        try (var context = start()) {
            var admin = new Browser(context, "admin");
            assertEquals(original, admin.command("RegisterManualPerson", registration, "register", 200));
            var detail = admin.get("/api/mirror/people/" + person, 200);
            assertEquals("SUSPENDED", detail.path("status").asText());
            assertEquals(2, detail.path("tags").get(0).path("contributions").size());
            assertTrue(detail.path("history").size() > 10);
            var events = admin.get("/api/events", 200);
            assertEquals(auditCount, events.path("audit").size());
            assertEquals(outboxCount, events.path("outbox").size());
        }
    }

    @Test
    void authorizationCoversCsrfRolesScopeGenericApisAndIdempotentReplay() throws Exception {
        try (var context = start()) {
            var anonymous = new Browser(context, null);
            anonymous.get("/api/mirror/people", 401);
            var admin = new Browser(context, "admin");
            var editor = new Browser(context, "editor");
            var reader = new Browser(context, "reader");
            var other = new Browser(context, "other");
            var directoryUser = new Browser(context, "directory");
            var tagUser = new Browser(context, "tags");
            var request = Map.<String, Object>of("name", "Scoped Person", "organization", "org", "note", "manual");
            reader.command("RegisterManualPerson", request, "r", 403);
            tagUser.command("RegisterManualPerson", request, "t", 403);
            other.command("RegisterManualPerson", request, "o", 403);
            String token = editor.csrf;
            editor.csrf = "invalid";
            editor.command("RegisterManualPerson", request, "csrf", 403);
            editor.csrf = token;
            var created = editor.command("RegisterManualPerson", request, "same", 200);
            assertEquals(created, editor.command("RegisterManualPerson", request, "same", 200));
            editor.command("RegisterManualPerson", Map.of("name", "Changed", "organization", "org", "note", "manual"), "same", 400);
            String person = affected(created, "Person");
            var detail = editor.get("/api/mirror/people/" + person, 200);
            reader.get("/api/mirror/people/" + person, 200);
            other.get("/api/mirror/people/" + person, 403);
            assertEquals(0, other.get("/api/mirror/people", 200).path("total").asInt());
            assertEquals(1, reader.get("/api/mirror/people?offset=1&limit=1", 200).path("total").asInt());
            assertEquals(0, reader.get("/api/mirror/people?offset=1&limit=1", 200).path("items").size());
            editor.command("DecideObjectMembership", decision(detail, "IN_SCOPE"), "approve", 403);
            admin.command("DecideObjectMembership", decision(detail, "IN_SCOPE"), "approve", 200);
            detail = admin.get("/api/mirror/people/" + person, 200);
            directoryUser.command("AssignManualTag", assignment(detail), "tag", 403);
            tagUser.command("AssignManualTag", assignment(detail), "tag", 200);
            editor.get("/api/v1/Person/" + person, 403);
            editor.get("/api/v1/Person/" + person + "/history", 403);
            editor.get("/api/events", 403);
            editor.get("/api/model", 403);
            assertEquals(403, editor.raw("/graphql", "POST", Map.of("query", "{ person(id: \"" + person + "\") { id } }"), null).statusCode());
            assertEquals(403, admin.raw("/api/v1/actions/RegisterManualPerson", "POST", request, "bypass").statusCode());
            var gql = admin.raw("/graphql", "POST", Map.of("query", "{ person(id: \"" + person + "\") { id name } }"), null);
            assertEquals(200, gql.statusCode());
            assertEquals(person, JSON.readTree(gql.body()).path("data").path("person").path("id").asText());
            var mutation = admin.raw("/graphql", "POST", Map.of("query", "mutation { registerManualPerson(organization: \"org\", name: \"Bypass\") { success } }"), null);
            assertTrue(JSON.readTree(mutation.body()).has("errors"));
            var storage = context.getBean(FoundryRuntime.class).storage();
            var ctx = RequestContext.system(MirrorAccounts.TENANT, "fixture-source");
            try (var tx = storage.beginTransaction(ctx)) {
                tx.acquireWrite();
                var record = tx.getObject("Person", person);
                tx.updateObject("Person", person, Map.of("nationalIdRef", "SECRET-NATIONAL-ID", "phoneRef", "SECRET-PHONE"), record.version());
                tx.commit();
            }
            var redacted = reader.get("/api/mirror/people/" + person, 200).toString();
            assertFalse(redacted.contains("SECRET-NATIONAL-ID"));
            assertFalse(redacted.contains("SECRET-PHONE"));
            try (var tx = storage.beginTransaction(ctx)) {
                tx.acquireWrite();
                var link = tx.findLinks("PersonCurrentOrganization", new EntityKey("Person", person), null).getFirst();
                tx.deleteLink(link.type(), link.id(), link.version());
                tx.createLink(link.type(), "moved", link.from(), new EntityKey("Organization", "org2"), Map.of());
                tx.commit();
            }
            editor.command("RegisterManualPerson", request, "same", 403);
            editor.get("/api/mirror/people/" + person, 403);
            assertEquals(1, other.get("/api/mirror/people", 200).path("total").asInt());
        }
    }

    @Test
    void staleVersionsInactiveOrganizationAndForgedContextDoNotPartiallyCommit() throws Exception {
        try (var context = start()) {
            var admin = new Browser(context, "admin");
            admin.command("AssignManualTag", Map.of("note", "missing references"), "invalid", 400);
            String person = affected(admin.command("RegisterManualPerson", Map.of("name", "Guarded", "organization", "org", "note", "manual"), "create", 200), "Person");
            var detail = admin.get("/api/mirror/people/" + person, 200);
            var oldDecision = decision(detail, "IN_SCOPE");
            admin.command("DecideObjectMembership", oldDecision, "approve", 200);
            admin.command("DecideObjectMembership", oldDecision, "stale", 409);
            detail = admin.get("/api/mirror/people/" + person, 200);
            var forged = new LinkedHashMap<>(assignment(detail));
            forged.put("sourceOrganization", "org2");
            admin.command("AssignManualTag", forged, "forged", 400);
            admin.command("AssignManualTag", assignment(detail), "assign", 200);
            detail = admin.get("/api/mirror/people/" + person, 200);
            var before = admin.get("/api/events", 200);
            var storage = context.getBean(FoundryRuntime.class).storage();
            try (var tx = storage.beginTransaction(RequestContext.system(MirrorAccounts.TENANT, "fixture-source"))) {
                tx.acquireWrite();
                var org = tx.getObject("Organization", "org");
                tx.updateObject("Organization", "org", Map.of("sourceStatus", "INACTIVE"), org.version());
                tx.commit();
            }
            var pt = detail.path("tags").get(0);
            admin.command("ApplyManualTagContribution", Map.of("personTag", pt.path("id").asText(), "expectedVersion", pt.path("version").asInt(),
                    "tagVersion", "tag-v1", "note", "not permitted"), "inactive", 403);
            assertEquals(before, admin.get("/api/events", 200));
            assertEquals(1, admin.get("/api/mirror/people/" + person, 200).path("tags").get(0).path("contributions").size());
        }
    }

    static Map<String, Object> assignment(JsonNode detail) {
        return Map.of("person", detail.path("id").asText(), "expectedPersonVersion", detail.path("version").asInt(),
                "membership", detail.path("membership").path("id").asText(), "expectedMembershipVersion", detail.path("membership").path("version").asInt(),
                "tag", "tag", "expectedTagVersion", 1, "tagVersion", "tag-v1", "note", "verified manual evidence");
    }

    @Test
    void concurrentFirstAssignmentsCommitOnlyOneCompleteContribution() throws Exception {
        try (var context = start()) {
            var admin = new Browser(context, "admin");
            String person = affected(admin.command("RegisterManualPerson", Map.of("name", "Concurrent", "organization", "org", "note", "manual"), "create", 200), "Person");
            admin.command("DecideObjectMembership", decision(admin.get("/api/mirror/people/" + person, 200), "IN_SCOPE"), "approve", 200);
            var input = assignment(admin.get("/api/mirror/people/" + person, 200));
            var second = new Browser(context, "admin");
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> admin.raw("/api/mirror/commands/AssignManualTag", "POST", input, "first").statusCode());
                var other = workers.submit(() -> second.raw("/api/mirror/commands/AssignManualTag", "POST", input, "second").statusCode());
                assertEquals(List.of(200, 409), java.util.stream.Stream.of(first.get(), other.get()).sorted().toList());
            }
            var detail = admin.get("/api/mirror/people/" + person, 200);
            assertEquals(1, detail.path("tags").size());
            assertEquals(1, detail.path("tags").get(0).path("contributions").size());
            assertEquals(3, admin.get("/api/events", 200).path("outbox").size());
        }
    }

    static Map<String, Object> decision(JsonNode detail, String status) {
        return Map.of("membership", detail.path("membership").path("id").asText(),
                "expectedVersion", detail.path("membership").path("version").asInt(),
                "status", status, "decisionCode", "MANUAL_VERIFIED", "note", "verified scope");
    }

    static String affected(JsonNode result, String type) {
        for (var value : result.path("affected")) if (value.path("type").asText().equals(type)) return value.path("id").asText();
        throw new AssertionError("Missing " + type + ": " + result);
    }

    static final class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        final String base;
        String csrf;

        Browser(ConfigurableApplicationContext context, String username) throws Exception {
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            csrf = get("/api/session", 200).path("csrf").asText();
            if (username != null) {
                var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(204, login.statusCode(), login.body());
                csrf = get("/api/session", 200).path("csrf").asText();
            }
        }

        JsonNode get(String path, int status) throws Exception {
            var response = raw(path, "GET", null, null);
            assertEquals(status, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        JsonNode command(String action, Map<String, Object> input, String key, int status) throws Exception {
            var response = raw("/api/mirror/commands/" + action, "POST", input, key);
            assertEquals(status, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        HttpResponse<String> raw(String path, String method, Object body, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path));
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            if (key != null) request.header("Idempotency-Key", key);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
