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
            var library = new DomainPackLoader().loadBundle(List.of(Path.of(args[0]).resolve("domain-packs/core"), Path.of(args[0]).resolve("examples/library-pack")));
            observations.put("upstream_library_load", "composed schema/actions/assets with core dependency");
            observations.put("upstream_library_assets", Map.of("field_policy_types", library.assets().fieldPolicies().keySet().stream().sorted().toList(),
                    "permission_sources", library.assets().permissions().size(), "seed_files", library.assets().seeds().size()));
            var workflows = new LinkedHashMap<String, Object>();
            workflows.put("memory", libraryWorkflow(library, new InMemoryStorageProvider()));
            var libraryData = new JdbcDataSource();
            libraryData.setURL("jdbc:h2:mem:audit_library;DB_CLOSE_DELAY=-1");
            workflows.put("jdbc_h2", libraryWorkflow(library, new JdbcStorageProvider(libraryData, DatabaseDialect.h2())));
            observations.put("upstream_library_workflow", workflows);
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            observations.put("upstream_library_load", cause.getMessage());
        }
        String bookSource = Files.readString(Path.of(args[0]).resolve("examples/library-pack/schema/book.odl"));
        var bookSchema = new OdlParser().parse(bookSource);
        var bookType = bookSchema.objectTypes().getFirst();
        observations.put("upstream_book_fields_retained", java.util.stream.Stream.concat(
                bookType.properties().stream().map(PropertyDefinition::name),
                bookType.linkFields().stream().map(field -> field.name())).toList());
        observations.put("upstream_book_navigation_fields", bookType.linkFields().stream().map(field -> Map.of(
                "name", field.name(), "target", field.targetType(), "linkType", field.linkType(),
                "direction", field.direction().name(), "history", field.history())).toList());
        try {
            var borrowed = new ActionManifestParser().parse(Files.readString(Path.of(args[0]).resolve("examples/library-pack/actions/borrow-book.yaml")));
            observations.put("upstream_borrow_manifest_accepted_with_sideeffects_unrepresented", borrowed.sideEffects().isEmpty());
            observations.put("upstream_borrow_side_effects", borrowed.sideEffects().stream().map(effect -> Map.of(
                    "name", effect.name(), "type", effect.type(), "attempts", effect.retries(), "failurePolicy", borrowed.onSideEffectFailure().name())).toList());
        } catch (ActionParseException unsupported) {
            observations.put("upstream_borrow_manifest_accepted_with_sideeffects_unrepresented", false);
            observations.put("upstream_borrow_manifest_rejection", unsupported.getMessage());
        }
        var returned = new ActionManifestParser().parse(Files.readString(Path.of(args[0]).resolve("examples/library-pack/actions/return-book.yaml")));
        var deleteLoan = (ActionManifest.DeleteLink) returned.effects().getLast();
        observations.put("upstream_return_manifest", Map.of("action", returned.action(), "failurePolicy", returned.onSideEffectFailure().name(),
                "linkType", deleteLoan.linkType(), "from", deleteLoan.filter().from(), "expect", deleteLoan.expect().name()));
        var recoveryData = new JdbcDataSource();
        recoveryData.setURL("jdbc:h2:mem:audit_event_recovery;DB_CLOSE_DELAY=-1");
        var deliveryRecovery = new LinkedHashMap<String, Object>();
        for (String provider : List.of("memory", "jdbc_h2")) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            org.openfoundry.foundation.events.EventSink callback = event -> {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("injected callback failure");
            };
            org.openfoundry.foundation.events.EventSink sink = provider.equals("memory")
                    ? new org.openfoundry.foundation.events.IdempotentEventSink(callback)
                    : new org.openfoundry.foundation.events.JdbcIdempotentEventSink(recoveryData, callback);
            var event = new org.openfoundry.foundation.events.CloudEvent("1.0", "retry", "audit", "test", "subject",
                    Instant.parse("2030-01-01T00:00:00Z"), "tenant", "tx", Map.of());
            try { sink.publish(event); } catch (IllegalStateException expected) { }
            sink.publish(event);
            int afterRetry = calls.get();
            sink.publish(event);
            deliveryRecovery.put(provider, Map.of("failed_delivery_retried", afterRetry == 2,
                    "completed_duplicate_suppressed", afterRetry == 2 && calls.get() == afterRetry));
        }
        observations.put("consumer_delivery_recovery", deliveryRecovery);
        var ward = new OdlParser().parse(Files.readString(Path.of(args[0]).resolve("domain-packs/nhs-acute/schema/ward.odl")));
        observations.put("upstream_ward_computed_fields", ward.objectTypes().getFirst().computedFields().stream()
                .map(field -> Map.of("name", field.name(), "function", field.function(), "cache", field.cache().name())).toList());
        var computed = new LinkedHashMap<String, Object>();
        computed.put("memory", computedProbe(new InMemoryStorageProvider()));
        var computedData = new JdbcDataSource();
        computedData.setURL("jdbc:h2:mem:audit_computed;DB_CLOSE_DELAY=-1");
        computed.put("jdbc_h2", computedProbe(new JdbcStorageProvider(computedData, DatabaseDialect.h2())));
        observations.put("computed_read_behavior", computed);
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

    static Map<String, Object> libraryWorkflow(org.openfoundry.foundation.pack.LoadedPackBundle library, StorageProvider storage) {
        storage.applySchema(CTX, library.ontology().schema());
        var seeder = new org.openfoundry.foundation.pack.PackSeeder();
        var seeded = seeder.apply(CTX, library, storage);
        var book = seeded.references().get("example.library:book-dune");
        var member = seeded.references().get("example.library:member-ada");
        var events = new ArrayList<org.openfoundry.foundation.events.CloudEvent>();
        var executor = new ActionExecutor().withSideEffects(new StandardSideEffectHandler(events::add));
        var app = ApplicationService.fromBundle(storage, new AuthorizationService((principal, relation, key) -> switch (key.type()) {
            case "Book" -> Set.of("viewer", "editor", "can_borrow", "can_return").contains(relation);
            case "Member" -> Set.of("viewer", "editor").contains(relation);
            default -> false;
        }), executor, library, AuthorizationMode.ONTOLOGY_TARGETS);
        var principal = new SecurityPrincipal(CTX.actorId(), CTX.tenantId(), Set.of("librarian"));
        var borrow = library.actions().get("BorrowBook");
        var parameters = Map.<String, Object>of("book", book.id(), "member", member.id());
        var result = app.execute(borrow, CTX, principal, parameters, "borrow");
        var replay = app.execute(borrow, CTX, principal, parameters, "borrow");
        String borrowedStatus = storage.getObject(CTX, "Book", book.id()).properties().get("status").toString();
        var returned = app.execute(library.actions().get("ReturnBook"), CTX, principal, Map.of("book", book.id()), "return");
        if (seeder.apply(CTX, library, storage).createdObjects() != 0) throw new IllegalStateException("Seed replay created duplicate objects");
        return Map.of("authorization_mode", "ONTOLOGY_TARGETS", "borrow_status", result.status(), "book_after_borrow", borrowedStatus,
                "event_count", events.size(), "event_data", events.getFirst().data(), "replay_same_result", result.equals(replay),
                "return_status", returned.status(), "book_after_return", storage.getObject(CTX, "Book", book.id()).properties().get("status"),
                "active_loans_after_return", storage.getLinks(CTX, book, "BorrowedBy", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
    }

    static Map<String, Object> computedProbe(StorageProvider storage) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "computed", version: "1.0.0")
                type Node @objectType { id: ID! @primary count: Int @computed(fn: "countLinks", args: {type: "Edge"}) }
                type Edge @linkType(from: "Node", to: "Node", cardinality: MANY_TO_MANY) { id: ID! @primary }
                """);
        storage.applySchema(CTX, schema);
        var source = new EntityKey("Node", "b");
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Node", "a", Map.of());
            tx.createObject("Node", "b", Map.of());
            tx.createLink("Edge", "one", new EntityKey("Node", "a"), source, Map.of());
            tx.createLink("Edge", "two", new EntityKey("Node", "a"), source, Map.of());
            tx.commit();
        }
        var visible = new java.util.concurrent.atomic.AtomicBoolean(true);
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> !key.id().equals("a") || visible.get()),
                new ActionExecutor(), schema, Map.of(), Map.of());
        Object initial = app.readComputedField(CTX, PRINCIPAL, source, "count");
        try (var tx = storage.beginTransaction(CTX)) { tx.deleteLink("Edge", "one", 1); tx.commit(); }
        Object after = app.readComputedField(CTX, PRINCIPAL, source, "count");
        visible.set(false);
        return Map.of("initial", initial, "after_delete", after, "hidden_endpoint_count", app.readComputedField(CTX, PRINCIPAL, source, "count"),
                "stored_attribute", storage.getObject(CTX, "Node", "b").properties().containsKey("count"),
                "source_version", storage.getObject(CTX, "Node", "b").version());
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
