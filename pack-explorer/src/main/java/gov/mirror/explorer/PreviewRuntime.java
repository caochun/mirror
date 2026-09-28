package gov.mirror.explorer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.api.ApplicationService;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.pack.LoadedDomainPack;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Spring-hosted state for the isolated Pack preview. No old Mirror persistence is opened. */
public final class PreviewRuntime implements AutoCloseable {
    public static final RequestContext CONTEXT = RequestContext.system("pack-preview", "demo-reviewer");
    public static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("demo-reviewer", "pack-preview", Set.of());
    private final LoadedDomainPack pack;
    private final InMemoryStorageProvider storage;
    private final ApplicationService application;
    private final ObjectMapper json;
    private final String token = UUID.randomUUID().toString();

    public PreviewRuntime(Path packDirectory) {
        pack = new DomainPackLoader().load(packDirectory);
        storage = new InMemoryStorageProvider();
        storage.applySchema(CONTEXT, pack.ontology().schema());
        DemoData.populate(storage);
        json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .registerModule(new JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        application = new ApplicationService(storage, new AuthorizationService((actor, relation, key) -> true), new ActionExecutor(),
                pack.ontology().schema(), pack.actions(), Map.of());
    }

    public LoadedDomainPack pack() { return pack; }
    public InMemoryStorageProvider storage() { return storage; }
    public ApplicationService application() { return application; }
    public ObjectMapper json() { return json; }
    public String token() { return token; }
    public RequestContext context() { return CONTEXT; }
    public SecurityPrincipal principal() { return PRINCIPAL; }

    @Override public void close() { }
}
