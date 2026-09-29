package gov.mirror.app;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.api.ApplicationService;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.pack.LoadedDomainPack;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import java.util.*;

/** Persistent Foundry services; HTTP identity is supplied for every request. */
public final class FoundryRuntime implements AutoCloseable {
    private final LoadedDomainPack pack;
    private final JdbcStorageProvider storage;
    private final JdbcDataSource data;
    private final java.sql.Connection lifetimeConnection;
    private final ApplicationService application;
    private final ObjectMapper json;

    public FoundryRuntime(FoundryProperties properties, MirrorAccounts accounts, PackResources resources) {
        pack = new DomainPackLoader().load(resources.directory());
        data = new JdbcDataSource();
        data.setURL(properties.jdbcUrl());
        try {
            // Keep the embedded file database open between SPI reads; close it on application shutdown.
            lifetimeConnection = data.getConnection();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Cannot open Mirror database", failure);
        }
        storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
        try {
            storage.applySchema(RequestContext.system(MirrorAccounts.TENANT, "schema-loader"), pack.ontology().schema());
            if (properties.seedDemo()) DemoData.populate(storage);
            if (properties.seedReference()) ReferenceDemoData.populate(storage);
            json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                    .registerModule(new JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            // Generic API is a read-only administrative surface. Business writes use a transactional Mirror policy.
            var authorization = new AuthorizationService((principal, relation, key) ->
                    relation.equals("viewer") && accounts.require(principal.id()).roles().contains("ADMIN"));
            application = new ApplicationService(storage, authorization, new ActionExecutor(),
                    pack.ontology().schema(), Map.of(), Map.of());
        } catch (RuntimeException | Error failure) {
            try { lifetimeConnection.close(); }
            catch (java.sql.SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    public LoadedDomainPack pack() { return pack; }
    public JdbcStorageProvider storage() { return storage; }
    public ApplicationService application() { return application; }

    /** Administrative diagnostics only; not a second domain persistence implementation. */
    public Map<String, Object> events(RequestContext context) {
        return Map.of("audit", records("of_audit_records", "timestamp_value", context),
                "outbox", records("of_outbox_events", "occurred_at", context));
    }

    private List<Map<String, Object>> records(String table, String order, RequestContext context) {
        var result = new ArrayList<Map<String, Object>>();
        try (var connection = data.getConnection();
             var statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE tenant_id=? ORDER BY " + order + " DESC")) {
            statement.setString(1, context.tenantId());
            statement.setMaxRows(200);
            try (var rows = statement.executeQuery()) {
                var metadata = rows.getMetaData();
                while (rows.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        String name = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);
                        Object value = rows.getString(i);
                        if (name.endsWith("_json") && value != null) value = json.readTree(value.toString());
                        row.put(name, value);
                    }
                    result.add(row);
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read diagnostics", failure);
        }
        return result;
    }

    @Override
    public void close() {
        storage.close();
        try { lifetimeConnection.close(); }
        catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot close Mirror database", failure); }
    }
}
