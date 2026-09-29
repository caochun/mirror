package gov.mirror.app;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mirror.security")
public record AccountProperties(List<Account> users) {
    public AccountProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record Account(String username, String passwordHash, String organization,
                          Set<String> organizations, Set<String> roles) {
        public Account {
            organizations = organizations == null ? Set.of() : Set.copyOf(organizations);
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }
}
