package gov.mirror.explorer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class PreviewConfiguration {
    @Bean(destroyMethod = "close")
    PreviewRuntime previewRuntime(PreviewProperties properties) {
        return new PreviewRuntime(Path.of(properties.packDirectory()));
    }
}
