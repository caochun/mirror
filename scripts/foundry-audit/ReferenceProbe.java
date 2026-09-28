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
        observations.put("typed_action_graphql", typedActionProbe());
        var queries = new LinkedHashMap<String, Object>();
        queries.put("memory", governedQueryProbe(new InMemoryStorageProvider()));
        var queryData = new JdbcDataSource();
        queryData.setURL("jdbc:h2:mem:audit_queries;DB_CLOSE_DELAY=-1");
        queries.put("jdbc_h2", governedQueryProbe(new JdbcStorageProvider(queryData, DatabaseDialect.h2())));
        observations.put("governed_object_queries", queries);
        var aggregates = new LinkedHashMap<String, Object>();
        aggregates.put("memory", aggregateProbe(new InMemoryStorageProvider()));
        var aggregateData = new JdbcDataSource();
        aggregateData.setURL("jdbc:h2:mem:audit_aggregates;DB_CLOSE_DELAY=-1");
        aggregates.put("jdbc_h2", aggregateProbe(new JdbcStorageProvider(aggregateData, DatabaseDialect.h2())));
        observations.put("governed_aggregates", aggregates);
        var searches = new LinkedHashMap<String, Object>();
        searches.put("memory", searchProbe(new InMemoryStorageProvider()));
        var searchData = new JdbcDataSource();
        searchData.setURL("jdbc:h2:mem:audit_search;DB_CLOSE_DELAY=-1");
        searches.put("jdbc_h2", searchProbe(new JdbcStorageProvider(searchData, DatabaseDialect.h2())));
        observations.put("governed_search", searches);
        var connections = new LinkedHashMap<String, Object>();
        connections.put("memory", connectionProbe(new InMemoryStorageProvider()));
        var connectionData = new JdbcDataSource();
        connectionData.setURL("jdbc:h2:mem:audit_connections;DB_CLOSE_DELAY=-1");
        connections.put("jdbc_h2", connectionProbe(new JdbcStorageProvider(connectionData, DatabaseDialect.h2())));
        observations.put("connection_pagination", connections);
        observations.put("persistent_schema_registry", registryProbe());
        observations.put("jdbc_schema_activation", activationProbe());
        var reads = new LinkedHashMap<String, Object>();
        reads.put("memory", readBindingProbe(new InMemoryStorageProvider()));
        var readData = new JdbcDataSource();
        readData.setURL("jdbc:h2:mem:audit_read_binding;DB_CLOSE_DELAY=-1");
        reads.put("jdbc_h2", readBindingProbe(new JdbcStorageProvider(readData, DatabaseDialect.h2())));
        observations.put("application_schema_read_binding", reads);
        var sets = new LinkedHashMap<String, Object>();
        sets.put("memory", objectSetProbe(new InMemoryStorageProvider(), new InMemoryObjectSetStore()));
        var setsData = new JdbcDataSource();
        setsData.setURL("jdbc:h2:mem:audit_object_sets;DB_CLOSE_DELAY=-1");
        sets.put("jdbc_h2", objectSetProbe(new JdbcStorageProvider(setsData, DatabaseDialect.h2()), new JdbcObjectSetStore(setsData, DatabaseDialect.h2())));
        observations.put("governed_object_sets", sets);
        var consent = new LinkedHashMap<String, Object>();
        consent.put("memory", consentProbe(new InMemoryStorageProvider(), new InMemoryConsentStore()));
        var consentData = new JdbcDataSource();
        consentData.setURL("jdbc:h2:mem:audit_consent;DB_CLOSE_DELAY=-1");
        consent.put("jdbc_h2", consentProbe(new JdbcStorageProvider(consentData, DatabaseDialect.h2()), new JdbcConsentStore(consentData, DatabaseDialect.h2())));
        observations.put("consent_governance", consent);
        var consentEffects = new LinkedHashMap<String, Object>();
        consentEffects.put("memory", consentEffectProbe(new InMemoryStorageProvider(), new InMemoryConsentStore()));
        var effectsData = new JdbcDataSource();
        effectsData.setURL("jdbc:h2:mem:audit_consent_effects;DB_CLOSE_DELAY=-1");
        consentEffects.put("jdbc_h2", consentEffectProbe(new JdbcStorageProvider(effectsData, DatabaseDialect.h2()), new JdbcConsentStore(effectsData, DatabaseDialect.h2())));
        observations.put("transactional_consent_effects", consentEffects);
        var scalars = new LinkedHashMap<String, Object>();
        scalars.put("memory", customScalarProbe(new InMemoryStorageProvider()));
        var scalarData = new JdbcDataSource();
        scalarData.setURL("jdbc:h2:mem:audit_custom_scalars;DB_CLOSE_DELAY=-1");
        scalars.put("jdbc_h2", customScalarProbe(new JdbcStorageProvider(scalarData, DatabaseDialect.h2())));
        observations.put("custom_scalar_values", scalars);
        var actionPaths = new LinkedHashMap<String, Object>();
        actionPaths.put("memory", actionNavigationProbe(new InMemoryStorageProvider()));
        var pathData = new JdbcDataSource();
        pathData.setURL("jdbc:h2:mem:audit_action_paths;DB_CLOSE_DELAY=-1");
        actionPaths.put("jdbc_h2", actionNavigationProbe(new JdbcStorageProvider(pathData, DatabaseDialect.h2())));
        observations.put("action_relationship_paths", actionPaths);
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

    static Map<String, Object> actionNavigationProbe(StorageProvider storage) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name:"action-path-probe",version:"1.0.0")
                type Person @objectType { id: ID! @primary unit: Unit @link(type:"Assigned",direction:OUTBOUND) }
                type Unit @objectType { id: ID! @primary note: String region: Region @link(type:"Located",direction:OUTBOUND) }
                type Region @objectType { id: ID! @primary name: String! }
                type Assigned @linkType(from:"Person",to:"Unit",cardinality:MANY_TO_ONE) { id: ID! @primary }
                type Located @linkType(from:"Unit",to:"Region",cardinality:MANY_TO_ONE) { id: ID! @primary }
                type Work @actionType(permission:"can_work") { person: Person! @param }
                """);
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Person", "p", Map.of());
            tx.createObject("Unit", "u", Map.of());
            tx.createObject("Region", "r", Map.of("name", "North"));
            tx.createLink("Assigned", "assigned", new EntityKey("Person", "p"), new EntityKey("Unit", "u"), Map.of());
            tx.createLink("Located", "located", new EntityKey("Unit", "u"), new EntityKey("Region", "r"), Map.of());
            tx.commit();
        }
        var action = new ActionManifestParser().parse("""
                action: Work
                version: 1
                preconditions:
                  - expr: "person.unit.region.name == 'North'"
                    error: region not ready
                effects:
                  - type: updateObject
                    target: person.unit
                    set: {note: person.unit.region.name}
                sideEffects:
                  - name: notice
                    type: event
                    config: {type: unit.updated, data: {region: person.unit.region.name}}
                    retries: 1
                    retryDelay: PT0S
                """);
        var visible = new java.util.concurrent.atomic.AtomicBoolean(true);
        var sent = new ArrayList<SideEffectHandler.Invocation>();
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> visible.get() || !key.type().equals("Region")),
                new ActionExecutor().withSideEffects(sent::add), schema, Map.of("Work", action), Map.of());
        var parameters = Map.<String, Object>of("person", "p");
        var first = app.execute(action, CTX, PRINCIPAL, parameters, "path");
        try (var tx = storage.beginTransaction(CTX)) {
            tx.deleteLink("Assigned", "assigned", 1);
            tx.updateObject("Region", "r", Map.of("name", "Later"), 1);
            tx.commit();
        }
        var replay = app.execute(action, CTX, PRINCIPAL, parameters, "path");
        visible.set(false);
        boolean denied = false;
        try { app.execute(action, CTX, PRINCIPAL, parameters, "path"); }
        catch (SecurityException expected) { denied = true; }
        return Map.of("completed", first.success(), "target_note", storage.getObject(CTX, "Unit", "u").properties().get("note"),
                "same_result_after_link_ended", first.equals(replay), "deliveries", sent.size(), "captured_data", sent.getFirst().config().get("data"),
                "revoked_related_read_blocks_replay", denied, "target_version", storage.getObject(CTX, "Unit", "u").version());
    }

    static Map<String, Object> customScalarProbe(StorageProvider storage) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name:"scalar-probe",version:"1.0.0")
                "A structured external value"
                scalar Payload
                type Item { id: ID! @primary value: Payload! secret: Payload @sensitive }
                type Create @actionType(permission:"can_create") { id: ID! @param value: Payload! @param }
                """);
        storage.applySchema(CTX, schema);
        var action = new ActionManifestParser().parse("""
                action: Create
                version: 1
                effects:
                  - type: createObject
                    objectType: Item
                    target: params.id
                    properties: {value: params.value, secret: params.value}
                """);
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> true),
                new ActionExecutor(), schema, Map.of("Create", action), Map.of());
        var payload = Map.of("code", "A", "amount", new java.math.BigDecimal("1.0000000000000000001"));
        var parameters = Map.<String, Object>of("id", "a", "value", payload);
        var result = app.execute(action, CTX, PRINCIPAL, parameters, "scalar");
        var replay = app.execute(action, CTX, PRINCIPAL, parameters, "scalar");
        var record = app.getObject(CTX, PRINCIPAL, "Item", "a");
        var filter = new ObjectConnectionQuery(Map.of("value", Map.of("eq", payload)), Map.of(), ConnectionPage.defaults());
        var graph = GraphqlApiRuntime.create(schema, app, Map.of("Create", action));
        var graphResult = graph.execute(graphql.ExecutionInput.newExecutionInput("{item(id:\"a\"){value secret} __type(name:\"Payload\"){name kind description}}")
                .graphQLContext(Map.of("request", new ApiRequestContext(CTX, PRINCIPAL))).build());
        if (!graphResult.getErrors().isEmpty()) throw new IllegalStateException(graphResult.getErrors().toString());
        boolean ordered = false;
        try { app.queryConnection(CTX, PRINCIPAL, "Item", new ObjectConnectionQuery(Map.of(), Map.of("value", "ASC"), ConnectionPage.defaults())); }
        catch (IllegalArgumentException expected) { ordered = true; }
        return Map.of("declared_scalars", schema.scalars().stream().map(type -> type.name()).toList(),
                "precise_value_roundtrip", payload.equals(record.properties().get("value")),
                "secret_hidden", !record.properties().containsKey("secret"), "idempotent_replay", result.equals(replay),
                "equality_count", app.queryConnection(CTX, PRINCIPAL, "Item", filter).totalCount(),
                "opaque_ordering_rejected", ordered, "graphql", graphResult.getData());
    }

    static Map<String, Object> consentEffectProbe(StorageProvider storage, ConsentStore store) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name:"consent-effects-probe",version:"1.0.0")
                type Person @objectType { id: ID! @primary name: String! }
                type Register @actionType(permission:"can_register") { id: ID! @param name: String! @param consent: Boolean! @param }
                """);
        storage.applySchema(CTX, schema);
        var manifest = new ActionManifestParser().parse("""
                action: Register
                version: 1
                effects:
                  - type: createObject
                    objectType: Person
                    target: params.id
                    properties: {name: params.name}
                  - type: recordConsent
                    subject: person
                    evidence: "recorded by action"
                    condition: "params.consent != false"
                """);
        var executor = new ActionExecutor().withAuthorization((context, actor, definition, values) -> true)
                .withConsentStore(store, "PUBLIC_DUTY", Set.of("Person"), Set.of("PUBLIC_DUTY"));
        var actor = new ActionActor(CTX.actorId(), Set.of());
        var definition = schema.actionTypes().getFirst();
        var parameters = Map.<String, Object>of("id", "a", "name", "A", "consent", true);
        var result = executor.execute(manifest, definition, CTX, actor, parameters, "record", storage);
        var subject = new EntityKey("Person", "a");
        int initial = store.snapshot(CTX, subject).records().size();
        store.record(CTX, subject, "PUBLIC_DUTY", ConsentRecord.Decision.DENY, "later withdrawal");
        var replay = executor.execute(manifest, definition, CTX, actor, parameters, "record", storage);
        var compensated = new ActionManifest(manifest.action(), manifest.version(), false, manifest.preconditions(), manifest.effects(),
                ActionManifest.RollbackPolicy.ROLLBACK_ALL,
                List.of(new ActionManifest.SideEffect("notify", "event", Map.of("type", "registered"), 1, java.time.Duration.ZERO)));
        var failed = executor.withSideEffects(invocation -> { throw new IllegalStateException("delivery failed"); })
                .execute(compensated, definition, CTX, actor, Map.of("id", "b", "name", "B", "consent", true), "rollback", storage);
        executor.execute(manifest, definition, CTX, actor, Map.of("id", "c", "name", "C", "consent", false), "skip", storage);
        return Map.of("initial_records", initial, "replay_same_result", result.equals(replay),
                "after_replay", store.snapshot(CTX, subject).records().stream().map(record -> record.decision().name()).toList(),
                "compensation_status", failed.status(), "compensated_object_deleted", storage.getObject(CTX, "Person", "b").isDeleted(),
                "compensation_decisions", store.snapshot(CTX, new EntityKey("Person", "b")).records().stream().map(record -> record.decision().name()).toList(),
                "false_condition_records", store.snapshot(CTX, new EntityKey("Person", "c")).records().size());
    }

    static Map<String, Object> consentProbe(StorageProvider storage, ConsentStore store) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name:"consent-probe",version:"1.0.0")
                type Item @objectType { id: ID! @primary amount: Int! }
                """);
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Item", "a", Map.of("amount", 100));
            tx.createObject("Item", "b", Map.of("amount", 2));
            tx.commit();
        }
        String purpose = "PUBLIC_DUTY";
        var auth = new AuthorizationService((principal, relation, key) -> true);
        var service = new ConsentService(store, auth, new ConsentConfiguration(Set.of("Item"), purpose));
        var app = new ApplicationService(storage, auth, new ActionExecutor(), schema, Map.of(), Map.of(), AuthorizationMode.STRICT_RESOURCES, service);
        var adminContext = RequestContext.system(CTX.tenantId(), "consent-admin");
        var admin = new SecurityPrincipal("consent-admin", CTX.tenantId(), Set.of("admin"));
        var b = new EntityKey("Item", "b");
        var restricted = app.readObject(CTX, PRINCIPAL, "Item", "a");
        boolean denied = false;
        try { service.record(CTX, PRINCIPAL, b, purpose, ConsentRecord.Decision.GRANT, "no recorder role"); }
        catch (SecurityException expected) { denied = true; }
        service.record(adminContext, admin, b, purpose, ConsentRecord.Decision.GRANT, "approved");
        int visible = app.queryConnection(CTX, PRINCIPAL, "Item", new ObjectConnectionQuery(Map.of(), Map.of(), ConnectionPage.defaults())).totalCount();
        var sum = app.aggregateObjects(CTX, PRINCIPAL, "Item", new AggregateQuery(List.of(new AggregateQuery.Field("amount", AggregateQuery.Function.SUM)), List.of(), Map.of(), List.of()))
                .groups().getFirst().values().get("sum_amount");
        service.revoke(adminContext, admin, b, purpose, "withdrawn");
        int after = app.listObjects(CTX, PRINCIPAL, "Item", QueryOptions.defaults()).size();
        var exempt = new ConsentService(store, auth, new ConsentConfiguration(Set.of("Item"), purpose, Set.of(purpose), Set.of("admin"), new ConsentConfiguration.Exemption(purpose, "viewer")));
        boolean exemptionPrecedesDeny = exempt.check(CTX, PRINCIPAL, b, purpose).allowed();
        exempt.setOptOut(adminContext, admin, b, true, "disable exemption");
        return Map.of("id_only_without_consent", restricted.consentRestricted() && restricted.object() == null,
                "non_recorder_rejected", denied, "consented_visible_count", visible, "consented_sum", sum,
                "visible_after_revocation", after, "configured_exemption_precedes_explicit_deny", exemptionPrecedesDeny,
                "opt_out_disables_exemption", !exempt.check(CTX, PRINCIPAL, b, purpose).allowed(),
                "decision_history", service.records(adminContext, admin, b).records().stream().map(record -> record.decision().name()).toList());
    }

    static Map<String, Object> objectSetProbe(StorageProvider storage, ObjectSetStore store) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name:"sets", version:"1.0.0")
                type Item @objectType { id: ID! @primary amount: Int! secret: String @sensitive }
                """);
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Item", "a", Map.of("amount", 1));
            tx.createObject("Item", "b", Map.of("amount", 2));
            tx.createObject("Item", "c", Map.of("amount", 3));
            tx.commit();
        }
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> principal.id().equals(PRINCIPAL.id()) || key.id().equals("b")),
                new ActionExecutor(), schema, Map.of(), Map.of());
        var service = new ObjectSetService(app, store);
        var saved = service.create(CTX, PRINCIPAL, Map.of("name", "Saved cohort", "objectType", "Item", "isPublic", true,
                "filter", Map.of("field", "amount", "operator", "gte", "value", 2),
                "aggregation", Map.of("fields", List.of(Map.of("field", "amount", "fn", "SUM", "alias", "total")),
                        "filter", Map.of("field", "amount", "operator", "lte", "value", 2))));
        var otherContext = RequestContext.system(CTX.tenantId(), "other-reader");
        var other = new SecurityPrincipal("other-reader", CTX.tenantId(), Set.of());
        var result = service.execute(otherContext, other, saved.id(), 20, 0);
        boolean changeRejected = false;
        try { service.update(otherContext, other, saved.id(), Map.of("name", "Forbidden"), null); }
        catch (SecurityException expected) { changeRejected = true; }
        double total = service.aggregate(CTX, PRINCIPAL, saved.id()).groups().getFirst().values().get("total").doubleValue();
        service.update(CTX, PRINCIPAL, saved.id(), Map.of("isPublic", false), saved.version());
        return Map.of("reader_visible_ids", result.edges().stream().map(edge -> edge.node().id()).toList(), "reader_total", result.totalCount(),
                "aggregate_filter_intersection", total, "non_owner_update_rejected", changeRejected,
                "unshared_definition_hidden", service.get(otherContext, other, saved.id()) == null,
                "creator_from_context", saved.createdBy().equals(CTX.actorId()));
    }

    static Map<String, Object> readBindingProbe(StorageProvider storage) {
        String source = """
                extend schema @namespace(name: "read-binding", version: "1.0.0")
                type Item @objectType { id: ID! @primary name: String! secret: String }
                """;
        var original = new OdlParser().parse(source);
        var restricted = new OdlParser().parse(source.replace("secret: String", "secret: String @sensitive"));
        storage.applySchema(CTX, original);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Item", "a", Map.of("name", "Example", "secret", "synthetic-private"));
            tx.commit();
        }
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> true), new ActionExecutor(), original, Map.of(), Map.of());
        var graph = GraphqlApiRuntime.create(original, app);
        boolean originallyVisible = app.getObject(CTX, PRINCIPAL, "Item", "a").properties().containsKey("secret");
        if (storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(CTX, restricted, new MigrationPlan("Restrict field visibility", true), jdbc.boundSchemaVersion());
        else storage.applySchema(CTX, restricted);
        boolean rejected = false;
        try { app.getObject(CTX, PRINCIPAL, "Item", "a"); } catch (SchemaVersionMismatchException expected) { rejected = true; }
        var result = graph.execute(ExecutionInput.newExecutionInput("{item(id:\"a\"){id secret}}")
                .graphQLContext(Map.of("request", new ApiRequestContext(CTX, PRINCIPAL))).build());
        var fresh = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> true), new ActionExecutor(), restricted, Map.of(), Map.of());
        return Map.of("field_visible_before_activation", originallyVisible, "old_application_rejected_after_provider_rebind", rejected,
                "old_graphql_data_discarded", result.getData() == null && !result.getErrors().isEmpty(),
                "old_graphql_error_code", result.getErrors().getFirst().getExtensions().get("code"),
                "new_application_hides_field", !fresh.getObject(CTX, PRINCIPAL, "Item", "a").properties().containsKey("secret"));
    }

    static Map<String, Object> activationProbe() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:audit_activation;DB_CLOSE_DELAY=-1");
        String source = """
                extend schema @namespace(name: "activation", version: "1.0.0")
                type Item @objectType { id: ID! @primary name: String! }
                """;
        var initial = new OdlParser().parse(source);
        var next = new OdlParser().parse(source.replace("name: String!", "name: String! extra: String"));
        var old = new JdbcStorageProvider(data, DatabaseDialect.h2());
        old.applySchema(CTX, initial);
        var active = new JdbcStorageProvider(data, DatabaseDialect.h2());
        active.applySchema(CTX, initial);
        var registry = new JdbcSchemaRegistry(data, DatabaseDialect.h2(), "storage", java.time.Clock.systemUTC());
        registry.applyIfChanged(next, null);
        try (var tx = old.beginTransaction(CTX)) { tx.createObject("Item", "before", Map.of("name", "Before")); tx.commit(); }
        boolean candidateStillInactive = old.getObject(CTX, "Item", "before") != null;
        boolean inFlightRejected = false;
        try (var tx = old.beginTransaction(CTX)) {
            tx.createObject("Item", "pending", Map.of("name", "Must roll back"));
            active.activateSchema(CTX, next, null, 1);
            try { tx.commit(); } catch (SchemaVersionMismatchException expected) { inFlightRejected = true; }
        }
        boolean staleRejected = false;
        try (var tx = old.beginTransaction(CTX)) {
            try { tx.createObject("Item", "stale", Map.of("name", "Stale")); }
            catch (SchemaVersionMismatchException expected) { staleRejected = true; }
        }
        var required = new OdlParser().parse(source.replace("name: String!", "name: String! extra: String required: String!"));
        boolean missingRejected = false;
        try { active.activateSchema(CTX, required, new MigrationPlan("Approval does not manufacture missing values", true), 2); }
        catch (PropertyValidationException expected) { missingRejected = true; }
        return Map.of("registered_candidate_did_not_block_old_model", candidateStillInactive,
                "active_version", active.boundSchemaVersion(), "in_flight_commit_rejected", inFlightRejected,
                "pending_object_absent", active.getObject(CTX, "Item", "pending") == null, "stale_writer_rejected", staleRejected,
                "approved_but_invalid_data_rejected", missingRejected, "registered_version_after_failed_activation", registry.currentVersion());
    }

    static Map<String, Object> registryProbe() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:audit_registry;DB_CLOSE_DELAY=-1");
        var registry = new JdbcSchemaRegistry(data, DatabaseDialect.h2());
        var source = """
                extend schema @namespace(name: "registry", version: "1.0.0")
                type Item @objectType { id: ID! @primary name: String }
                type Move @actionType(permission: "can_move") { first: Item! @param second: Item! @param }
                """;
        var schema = new OdlParser().parse(source);
        int initial = registry.applyIfChanged(schema, null).version();
        int restarted = new JdbcSchemaRegistry(data, DatabaseDialect.h2()).applyIfChanged(schema, null).version();
        var changed = new OdlParser().parse(source.replace("first: Item! @param second: Item! @param", "second: Item! @param first: Item! @param"));
        boolean drift = false;
        try { registry.requireCurrent(changed); } catch (SchemaDriftException expected) { drift = true; }
        boolean breakingRejected = false;
        try { registry.apply(changed, null, initial); } catch (SchemaValidationException expected) { breakingRejected = true; }
        var plan = new MigrationPlan("Review changed Action authorization target; registry records evidence only", true);
        var approved = registry.apply(changed, plan, initial);
        boolean staleRejected = false;
        try { registry.apply(changed, plan, initial); } catch (SchemaVersionConflictException expected) { staleRejected = true; }
        var reloaded = new JdbcSchemaRegistry(data, DatabaseDialect.h2());
        return Map.of("initial_version", initial, "unchanged_restart_version", restarted,
                "action_parameter_order_drift_rejected", drift, "breaking_without_approval_rejected", breakingRejected,
                "approved_version", approved.version(), "stale_version_rejected", staleRejected,
                "persisted_plan", reloaded.history().getLast().migrationPlan().description(),
                "old_snapshot_preserved", reloaded.atVersion(1).equals(schema));
    }

    static Map<String, Object> connectionProbe(StorageProvider storage) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "connection", version: "1.0.0")
                type Item @objectType { id: ID! @primary }
                """);
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) {
            for (int index = 0; index < 7; index++) tx.createObject("Item", "a" + index, Map.of());
            tx.createObject("Item", "hidden", Map.of());
            tx.commit();
        }
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> !key.id().equals("hidden")),
                new ActionExecutor(), schema, Map.of(), Map.of());
        String before = java.util.Base64.getEncoder().encodeToString("cursor:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var bounded = app.queryConnection(CTX, PRINCIPAL, "Item", new ObjectConnectionQuery(Map.of(), Map.of(), new ConnectionPage(null, null, 3, before, 0)));
        var zero = app.queryConnection(CTX, PRINCIPAL, "Item", new ObjectConnectionQuery(Map.of(), Map.of(), new ConnectionPage(0, null, null, null, 0)));
        var context = Map.<String, Object>of("request", new ApiRequestContext(CTX, PRINCIPAL));
        var graph = GraphqlApiRuntime.create(schema, app);
        var tail = graph.execute(ExecutionInput.newExecutionInput("{items(last:2){edges{node{id}} totalCount pageInfo{hasNextPage hasPreviousPage}}}")
                .graphQLContext(context).build());
        var legacy = GraphqlApiRuntime.create(schema, app, Map.of(), GraphqlApiRuntime.ActionMode.TYPED, GraphqlApiRuntime.QueryMode.LEGACY_LIST)
                .execute(ExecutionInput.newExecutionInput("{items(first:1){id}}").graphQLContext(context).build());
        return Map.of("bounded_before_ids", bounded.edges().stream().map(edge -> edge.node().id()).toList(),
                "zero_edges", zero.edges().size(), "visible_total", zero.totalCount(),
                "default_tail_errors", tail.getErrors().stream().map(error -> error.getMessage()).toList(), "default_tail_data", tail.getData(),
                "legacy_errors", legacy.getErrors().stream().map(error -> error.getMessage()).toList(), "legacy_data", legacy.getData());
    }

    static Map<String, Object> searchProbe(StorageProvider storage) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "search", version: "1.0.0")
                type Entry @objectType { id: ID! @primary title: String! secret: String @sensitive }
                """);
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Entry", "hidden", Map.of("title", "alpha ".repeat(100)));
            tx.createObject("Entry", "a", Map.of("title", "alpha alpha river"));
            tx.createObject("Entry", "b", Map.of("title", "alpha river", "secret", "alpha ".repeat(100)));
            tx.createObject("Entry", "c", Map.of("title", "quiet", "secret", "alpha"));
            tx.commit();
        }
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> !key.id().equals("hidden")),
                new ActionExecutor(), schema, Map.of(), Map.of());
        var terms = app.searchObjects(CTX, PRINCIPAL, "Entry", new SearchQuery("alpha river", null, Map.of(), SearchQuery.Mode.TERMS, 1, 0, null, null, false));
        var phrase = app.searchObjects(CTX, PRINCIPAL, "Entry", new SearchQuery("alpha river", null, Map.of(), SearchQuery.Mode.PHRASE, 20, 0, null, null, false));
        boolean hiddenRejected = false;
        try { app.searchObjects(CTX, PRINCIPAL, "Entry", new SearchQuery("alpha", List.of("secret"), Map.of())); }
        catch (SecurityException expected) { hiddenRejected = true; }
        return Map.of("visible_total", terms.totalCount(), "first_id", terms.hits().getFirst().node().id(),
                "first_term_score", terms.hits().getFirst().score(), "has_next_page", terms.hasNextPage(),
                "phrase_scores", phrase.hits().stream().map(SearchResult.Hit::score).toList(),
                "hidden_field_rejected", hiddenRejected,
                "highlights_exclude_secret", phrase.hits().stream().noneMatch(hit -> hit.highlights().containsKey("secret")));
    }

    static Map<String, Object> aggregateProbe(StorageProvider storage) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "aggregation", version: "1.0.0")
                type Entry @objectType { id: ID! @primary category: String amount: Float secret: Float @sensitive }
                """);
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Entry", "hidden", Map.of("category", "hidden-only", "amount", 100000, "secret", 999));
            tx.createObject("Entry", "visible-a", Map.of("category", "A", "amount", 1));
            tx.createObject("Entry", "visible-b", Map.of("category", "A", "amount", 3));
            tx.createObject("Entry", "visible-c", Map.of("category", "B"));
            tx.commit();
        }
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> key.id().startsWith("visible-")),
                new ActionExecutor(), schema, Map.of(), Map.of());
        var fields = List.of(new AggregateQuery.Field("*", AggregateQuery.Function.COUNT),
                new AggregateQuery.Field("amount", AggregateQuery.Function.SUM), new AggregateQuery.Field("amount", AggregateQuery.Function.AVG));
        var total = app.aggregateObjects(CTX, PRINCIPAL, "Entry", new AggregateQuery(fields, List.of(), Map.of(), List.of()));
        var grouped = app.aggregateObjects(CTX, PRINCIPAL, "Entry", new AggregateQuery(fields, List.of("category"), Map.of(), List.of(),
                1, 0, null, null, false));
        boolean hiddenRejected = false;
        try {
            app.aggregateObjects(CTX, PRINCIPAL, "Entry", new AggregateQuery(List.of(new AggregateQuery.Field("secret", AggregateQuery.Function.SUM)), List.of(), Map.of(), List.of()));
        } catch (SecurityException expected) { hiddenRejected = true; }
        return Map.of("visible_totals", total.groups().getFirst().values(), "total_visible_groups", grouped.totalGroups(),
                "page_group_count", grouped.groups().size(), "first_group_keys", grouped.groups().getFirst().keys(),
                "first_group_values", grouped.groups().getFirst().values(), "hidden_metric_rejected", hiddenRejected);
    }

    static Map<String, Object> governedQueryProbe(StorageProvider storage) {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "query", version: "1.0.0")
                type Entry @objectType { id: ID! @primary rank: Int! secret: String @sensitive }
                """);
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) {
            for (int index = 0; index < 125; index++) {
                tx.createObject("Entry", "hidden-" + index, Map.of("rank", index, "secret", "hidden"));
            }
            tx.createObject("Entry", "visible-a", Map.of("rank", 2));
            tx.createObject("Entry", "visible-b", Map.of("rank", 1));
            tx.commit();
        }
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> key.id().startsWith("visible-")),
                new ActionExecutor(), schema, Map.of(), Map.of());
        var result = app.queryObjects(CTX, PRINCIPAL, "Entry", new org.openfoundry.foundation.api.ObjectQuery(
                Map.of("rank", Map.of("lte", 2)), Map.of("rank", "ASC"), new QueryOptions(1, 0, null, null, false)));
        boolean hiddenRejected = false;
        try {
            app.queryObjects(CTX, PRINCIPAL, "Entry", new org.openfoundry.foundation.api.ObjectQuery(
                    Map.of("secret", Map.of("exists", false)), Map.of(), QueryOptions.defaults()));
        } catch (SecurityException expected) { hiddenRejected = true; }
        return Map.of("visible_total", result.totalCount(), "first_visible_id", result.items().getFirst().id(),
                "has_next_page", result.connection().pageInfo().hasNextPage(), "hidden_predicate_rejected", hiddenRejected,
                "legacy_list_visible_ids", app.listObjects(CTX, PRINCIPAL, "Entry", new QueryOptions(2, 0, null, null, false))
                        .stream().map(ObjectRecord::id).toList());
    }

    static Map<String, Object> typedActionProbe() {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "typed", version: "1.0.0")
                enum State { READY DONE }
                type Thing @objectType { id: ID! @primary state: State! }
                type SetState @actionType(permission: "can_set") { thing: Thing! @param state: State! @param }
                """);
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CTX, schema);
        try (var tx = storage.beginTransaction(CTX)) { tx.createObject("Thing", "item", Map.of("state", "READY")); tx.commit(); }
        var action = new ActionManifest("SetState", 1, false, List.of(), List.of(new ActionManifest.UpdateObject("thing", Map.of("state", "params.state"))));
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> true), new ActionExecutor(), schema,
                Map.of("SetState", action), Map.of());
        var graph = GraphqlApiRuntime.create(schema, app, Map.of("SetState", action));
        var context = Map.<String, Object>of("request", new ApiRequestContext(CTX, PRINCIPAL));
        var rejected = graph.execute(ExecutionInput.newExecutionInput("mutation { setState(input: {thing: \"item\", state: MISSING}) { success } }")
                .graphQLContext(context).build());
        long afterRejected = storage.getObject(CTX, "Thing", "item").version();
        var accepted = graph.execute(ExecutionInput.newExecutionInput("mutation { setState(input: {thing: \"item\", state: DONE}) { success affectedObjects { typeName id changeType } } }")
                .graphQLContext(context).build());
        return Map.of("invalid_enum_rejected", !rejected.getErrors().isEmpty(), "version_after_rejected", afterRejected,
                "accepted_errors", accepted.getErrors().stream().map(error -> error.getMessage()).toList(), "accepted_data", accepted.getData(),
                "stored_state", storage.getObject(CTX, "Thing", "item").properties().get("state"));
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
