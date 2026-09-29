package gov.mirror.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mirror.foundry")
public record FoundryProperties(String packDirectory, String jdbcUrl, boolean seedDemo, boolean seedReference) {
    public FoundryProperties {
        if (seedReference && !seedDemo) {
            throw new IllegalArgumentException("Reference fixtures require mirror.foundry.seed-demo=true");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:h2:file:./.runtime/personnel-v1/foundry";
        }
        if (!jdbcUrl.startsWith("jdbc:h2:")) {
            throw new IllegalArgumentException("This local adapter supports H2 only; target database requires validation");
        }
    }
}
