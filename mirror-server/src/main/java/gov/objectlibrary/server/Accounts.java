package gov.objectlibrary.server;

import java.util.List;
import org.openfoundry.foundation.spi.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class Accounts implements UserDetailsService {
    private final JdbcTemplate jdbc;
    public Accounts(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public UserDetails loadUserByUsername(String username) {
        var rows = jdbc.query("SELECT * FROM mirror_accounts WHERE username = ?", (rs, i) ->
                User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                    .roles(rs.getString("role_name")).disabled(!rs.getBoolean("enabled")).build(), username);
        if (rows.isEmpty()) throw new UsernameNotFoundException("Account not found");
        return rows.getFirst();
    }
    public Actor actor(String username) {
        var rows = jdbc.query("SELECT * FROM mirror_accounts WHERE username = ? AND enabled = TRUE", (rs, i) ->
                new Actor(rs.getString("username"), rs.getString("display_name"), rs.getString("tenant_id"),
                        rs.getString("organization_id"), rs.getString("role_name")), username);
        if (rows.isEmpty()) throw new AccessDeniedException("账号已停用");
        return rows.getFirst();
    }
    public List<String> roots(String username) {
        return jdbc.queryForList("SELECT organization_id FROM mirror_scope_roots WHERE username = ?", String.class, username);
    }
    public List<String> permissions(Actor actor) {
        return jdbc.queryForList("SELECT permission_name FROM mirror_role_permissions WHERE role_name = ? ORDER BY permission_name",
                String.class, actor.role());
    }
    public void requirePermission(Actor actor, String permission) {
        if (!permissions(actor).contains(permission)) throw new AccessDeniedException("Missing functional permission");
    }
    public record Actor(String username, String displayName, String tenantId, String organizationId, String role) {
        public RequestContext context() { return RequestContext.system(tenantId, username); }
    }
}
