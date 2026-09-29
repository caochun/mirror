package gov.mirror.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final MirrorAccounts accounts;
    private final FoundryProperties properties;

    public SessionController(MirrorAccounts accounts, FoundryProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(Authentication auth, CsrfToken csrf) {
        var result = new LinkedHashMap<String, Object>();
        result.put("csrf", csrf.getToken());
        boolean loggedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        result.put("authenticated", loggedIn);
        result.put("demo", properties.seedDemo());
        if (loggedIn) {
            var account = accounts.current();
            result.put("username", account.username());
            result.put("roles", account.roles());
            result.put("organization", account.organization());
        }
        return result;
    }
}
