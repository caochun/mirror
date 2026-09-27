package gov.objectlibrary.server;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import java.util.Map;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:mirror_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true"
})
@AutoConfigureMockMvc
class ApplicationSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired StorageProvider storage;
    @Autowired JdbcTemplate jdbc;

    MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content("{\"username\":\""+username+"\",\"password\":\"TestOnlyPassword-123\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void authenticationCsrfAndLogoutAreEnforced() throws Exception {
        mvc.perform(get("/api/people")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        var session=login("admin");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void unitAndAreaIncludeDescendantsButRejectSiblingAndForgedRoles() throws Exception {
        for (String name:new String[]{"unit","area"}) {
            var session=login(name);
            mvc.perform(get("/api/people").session(session).param("size","100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(16))
                    .andExpect(jsonPath("$.items[*].organizationId",hasItem("demo-child")))
                    .andExpect(jsonPath("$.items[*].organizationId",not(hasItem("demo-b"))));
            mvc.perform(get("/api/people/demo-person-017").session(session).header("X-Role","SUPER_ADMIN"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/people").session(session).param("organization","demo-b"))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void reviewerCannotBrowseDirectoryOrPersonHistory() throws Exception {
        var session=login("reviewer");
        for(String endpoint:new String[]{"/api/people","/api/organizations","/api/people/demo-person-001"})
            mvc.perform(get(endpoint).session(session)).andExpect(status().isForbidden());
    }
    @Test void readsAllPagesAndNeverLeaksOtherTenantOrPrivateProperties() throws Exception {
        var context=RequestContext.system("mirror","test");
        try(var tx=storage.beginTransaction(context)) {
            for(int i=0;i<125;i++) tx.createObject("Person","bulk-"+i,Map.of("name","分页测试"+i,
                    "status","ACTIVE","identityStatus","PENDING","identityReference","PRIVATE-ID","phoneReference","PRIVATE-PHONE"));
            tx.commit();
        }
        try(var tx=storage.beginTransaction(RequestContext.system("other-tenant","test"))) {
            tx.createObject("Person","other-person",Map.of("name","SHOULD-NOT-LEAK","status","ACTIVE","identityStatus","PENDING"));tx.commit();
        }
        var session=login("admin");
        mvc.perform(get("/api/people").session(session).param("q","分页测试").param("page","1").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(125))
                .andExpect(jsonPath("$.items",hasSize(25)))
                .andExpect(content().string(not(containsString("PRIVATE"))));
        mvc.perform(get("/api/people/bulk-1").session(session))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("PRIVATE"))));
        mvc.perform(get("/api/people/other-person").session(session)).andExpect(status().isForbidden());
    }
    @Test void disabledAccountImmediatelyLosesApiAccess() throws Exception {
        var session=login("unit");
        try {
            jdbc.update("UPDATE mirror_accounts SET enabled=FALSE WHERE username='unit'");
            mvc.perform(get("/api/people").session(session)).andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE mirror_accounts SET enabled=TRUE WHERE username='unit'"); }
    }
}
