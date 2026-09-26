package gov.objectlibrary.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class AuthController {
    private final AuthenticationManager authentication;
    private final HttpSessionSecurityContextRepository contexts;
    private final Accounts accounts;
    AuthController(AuthenticationManager authentication, HttpSessionSecurityContextRepository contexts, Accounts accounts) {
        this.authentication = authentication; this.contexts = contexts; this.accounts = accounts;
    }
    @GetMapping("/health") Map<String, String> health() { return Map.of("status", "UP", "application", "mirror"); }
    @GetMapping("/auth/csrf") Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
    }
    @PostMapping("/auth/login") Accounts.Actor login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        var auth = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(), input.password()));
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        contexts.saveContext(context, request, response);
        SecurityContextHolder.setContext(context);
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        return accounts.actor(auth.getName());
    }
    @GetMapping("/auth/me") Accounts.Actor me(Principal principal) { return accounts.actor(principal.getName()); }
    @GetMapping("/auth/permissions") java.util.List<String> permissions(Principal principal) {
        return accounts.permissions(accounts.actor(principal.getName()));
    }
    record Login(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=128) String password) {
        @Override public String toString() { return "Login[credentials=REDACTED]"; }
    }
}
