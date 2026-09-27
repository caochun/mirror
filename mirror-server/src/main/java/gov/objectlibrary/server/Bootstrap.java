package gov.objectlibrary.server;

import java.util.Map;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class Bootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final StorageProvider storage;
    private final String password;
    private final boolean demo;
    Bootstrap(JdbcTemplate jdbc, PasswordEncoder passwords, StorageProvider storage,
              @Value("${mirror.bootstrap-password}") String password, @Value("${mirror.demo}") boolean demo) {
        this.jdbc=jdbc; this.passwords=passwords; this.storage=storage; this.password=password; this.demo=demo;
    }
    @Override public void run(ApplicationArguments arguments) {
        if (password.isBlank()) return;
        if (password.length() < 12 || password.length() > 72) throw new IllegalArgumentException("Bootstrap password must have 12–72 characters");
        var context = RequestContext.system("mirror", "bootstrap");
        try (var tx = storage.beginTransaction(context)) {
            if (storage.getObject(context, "Organization", "city") == null)
                tx.createObject("Organization", "city", Map.of("name", "市级管理单位", "nature", "CITY", "status", "ACTIVE"));
            tx.commit();
        }
        account("admin", "系统管理员", "city", "SUPER_ADMIN");
        if (!demo) return;
        // Explicit opt-in only. Synthetic names and identifiers; never loaded for ordinary production startup.
        try (var tx = storage.beginTransaction(context)) {
            if (storage.getObject(context, "Organization", "demo-a") == null) {
                tx.createObject("Organization", "demo-a", Map.of("name", "演示一局", "nature", "DEPARTMENT", "status", "ACTIVE"));
                tx.createObject("Organization", "demo-child", Map.of("name", "演示一局业务科", "nature", "DEPARTMENT", "status", "ACTIVE"));
                tx.createObject("Organization", "demo-b", Map.of("name", "演示二局", "nature", "DEPARTMENT", "status", "ACTIVE"));
                for (String id : new String[]{"demo-a", "demo-b", "demo-child"})
                    tx.createLink("OrganizationParent", "parent-"+id, new EntityKey("Organization",id),
                            new EntityKey("Organization",id.equals("demo-child")?"demo-a":"city"), Map.of("relation","PARENT", "startedAt", "2026-01-01T00:00:00Z"));
                for (int i=1;i<=24;i++) {
                    String id="demo-person-"+String.format("%03d",i);
                    String org=i<=8?"demo-a":i<=16?"demo-child":"demo-b";
                    tx.createObject("Person",id,Map.of("name","演示人员"+String.format("%02d",i),
                            "employeeNo","DEMO-"+i,"status","ACTIVE","identityStatus","OK","title","业务经办","identityReference","mock:"+id));
                    tx.createLink("PersonBelongsToOrganization","org-"+id,new EntityKey("Person",id),
                            new EntityKey("Organization",org),Map.of("startedAt","2026-01-01T00:00:00Z"));
                }
            }
            tx.commit();
        }
        account("unit", "演示单位管理员", "demo-a", "UNIT_ADMIN");
        account("reviewer", "演示审核员", "demo-a", "REVIEWER");
        account("area", "演示片区管理员", "city", "AREA_ADMIN");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mirror_scope_roots WHERE username = ?", Integer.class, "area")==0)
            jdbc.update("INSERT INTO mirror_scope_roots(username,organization_id) VALUES (?,?)", "area", "demo-a");
    }
    private void account(String username, String displayName, String org, String role) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mirror_accounts WHERE username = ?",Integer.class,username)!=0) return;
        jdbc.update("INSERT INTO mirror_accounts(username,password_hash,display_name,tenant_id,organization_id,role_name) VALUES (?,?,?,?,?,?)",
                username,passwords.encode(password),displayName,"mirror",org,role);
    }
}
