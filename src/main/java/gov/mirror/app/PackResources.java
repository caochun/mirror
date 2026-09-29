package gov.mirror.app;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Adapts bundled resources to Foundry's directory loader, including executable JAR resources. */
public final class PackResources implements AutoCloseable {
    private final Path directory;
    private final boolean temporary;

    private PackResources(Path directory, boolean temporary) {
        this.directory = directory;
        this.temporary = temporary;
    }

    public static PackResources open(String externalDirectory) throws IOException {
        if (externalDirectory != null && !externalDirectory.isBlank()) {
            Path directory = Path.of(externalDirectory).toRealPath();
            if (!Files.isRegularFile(directory.resolve("pack.yaml"))) {
                throw new IOException("External Pack directory must contain pack.yaml");
            }
            return new PackResources(directory, false);
        }
        var pack = new PackResources(Files.createTempDirectory("mirror-domain-pack-"), true);
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            for (var resource : resolver.getResources("classpath:domain-pack/**")) {
                String url = resource.getURL().toExternalForm();
                if (url.endsWith("/") || !resource.isReadable()) continue;
                int marker = url.lastIndexOf("/domain-pack/");
                if (marker < 0) throw new IOException("Unexpected Pack resource: " + resource.getFilename());
                String relative = UriUtils.decode(url.substring(marker + "/domain-pack/".length()), StandardCharsets.UTF_8);
                Path target = pack.directory.resolve(relative).normalize();
                if (!target.startsWith(pack.directory)) throw new IOException("Pack resource escapes its directory");
                Files.createDirectories(target.getParent());
                try (var input = resource.getInputStream()) {
                    Files.copy(input, target);
                }
            }
            if (!Files.isRegularFile(pack.directory.resolve("pack.yaml"))) {
                throw new IOException("Bundled domain-pack/pack.yaml is missing");
            }
            return pack;
        } catch (IOException | RuntimeException failure) {
            try { pack.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void close() throws IOException {
        if (!temporary || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
