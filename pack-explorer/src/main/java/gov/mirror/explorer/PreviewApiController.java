package gov.mirror.explorer;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import org.openfoundry.foundation.api.*;
import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.pack.LoadedDomainPack;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
public final class PreviewApiController {
    private final PreviewRuntime runtime;
    private final RestApiRouter rest;
    private final graphql.GraphQL graph;

    public PreviewApiController(PreviewRuntime runtime) {
        this.runtime = runtime;
        this.rest = new RestApiRouter(runtime.application());
        this.graph = GraphqlApiRuntime.create(runtime.pack().ontology().schema(), runtime.application(), runtime.pack().actions());
    }

    @GetMapping("/api/model")
    public Map<String, Object> model() {
        var schema = runtime.pack().ontology().schema();
        var counts = new LinkedHashMap<String, Integer>();
        for (var type : schema.objectTypes()) counts.put(type.name(), runtime.storage().queryObjects(runtime.context(), type.name(), new QueryOptions(1000, 0, null, null, false)).size());
        return Map.of("name", runtime.pack().manifest().name(), "version", runtime.pack().manifest().version(), "token", runtime.token(),
                "schema", schema, "counts", counts, "actions", runtime.pack().actions(),
                "generatedParameters", Set.of("receiptId", "readKey", "contributionId", "contributionKey", "participationId", "signalId", "personTagId", "pairKey"),
                "demo", Map.of("storage", "isolated-memory", "actor", runtime.context().actorId(), "organization", "org", "receiverPerson", "p", "reset", "restart-process"));
    }

    @GetMapping("/api/events")
    public Map<String, Object> events() { return Map.of("audit", runtime.storage().auditEntries(runtime.context()), "outbox", runtime.storage().outboxEntries(runtime.context())); }

    @GetMapping("/api/v1/{type}")
    public ResponseEntity<?> list(@PathVariable("type") String type) { return response(rest.get(runtime.context(), runtime.principal(), "/api/v1/" + type, QueryOptions.defaults())); }
    @GetMapping("/api/v1/{type}/{id}")
    public ResponseEntity<?> object(@PathVariable("type") String type, @PathVariable("id") String id) { return response(rest.get(runtime.context(), runtime.principal(), "/api/v1/" + type + "/" + id, QueryOptions.defaults())); }
    @GetMapping("/api/v1/{type}/{id}/history")
    public ResponseEntity<?> history(@PathVariable("type") String type, @PathVariable("id") String id) { return response(rest.get(runtime.context(), runtime.principal(), "/api/v1/" + type + "/" + id + "/history", QueryOptions.defaults())); }
    @GetMapping("/api/v1/{type}/{id}/links/{field}")
    public ResponseEntity<?> links(@PathVariable("type") String type, @PathVariable("id") String id, @PathVariable("field") String field) { return response(rest.get(runtime.context(), runtime.principal(), "/api/v1/" + type + "/" + id + "/links/" + field, QueryOptions.defaults())); }

    @PostMapping("/api/v1/actions/{action}")
    public ResponseEntity<?> action(@PathVariable("action") String action, @RequestHeader(value="Idempotency-Key", required=false) String key,
                                    @RequestHeader(value="X-Preview-Token", required=false) String token,
                                    @RequestHeader(value="X-Preview-Role", required=false) String role,
                                    @RequestBody Map<String, Object> input) {
        if (!Objects.equals(token, runtime.token())) return new ResponseEntity<>(Map.of("error", "Preview token required", "code", "DENIED"), HttpStatus.FORBIDDEN);
        if (!"operator".equals(role)) return new ResponseEntity<>(Map.of("error", "Read-only preview cannot execute Actions", "code", "DENIED"), HttpStatus.FORBIDDEN);
        var manifest = runtime.pack().actions().get(action);
        if (manifest == null) return ResponseEntity.notFound().build();
        Map<String,Object> normalized = new LinkedHashMap<>(input);
        // Preview's generated identities are intentionally derived server-side; its fixed demo policy is configured here.
        var definition = runtime.pack().ontology().schema().actionTypes().stream().filter(t -> t.name().equals(action)).findFirst().orElseThrow();
        String identity = org.openfoundry.foundation.spi.LineageValues.hash(true, List.of(runtime.context().actorId(), action, Objects.toString(key, "")));
        for (String field : List.of("contributionId", "contributionKey", "participationId", "signalId")) if (definition.parameters().stream().anyMatch(p -> p.name().equals(field))) normalized.put(field, identity);
        if (definition.name().equals("AssignManualTag")) {
            var person = runtime.storage().getObject(runtime.context(), "Person", String.valueOf(normalized.get("person")));
            var tag = runtime.storage().getObject(runtime.context(), "Tag", String.valueOf(normalized.get("tag")));
            if (person == null || tag == null) throw new IllegalArgumentException("Person and tag are required");
            var pair = org.openfoundry.foundation.spi.LineageValues.hash(true, List.of(person.id(), tag.id()));
            normalized.put("personTagId", "person_tag_" + pair);
            normalized.put("pairKey", pair);
        }
        if (definition.name().equals("RecordFirstRead")) {
            String recipient = String.valueOf(normalized.get("recipient"));
            String version = String.valueOf(normalized.get("version"));
            String readIdentity = org.openfoundry.foundation.spi.LineageValues.hash(true, List.of(recipient, version));
            normalized.put("receiptId", "read_" + readIdentity);
            normalized.put("readKey", readIdentity);
        }
        var actor = new org.openfoundry.foundation.actions.ActionActor(runtime.context().actorId(), Set.of(definition.permission()));
        try {
            var result = runtime.application().execute(manifest, runtime.context(), runtime.principal(), normalized, key);
            return ResponseEntity.ok(result);
        } catch (RuntimeException failure) { return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", Objects.toString(failure.getMessage(), "Action rejected"), "code", "ACTION_REJECTED")); }
    }

    @PostMapping(value="/graphql", consumes="application/json")
    public Map<String, Object> graphql(@RequestBody Map<String, Object> body) {
        String query = Objects.toString(body.get("query"), "");
        if (query.isBlank()) throw new IllegalArgumentException("GraphQL query is required");
        Map<String,Object> variables = new LinkedHashMap<>();
        if (body.get("variables") instanceof Map<?, ?> map) map.forEach((key, value) -> variables.put(String.valueOf(key), value));
        var graphContext = new LinkedHashMap<String, Object>();
        graphContext.put("request", new ApiRequestContext(runtime.context(), runtime.principal()));
        if (body.get("idempotencyKey") != null) graphContext.put("idempotencyKey", body.get("idempotencyKey"));
        ExecutionInput input = ExecutionInput.newExecutionInput(query)
                .variables(variables)
                .graphQLContext(graphContext)
                .build();
        ExecutionResult result = graph.execute(input);
        return result.toSpecification();
    }

    private static ResponseEntity<?> response(ApiResponse response) { return ResponseEntity.status(response.status()).body(response.body()); }
}
