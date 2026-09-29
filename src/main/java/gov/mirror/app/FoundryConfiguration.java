package gov.mirror.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class FoundryConfiguration {
    @Bean(destroyMethod = "close")
    PackResources packResources(FoundryProperties properties) throws IOException {
        return PackResources.open(properties.packDirectory());
    }

    @Bean(destroyMethod = "close")
    FoundryRuntime foundryRuntime(FoundryProperties properties, MirrorAccounts accounts, PackResources resources) {
        return new FoundryRuntime(properties, accounts, resources);
    }
}
