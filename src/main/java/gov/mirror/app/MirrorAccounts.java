package gov.mirror.app;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.RequestContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class MirrorAccounts {
    public static final String TENANT = "mirror";
    private final Map<String, AccountProperties.Account> accounts;

    public MirrorAccounts(AccountProperties properties) {
        accounts = properties.users().stream().collect(Collectors.toUnmodifiableMap(
                AccountProperties.Account::username, account -> account));
        if (accounts.isEmpty()) throw new IllegalArgumentException("Configure mirror.security.users before starting Mirror");
        Set<String> roles = Set.of("ADMIN", "DIRECTORY", "TAG_EDITOR", "READER");
        accounts.values().forEach(account -> {
            if (account.username() == null || account.username().isBlank()
                    || account.organization() == null || account.organization().isBlank()
                    || !account.organizations().contains(account.organization())
                    || account.passwordHash() == null || !account.passwordHash().matches("\\$2[aby]\\$\\d{2}\\$.{53}")
                    || account.roles().isEmpty() || !roles.containsAll(account.roles())) {
                throw new IllegalArgumentException("Account requires BCrypt password hash, roles and organization scope");
            }
        });
    }

    public AccountProperties.Account require(String username) {
        var account = accounts.get(username);
        if (account == null) throw new SecurityException("Account is unavailable");
        return account;
    }

    public AccountProperties.Account current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new SecurityException("Login required");
        return require(authentication.getName());
    }

    public SecurityPrincipal principal() {
        var account = current();
        return new SecurityPrincipal(account.username(), TENANT, account.roles());
    }

    public RequestContext context() {
        return RequestContext.system(TENANT, current().username());
    }

    public void requireAdmin() {
        if (!current().roles().contains("ADMIN")) throw new SecurityException("Administrator permission required");
    }
}
