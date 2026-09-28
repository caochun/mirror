package gov.mirror.explorer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mirror.preview")
public record PreviewProperties(String packDirectory, String assetsDirectory, int port) {
    public PreviewProperties {
        if (packDirectory == null || packDirectory.isBlank()) packDirectory = "domain-pack";
        if (assetsDirectory == null || assetsDirectory.isBlank()) assetsDirectory = "pack-explorer/web/dist";
        if (port < 1 || port > 65535) port = 8090;
    }
}
