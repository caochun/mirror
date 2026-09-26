package gov.objectlibrary.server;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

@Configuration
class StorageConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    JdbcStorageProvider storage(DataSource dataSource, @Value("${mirror.dialect}") String dialect,
                                @Value("${mirror.pack}") String pack) {
        var selected = switch (dialect) {
            case "h2" -> DatabaseDialect.h2();
            case "postgresql" -> DatabaseDialect.postgresql();
            case "openGauss" -> DatabaseDialect.openGauss();
            default -> throw new IllegalArgumentException("Unsupported configured database dialect: " + dialect);
        };
        var storage = new JdbcStorageProvider(dataSource, selected);
        storage.applySchema(RequestContext.system("system", "bootstrap"),
                new DomainPackLoader().load(Path.of(pack).toAbsolutePath().normalize()).ontology().schema());
        return storage;
    }
}
