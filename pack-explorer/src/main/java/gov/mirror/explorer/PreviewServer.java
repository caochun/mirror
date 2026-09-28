package gov.mirror.explorer;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.api.ApplicationService;
import org.openfoundry.foundation.pack.*;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

/** Local-only Pack preview. Deliberately separate from production Mirror and its prior database. */
public final class PreviewServer implements AutoCloseable {
    static final RequestContext CONTEXT = RequestContext.system("pack-preview", "demo-reviewer");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("demo-reviewer", "pack-preview", Set.of());
    private static final Set<String> GENERATED = Set.of("receiptId", "readKey", "contributionId", "contributionKey", "participationId", "signalId",
            "personTagId", "pairKey");
    private final LoadedDomainPack pack;
    private final InMemoryStorageProvider storage = new InMemoryStorageProvider();
    private final ApplicationService reads;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).registerModule(new JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Path assets;
    private final String token = UUID.randomUUID().toString();
    private final Object commandGate = new Object();
    private final Set<String> objectTypes;

    public PreviewServer(Path packDirectory, Path assets, int port) throws IOException {
        this.assets = assets.toAbsolutePath().normalize();
        pack = new DomainPackLoader().load(packDirectory);
        storage.applySchema(CONTEXT, pack.ontology().schema());
        DemoData.populate(storage);
        objectTypes = pack.ontology().schema().objectTypes().stream().map(ObjectTypeDefinition::name).collect(java.util.stream.Collectors.toSet());
        reads = new ApplicationService(storage, new AuthorizationService((actor, relation, key) -> true), new ActionExecutor(),
                pack.ontology().schema(), pack.actions(), Map.of());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!Set.of("127.0.0.1:" + port(), "localhost:" + port()).contains(host)) throw new SecurityException("Local preview host required");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://" + host)) throw new SecurityException("Cross-origin preview access denied");
            if ("cross-site".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"))) throw new SecurityException("Cross-site preview access denied");
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                synchronized (commandGate) { api(exchange, path); }
            } else if (exchange.getRequestMethod().equals("GET")) {
                staticFile(exchange, path);
            } else respond(exchange, 405, Map.of("error", "Method not allowed"));
        } catch (SecurityException denied) {
            respond(exchange, 403, Map.of("error", denied.getMessage(), "code", "DENIED"));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            respond(exchange, 400, Map.of("error", Objects.toString(invalid.getMessage(), "Invalid input"), "code", "INVALID_INPUT"));
        } catch (RuntimeException failure) {
            respond(exchange, 409, Map.of("error", Objects.toString(failure.getMessage(), "State conflict"), "code", "STATE_CONFLICT"));
        } finally { exchange.close(); }
    }

    private void api(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        String[] parts = path.split("/");
        if (method.equals("GET")) {
            switch (path) {
                case "/api/model" -> { respond(exchange, 200, metadata()); return; }
                case "/api/events" -> { respond(exchange, 200, Map.of("audit", storage.auditEntries(CONTEXT), "outbox", storage.outboxEntries(CONTEXT))); return; }
            }
            if (parts.length == 4 && parts[2].equals("objects")) {
                String type = requireType(parts[3]);
                var parameters = query(exchange.getRequestURI().getRawQuery());
                int offset = Integer.parseInt(parameters.getOrDefault("offset", "0"));
                if (offset < 0) throw new IllegalArgumentException("Negative offset");
                var rows = reads.listObjects(CONTEXT, PRINCIPAL, type, new QueryOptions(101, offset, null, null, false));
                respond(exchange, 200, Map.of("items", rows.stream().limit(100).toList(), "offset", offset, "hasMore", rows.size() > 100));
                return;
            }
            if (parts.length == 5 && parts[2].equals("objects")) {
                String type = requireType(parts[3]);
                var object = reads.getObject(CONTEXT, PRINCIPAL, type, parts[4]);
                if (object == null) { respond(exchange, 404, Map.of("error", "Object not found")); return; }
                var relationships = new ArrayList<Map<String, Object>>();
                var endpoint = new EntityKey(type, parts[4]);
                for (var linkType : pack.ontology().schema().linkTypes()) {
                    for (var direction : StorageProvider.Direction.values()) {
                        String expected = direction == StorageProvider.Direction.OUTBOUND ? linkType.fromType() : linkType.toType();
                        if (!expected.equals(type)) continue;
                        for (var link : storage.getLinks(CONTEXT, endpoint, linkType.name(), direction, new QueryOptions(100, 0, null, null, true))) {
                            var target = direction == StorageProvider.Direction.OUTBOUND ? link.to() : link.from();
                            var display = reads.getObject(CONTEXT, PRINCIPAL, target.type(), target.id());
                            relationships.add(Map.of("id", link.id(), "type", link.type(), "direction", direction.name(), "target", target,
                                    "targetName", display == null ? target.id() : label(display), "deleted", link.isDeleted(),
                                    "version", link.version(), "history", reads.history(CONTEXT, PRINCIPAL, new EntityKey(link.type(), link.id()))));
                        }
                    }
                }
                respond(exchange, 200, Map.of("object", object, "relationships", relationships, "history", reads.history(CONTEXT, PRINCIPAL, endpoint)));
                return;
            }
            respond(exchange, 404, Map.of("error", "Unknown preview endpoint"));
            return;
        }
        if (!method.equals("POST") || parts.length != 4 || !parts[2].equals("actions")) {
            respond(exchange, 405, Map.of("error", "Only registered Actions accept writes")); return;
        }
        if (!token.equals(exchange.getRequestHeaders().getFirst("X-Preview-Token"))) throw new SecurityException("Preview token required");
        String mode = exchange.getRequestHeaders().getFirst("X-Preview-Role");
        if (!"operator".equals(mode)) throw new SecurityException("只读预览不能执行 Action，请切换演示操作员");
        String requestKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 200) throw new IllegalArgumentException("Idempotency-Key required");
        var manifest = pack.actions().get(parts[3]);
        if (manifest == null) { respond(exchange, 404, Map.of("error", "Action not registered")); return; }
        byte[] bytes = exchange.getRequestBody().readNBytes(1_000_001);
        if (bytes.length > 1_000_000) throw new IllegalArgumentException("Request too large");
        Map<String, Object> raw = json.readValue(bytes, new TypeReference<>() {});
        if (raw == null) throw new IllegalArgumentException("Object input required");
        var definition = pack.ontology().schema().actionTypes().stream().filter(action -> action.name().equals(manifest.action())).findFirst().orElseThrow();
        Set<String> names = definition.parameters().stream().map(ActionParameter::name).collect(java.util.stream.Collectors.toSet());
        if (!names.containsAll(raw.keySet())) throw new IllegalArgumentException("Unknown Action parameter");
        if (raw.keySet().stream().anyMatch(GENERATED::contains)) throw new IllegalArgumentException("Generated identities cannot be supplied by the browser");
        var parameters = new LinkedHashMap<String, Object>();
        for (var parameter : definition.parameters()) parameters.put(parameter.name(), resolve(parameter.type(), raw.get(parameter.name())));
        String commandIdentity = LineageValues.hash(true, List.of(CONTEXT.actorId(), definition.name(), requestKey));
        for (String field : List.of("contributionId", "contributionKey", "participationId", "signalId")) {
            if (names.contains(field)) parameters.put(field, commandIdentity);
        }
        if (definition.name().equals("AssignManualTag")) {
            ObjectRecord person = (ObjectRecord) parameters.get("person");
            ObjectRecord tag = (ObjectRecord) parameters.get("tag");
            if (person == null || tag == null) throw new IllegalArgumentException("Person and tag are required");
            String pair = LineageValues.hash(true, List.of(person.id(), tag.id()));
            parameters.put("personTagId", "person_tag_" + pair);
            parameters.put("pairKey", pair);
        }
        if (definition.name().equals("RecordFirstRead")) {
            var recipient = (ObjectRecord) parameters.get("recipient");
            var version = (ObjectRecord) parameters.get("version");
            if (recipient == null || version == null) throw new IllegalArgumentException("Recipient and version are required");
            String readIdentity = LineageValues.hash(true, List.of(recipient.id(), version.id()));
            parameters.put("receiptId", "read_" + readIdentity);
            parameters.put("readKey", readIdentity);
        }
        var actor = new ActionActor(CONTEXT.actorId(), Set.of(definition.permission()));
        var executor = new ActionExecutor().withParameterSchema(pack.ontology().schema()).withAuthorization(previewPolicy());
        var result = executor.execute(manifest, definition, CONTEXT, actor, parameters, requestKey, storage);
        respond(exchange, result.success() ? 200 : 422, Map.of("result", result, "idempotencyKey", requestKey));
    }

    private ActionAuthorizer previewPolicy() {
        return new ActionAuthorizer() {
            @Override public boolean allowed(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values) {
                return CONTEXT.equals(context) && actor.id().equals(CONTEXT.actorId()) && actor.roles().contains(definition.permission());
            }
            @Override public boolean allowed(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values, Transaction tx) {
                if (!allowed(context, actor, definition, values)) return false;
                if (definition.name().equals("RecordFirstRead")) {
                    var recipient = (ObjectRecord) values.get("recipient");
                    if (recipient == null) return false;
                    // The preview operator simulates only person p's receiver session; not arbitrary identities.
                    var people = tx.findLinks("RecipientPerson", recipient.key(), key("Person", "p"));
                    var snapshots = tx.findLinks("SnapshotRecipient", null, recipient.key());
                    return people.size() == 1 && snapshots.stream().anyMatch(snapshot -> {
                        var audiences = tx.findLinks("SnapshotAudience", snapshot.from(), null);
                        return audiences.stream().anyMatch(a -> !tx.findLinks("TaskApprovedAudience", key("ReminderTask", "task"), a.to()).isEmpty());
                    });
                }
                if (definition.name().equals("DecideReminderReview")) {
                    var review = (ObjectRecord) values.get("review");
                    return review != null && tx.findLinks("ReviewVersion", review.key(), null).stream().anyMatch(rv ->
                            tx.findLinks("ReminderVersionOf", rv.to(), null).stream().anyMatch(vt ->
                                    !tx.findLinks("TaskCreatedIn", vt.to(), key("Organization", "org")).isEmpty()));
                }
                if (definition.name().equals("RegisterManualPerson") || definition.name().equals("RegisterManualChildOrganization")) {
                    String field = definition.name().equals("RegisterManualPerson") ? "organization" : "parent";
                    ObjectRecord organization = (ObjectRecord) values.get(field);
                    return organization != null && Set.of("org", "org2").contains(organization.id());
                }
                if (definition.name().equals("AssignManualTag")) {
                    ObjectRecord person = (ObjectRecord) values.get("person");
                    ObjectRecord tag = (ObjectRecord) values.get("tag");
                    ObjectRecord membership = (ObjectRecord) values.get("membership");
                    ObjectRecord source = (ObjectRecord) values.get("sourceOrganization");
                    return person != null && person.id().equals("p") && tag != null && tag.id().equals("tag")
                            && membership != null && membership.id().equals("membership") && source != null && source.id().equals("org");
                }
                String organization = definition.name().equals("DecideObjectMembership") ? "decisionOrganization" : "sourceOrganization";
                if (values.get(organization) instanceof ObjectRecord org && !org.id().equals("org")) return false;
                return true;
            }
        };
    }

    private Object resolve(String type, Object value) {
        if (value == null) return null;
        if (type.startsWith("[")) {
            if (!(value instanceof List<?> list)) throw new IllegalArgumentException("List expected");
            String inner = type.substring(1, type.length() - 1).replace("!", "");
            return list.stream().map(item -> resolve(inner, item)).toList();
        }
        if (objectTypes.contains(type)) {
            if (!(value instanceof String id)) throw new IllegalArgumentException("Object parameter must be an ID");
            var object = storage.getObject(CONTEXT, type, id);
            if (object == null || object.isDeleted()) throw new IllegalArgumentException("Referenced object is unavailable");
            return object;
        }
        return PropertyValues.normalize(pack.ontology().schema(), type, value, "parameter");
    }

    private Object metadata() {
        var counts = new LinkedHashMap<String, Integer>();
        for (String type : objectTypes) counts.put(type, storage.queryObjects(CONTEXT, type, new QueryOptions(1000, 0, null, null, false)).size());
        return Map.of("name", pack.manifest().name(), "version", pack.manifest().version(), "schema", pack.ontology().schema(),
                "counts", counts, "actions", pack.actions(), "token", token, "generatedParameters", GENERATED,
                "demo", Map.of("storage", "isolated-memory", "actor", CONTEXT.actorId(), "organization", "org", "receiverPerson", "p", "reset", "restart-process"));
    }

    private String requireType(String type) {
        if (!objectTypes.contains(type)) throw new IllegalArgumentException("Unknown object type");
        return type;
    }
    private static EntityKey key(String type, String id) { return new EntityKey(type, id); }
    private static String label(ObjectRecord record) {
        return Objects.toString(record.properties().getOrDefault("name", record.properties().getOrDefault("title", record.id())));
    }
    private static Map<String, String> query(String raw) {
        var parameters = new LinkedHashMap<String, String>();
        if (raw != null) for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return parameters;
    }

    private void staticFile(HttpExchange exchange, String path) throws IOException {
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        Path file = assets.resolve(relative).normalize();
        if (!file.startsWith(assets) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(assets.toRealPath())) {
            respond(exchange, 404, Map.of("error", "Page not found; build pack-explorer/web first")); return;
        }
        String mime = relative.endsWith(".html") ? "text/html; charset=utf-8" : relative.endsWith(".js") ? "text/javascript; charset=utf-8" : relative.endsWith(".css") ? "text/css; charset=utf-8" : "application/octet-stream";
        send(exchange, 200, mime, Files.readAllBytes(file));
    }
    private void respond(HttpExchange exchange, int status, Object payload) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.writeValueAsBytes(payload));
    }
    private void send(HttpExchange exchange, int status, String mime, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public static void main(String[] args) throws Exception {
        Path pack = args.length > 0 ? Path.of(args[0]) : Path.of("domain-pack");
        Path assets = args.length > 1 ? Path.of(args[1]) : Path.of("pack-explorer/web/dist");
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8090;
        var preview = new PreviewServer(pack, assets, port);
        Runtime.getRuntime().addShutdownHook(new Thread(preview::close));
        preview.start();
        System.out.println("Mirror Pack preview: http://127.0.0.1:" + preview.port() + " (synthetic in-memory data)");
    }
}
