package gov.mirror.app;

import graphql.ExecutionInput;
import org.openfoundry.foundation.api.*;
import org.openfoundry.foundation.spi.QueryOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Read-only diagnostics. Mutations are exposed only through Mirror business commands. */
@RestController
public final class FoundryApiController {
    private final FoundryRuntime runtime;
    private final MirrorAccounts accounts;
    private final ObjectRelationsService relations;
    private final ObjectPresentationService presentations;
    private final RestApiRouter rest;
    private final graphql.GraphQL graph;

    public FoundryApiController(FoundryRuntime runtime, MirrorAccounts accounts, ObjectRelationsService relations,
                                ObjectPresentationService presentations) {
        this.runtime = runtime;
        this.accounts = accounts;
        this.relations = relations;
        this.presentations = presentations;
        rest = new RestApiRouter(runtime.application());
        graph = GraphqlApiRuntime.create(runtime.pack().ontology().schema(), runtime.application(), Map.of());
    }

    @GetMapping("/api/model")
    public Map<String, Object> model() {
        accounts.requireAdmin();
        return Map.of("schema", runtime.pack().ontology().schema(), "actions", runtime.pack().actions(), "readOnly", true);
    }

    @GetMapping("/api/events")
    public Map<String, Object> events() {
        accounts.requireAdmin();
        return runtime.events(accounts.context());
    }

    @GetMapping("/api/objects/{type}/{id}/relationships")
    public ObjectRelationsService.Page relationships(@PathVariable String type, @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean includeEnded,
            @RequestParam(defaultValue = "") String relationshipType,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) {
        return relations.list(type, id, includeEnded, relationshipType, offset, limit);
    }

    @GetMapping("/api/objects/{type}/presentations")
    public Object presentedObjects(@PathVariable String type, @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "20") int limit) {
        return presentations.list(type, offset, limit);
    }

    @GetMapping("/api/objects/{type}/{id}/presentation")
    public Object presentedObject(@PathVariable String type, @PathVariable String id) {
        return presentations.get(type, id);
    }

    @GetMapping("/api/objects/{type}/{id}/relationships/{relationshipType}/{relationshipId}/history")
    public Object relationshipHistory(@PathVariable String type, @PathVariable String id,
            @PathVariable String relationshipType, @PathVariable String relationshipId) {
        return relations.history(type, id, relationshipType, relationshipId);
    }

    @GetMapping({"/api/v1/{type}", "/api/v1/{type}/{id}", "/api/v1/{type}/{id}/history",
            "/api/v1/{type}/{id}/links/{field}"})
    public ResponseEntity<?> read(jakarta.servlet.http.HttpServletRequest request,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "100") int limit) {
        accounts.requireAdmin();
        var response = rest.get(accounts.context(), accounts.principal(), request.getRequestURI(),
                new QueryOptions(Math.min(limit, 200), offset, null, null, false));
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @PostMapping("/api/v1/actions/{action}")
    public void action() {
        throw new SecurityException("Use an authorized Mirror business command");
    }

    @PostMapping("/graphql")
    public Map<String, Object> graphql(@RequestBody Map<String, Object> body) {
        accounts.requireAdmin();
        if (!(body.get("query") instanceof String query) || query.isBlank()) {
            throw new IllegalArgumentException("GraphQL query is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = body.get("variables") instanceof Map<?, ?> values
                ? (Map<String, Object>) values : Map.of();
        return graph.execute(ExecutionInput.newExecutionInput(query).variables(variables)
                .operationName((String) body.get("operationName"))
                .graphQLContext(Map.of("request", new ApiRequestContext(accounts.context(), accounts.principal())))
                .build()).toSpecification();
    }
}
