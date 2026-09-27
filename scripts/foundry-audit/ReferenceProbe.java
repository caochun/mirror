import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.api.*;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Read-only audit of source capabilities using disposable in-memory stores; findings are observations, not passing requirements. */
public class ReferenceProbe {
    static final RequestContext CTX = RequestContext.system("audit-a", "audit-user");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("audit-user", "audit-a", Set.of());
    static final String SDL = """
            extend schema @namespace(name: "audit", version: "0.1.0")
            type Item @objectType {
              id: ID! @primary
              name: String!
              serial: String @unique @immutable
              secret: String @sensitive
            }
            """;

    public static void main(String[] args) throws Exception {
        var observations = new LinkedHashMap<String, Object>();
        var schema = new SchemaCompiler().compile(new OdlParser().parse(SDL)).schema();
        var memory = new InMemoryStorageProvider();
        memory.applySchema(CTX, schema);
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:reference_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcStorageProvider(ds, DatabaseDialect.h2());
        jdbc.applySchema(CTX, schema);
        for (var provider : Map.<String, StorageProvider>of("memory", memory, "jdbc_h2", jdbc).entrySet()) {
            var findings = new LinkedHashMap<String, Object>();
            var storage = provider.getValue();
            findings.put("missing_required_name_accepted", attempt(() -> create(storage, "missing", Map.of())));
            findings.put("wrong_scalar_type_accepted", attempt(() -> create(storage, "wrong-type", Map.of("name", 42))));
            create(storage, "unique-one", Map.of("name", "One", "serial", "SAME"));
            findings.put("duplicate_unique_value_accepted", attempt(() -> create(storage, "unique-two", Map.of("name", "Two", "serial", "SAME"))));
            findings.put("immutable_change_accepted", attempt(() -> {
                try (var tx = storage.beginTransaction(CTX)) {
                    tx.updateObject("Item", "unique-one", Map.of("serial", "CHANGED"), 1);
                    tx.commit();
                }
            }));
            create(storage, "history", Map.of("name", "before"));
            Instant beforeUpdate = Instant.now();
            Thread.sleep(15);
            try (var tx = storage.beginTransaction(CTX)) {
                tx.updateObject("Item", "history", Map.of("name", "after"), 1);
                tx.commit();
            }
            findings.put("earlier_valid_time_seen_with_current_recorded_time", storage.getObjectAtTime(CTX, "Item", "history", beforeUpdate, Instant.now()).state().get("name"));
            findings.put("asof_list_name", storage.queryObjects(CTX, "Item", new QueryOptions(100, 0, beforeUpdate, beforeUpdate, false))
                    .stream().filter(item -> item.id().equals("history")).findFirst().orElseThrow().properties().get("name"));
            observations.put(provider.getKey(), findings);
        }
        var deny = new ApplicationService(memory, new AuthorizationService((p, relation, key) -> false), new ActionExecutor());
        var create = new ActionManifest("CreateItem", 1, false, List.of(),
                List.of(new ActionManifest.CreateObject("Item", "created-by-denied", Map.of("name", "Generated"))));
        boolean deniedWrite;
        try { deniedWrite = deny.execute(create, CTX, PRINCIPAL, Map.of(), null).success(); }
        catch (SecurityException deniedAction) { deniedWrite = false; }
        observations.put("application_action_succeeds_with_deny_all_authorizer", deniedWrite);
        create(memory, "read", Map.of("name", "Readable", "secret", "synthetic-secret"));
        var app = new ApplicationService(memory, new AuthorizationService((p, relation, key) -> true), new ActionExecutor(), schema, Map.of(), Map.of());
        observations.put("application_read_returns_sensitive_value", app.getObject(CTX, PRINCIPAL, "Item", "read").properties().containsKey("secret"));
        var graphql = GraphqlApiRuntime.create(schema, app);
        var gql = graphql.execute(ExecutionInput.newExecutionInput("{ item(id: \"read\") { id name } }")
                .graphQLContext(Map.of("request", new ApiRequestContext(CTX, PRINCIPAL))).build());
        observations.put("graphql_declared_property_errors", gql.getErrors().stream().map(e -> e.getMessage()).toList());
        observations.put("graphql_declared_property_data", gql.getData());
        var idempotent = new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore(), (context, actor, type, parameters) -> true);
        var noop = new ActionManifest("Noop", 1, false, List.of(), List.of());
        var actor = new ActionActor("audit-user", Set.of());
        var noopType = new ActionTypeDefinition("Noop", List.of(), "can_noop");
        var first = idempotent.execute(noop, noopType, CTX, actor, Map.of(), "same-key", memory);
        var second = idempotent.execute(noop, noopType, RequestContext.system("audit-b", "another-user"),
                new ActionActor("another-user", Set.of()), Map.of(), "same-key", memory);
        observations.put("unscoped_idempotency_returns_other_tenant_result", first.actionId().equals(second.actionId()));
        var changedType = new OdlParser().parse(SDL.replace("name: String!", "name: Int!"));
        observations.put("string_to_int_migration_class", new SchemaDiffer().diff(schema, changedType).classification().name());
        try {
            new DomainPackLoader().load(Path.of(args[0]).resolve("examples/library-pack"));
            observations.put("upstream_library_load", "accepted");
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            observations.put("upstream_library_load", cause.getMessage());
        }
        String bookSource = Files.readString(Path.of(args[0]).resolve("examples/library-pack/schema/book.odl"));
        var bookSchema = new OdlParser().parse(bookSource);
        observations.put("upstream_book_fields_retained", bookSchema.objectTypes().getFirst().properties().stream().map(PropertyDefinition::name).toList());
        try {
            var borrowed = new ActionManifestParser().parse(Files.readString(Path.of(args[0]).resolve("examples/library-pack/actions/borrow-book.yaml")));
            observations.put("upstream_borrow_manifest_accepted_with_sideeffects_unrepresented", borrowed.action().equals("BorrowBook"));
        } catch (ActionParseException unsupported) {
            observations.put("upstream_borrow_manifest_accepted_with_sideeffects_unrepresented", false);
            observations.put("upstream_borrow_manifest_rejection", unsupported.getMessage());
        }
        observations.put("java_schema_components", Arrays.stream(OntologySchema.class.getRecordComponents()).map(c -> c.getName()).toList());
        observations.put("java_action_metadata_components", Arrays.stream(ActionTypeDefinition.class.getRecordComponents()).map(c -> c.getName()).toList());
        observations.put("java_manifest_components", Arrays.stream(ActionManifest.class.getRecordComponents()).map(c -> c.getName()).toList());
        var inherited = new OdlParser().parse("""
                extend schema @namespace(name: "audit", version: "0.1.0")
                interface Base { id: ID! @primary }
                type Child implements Base @objectType { name: String! }
                """);
        observations.put("interface_primary_inheritance_errors", new SchemaCompiler().validate(inherited));
        String encoded = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(observations);
        if (args.length > 1) Files.writeString(Path.of(args[1]), encoded + "\n");
        System.out.println(encoded);
    }

    static void create(StorageProvider storage, String id, Map<String, Object> values) {
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Item", id, values);
            tx.commit();
        }
    }

    static boolean attempt(Runnable work) {
        try { work.run(); return true; }
        catch (RuntimeException rejected) { return false; }
    }
}
