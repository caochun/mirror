package gov.objectlibrary.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mirror_tags;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack","mirror.bootstrap-password=TestOnlyPassword-123","mirror.demo=true"})
@AutoConfigureMockMvc
class TagWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StorageProvider storage;
    @Autowired TagService tags;
    @Autowired Accounts accounts;
    @Autowired DomainContracts contracts;
    private static final RequestContext CONTEXT=RequestContext.system("mirror","test");
    private String key() {return UUID.randomUUID().toString();}
    private MockHttpSession login(String user) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",user,"password","TestOnlyPassword-123"))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
    }
    private String create(MockHttpSession session,String parent) throws Exception {
        String code="T"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        var response=mvc.perform(post("/api/tags").session(session).with(csrf()).header("Idempotency-Key",key())
                .contentType("application/json").content(json.writeValueAsString(Map.of("code",code,"name","测试标签",
                        "parentId",parent,"dimension","PERSON","description","测试定义"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asText();
    }
    private Map<String,Object> assignment(String operation,long version,List<String> people) {
        var expected=new HashMap<String,Long>();people.forEach(p->expected.put(p,version));
        return Map.of("personIds",people,"expectedVersions",expected,"operation",operation,"note","","tagVersion",1);
    }
    private org.springframework.test.web.servlet.ResultActions assign(MockHttpSession session,String tag,String key,Map<String,Object> input) throws Exception {
        return mvc.perform(post("/api/tags/"+tag+"/assignments").session(session).with(csrf()).header("Idempotency-Key",key)
                .contentType("application/json").content(json.writeValueAsString(input)));
    }
    @Test void manualLifecycleHasDurableIdempotencyOptimisticVersionsAndHistory() throws Exception {
        var admin=login("admin");var unit=login("unit");String tag=create(admin,"");String command=key();
        var add=assignment("ADD",0,List.of("demo-person-001"));
        assign(unit,tag,command,add).andExpect(status().isOk());
        assign(unit,tag,command,add).andExpect(status().isOk());
        String id=TagService.assignmentId("demo-person-001",tag);
        assertEquals(1,storage.getEntityHistory(CONTEXT,new EntityKey("PersonTagAssignment",id)).size());
        // Same key must not silently accept another payload.
        assign(unit,tag,command,assignment("REMOVE",1,List.of("demo-person-001"))).andExpect(status().isConflict());
        assign(unit,tag,key(),assignment("REMOVE",0,List.of("demo-person-001"))).andExpect(status().isConflict());
        assign(unit,tag,key(),assignment("REMOVE",1,List.of("demo-person-001"))).andExpect(status().isOk());
        assertEquals(true,storage.getObject(CONTEXT,"PersonTagAssignment",id).properties().get("manualSuppressed"));
        assign(unit,tag,key(),assignment("ADD",2,List.of("demo-person-001"))).andExpect(status().isOk());
        var item=storage.getObject(CONTEXT,"PersonTagAssignment",id);
        assertEquals(false,item.properties().get("manualSuppressed"));assertNull(item.properties().get("effectiveTo"));
        assertEquals(2, storage.getLinks(CONTEXT, item.key(), "AssignmentHasContribution",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
        mvc.perform(get("/api/people/demo-person-001/tags/"+tag+"/history").session(unit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].state").value("SUPPRESSED"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM of_audit_records WHERE action_type='AddPersonTag'",Integer.class)>0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM of_outbox_events WHERE type='gov.object-library.AddPersonTag'",Integer.class)>0);
    }
    @Test void functionAndOrganizationChecksApplyToWholeBatchBeforeMutation() throws Exception {
        var admin=login("admin");var unit=login("unit");var reviewer=login("reviewer");String tag=create(admin,"");
        assign(unit,tag,key(),assignment("ADD",0,List.of("demo-person-001","demo-person-017"))).andExpect(status().isForbidden());
        assertNull(storage.getObject(CONTEXT,"PersonTagAssignment",TagService.assignmentId("demo-person-001",tag)));
        assign(reviewer,tag,key(),assignment("ADD",0,List.of("demo-person-001"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tags").session(unit).with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
                .content("{\"code\":\"ILLEGAL\",\"name\":\"越权\",\"parentId\":\"\",\"dimension\":\"PERSON\",\"description\":\"\"}"))
                .andExpect(status().isForbidden());
        try {
            jdbc.update("DELETE FROM mirror_role_permissions WHERE role_name='UNIT_ADMIN' AND permission_name='PERSON_TAG_WRITE'");
            assign(unit,tag,key(),assignment("ADD",0,List.of("demo-person-001"))).andExpect(status().isForbidden());
        } finally {jdbc.update("INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN','PERSON_TAG_WRITE')");}
    }
    @Test void invalidSecondPersonRollsBackFirstPersonAuditAndCommandReceipt() throws Exception {
        var admin=login("admin");String tag=create(admin,"");var person=storage.getObject(CONTEXT,"Person","demo-person-002");
        try(var tx=storage.beginTransaction(CONTEXT)) {tx.updateObject(person.type(),person.id(),Map.of("status","INACTIVE"),person.version());tx.commit();}
        int audits=jdbc.queryForObject("SELECT COUNT(*) FROM of_audit_records",Integer.class);
        int receipts=storage.queryObjects(CONTEXT,"BusinessCommandReceipt",new QueryOptions(1000,0,null,null,false)).size();
        try {
            assign(admin,tag,key(),assignment("ADD",0,List.of("demo-person-001","demo-person-002"))).andExpect(status().isConflict());
            assertNull(storage.getObject(CONTEXT,"PersonTagAssignment",TagService.assignmentId("demo-person-001",tag)));
            assertEquals(audits,jdbc.queryForObject("SELECT COUNT(*) FROM of_audit_records",Integer.class));
            assertEquals(receipts,storage.queryObjects(CONTEXT,"BusinessCommandReceipt",new QueryOptions(1000,0,null,null,false)).size());
        } finally {
            var current=storage.getObject(CONTEXT,"Person",person.id());
            try(var tx=storage.beginTransaction(CONTEXT)) {tx.updateObject(current.type(),current.id(),Map.of("status","ACTIVE"),current.version());tx.commit();}
        }
    }
    @Test void onlyLeavesCanBeAssignedAndUnusedThirdLevelCannotHaveChildren() throws Exception {
        var admin=login("admin");String root=create(admin,"");String second=create(admin,root);String third=create(admin,second);
        assign(admin,root,key(),assignment("ADD",0,List.of("demo-person-001"))).andExpect(status().isConflict());
        assign(admin,second,key(),assignment("ADD",0,List.of("demo-person-001"))).andExpect(status().isConflict());
        assign(admin,third,key(),assignment("ADD",0,List.of("demo-person-001"))).andExpect(status().isOk());
        mvc.perform(post("/api/tags").session(admin).with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("code","FOURTH","name","四级","parentId",third,"dimension","PERSON","description",""))))
                .andExpect(status().isConflict());
    }
    @Test void disablingDirectoryExpiresAssignmentsAndKeepsFrozenNames() throws Exception {
        var admin=login("admin");String tag=create(admin,"");
        assign(admin,tag,key(),assignment("ADD",0,List.of("demo-person-001"))).andExpect(status().isOk());
        mvc.perform(put("/api/tags/"+tag).session(admin).with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("name","新名称","description","新定义","status","INACTIVE","expectedVersion",1))))
                .andExpect(status().isOk());
        var assignment=storage.getObject(CONTEXT,"PersonTagAssignment",TagService.assignmentId("demo-person-001",tag));
        assertEquals("EXPIRED",assignment.properties().get("state"));
        assertEquals("测试标签",assignment.properties().get("tagNameSnapshot"));
        assertEquals("测试标签",storage.getObject(CONTEXT,"TagVersion",tag+"-v1").properties().get("nameSnapshot"));
        assign(admin,tag,key(),assignment("ADD",2,List.of("demo-person-001"))).andExpect(status().isConflict());
    }
    @Test void concurrentReplayCommitsOneAssignmentAndOneHistoryEntry() throws Exception {
        String tag=create(login("admin"),"");String key=key();var actor=accounts.actor("unit");
        var request=new TagService.AssignmentCommand(List.of("demo-person-003"),Map.of("demo-person-003",0L),"ADD","",1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Map<String,Object>> task=()->{start.await();return tags.assign(actor,tag,request,key);};
            var first=executor.submit(task);var second=executor.submit(task);start.countDown();
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,storage.getEntityHistory(CONTEXT,new EntityKey("PersonTagAssignment",TagService.assignmentId("demo-person-003",tag))).size());
        assertEquals(1, storage.getLinks(CONTEXT,
                new EntityKey("PersonTagAssignment", TagService.assignmentId("demo-person-003", tag)),
                "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
    }

    @Test
    void rejectsUnknownUnconnectedAndInvalidContractInputs() throws Exception {
        var actor = accounts.actor("unit");
        assertThrows(IllegalArgumentException.class, () -> contracts.authorize(actor, "ArbitraryWrite"));
        assertThrows(BusinessConflict.class, () -> contracts.authorize(actor, "DispatchReminder"));
        assertThrows(IllegalArgumentException.class, () -> contracts.validateInputs(actor, "AddPersonTag",
                Map.of("personId", "demo-person-001")));
        assertThrows(BusinessConflict.class, () -> contracts.requireTransition("PersonTagAssignment", "state",
                "SUPPRESSED", "EXPIRED", "RemovePersonTag"));
    }

    @Test
    void legacyRuleSourceSurvivesRemovalAndManualRestoration() throws Exception {
        var admin = login("admin");
        var unit = login("unit");
        String tag = create(admin, "");
        String id = TagService.assignmentId("demo-person-004", tag);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var assignment = tx.createObject("PersonTagAssignment", id, Map.of(
                    "personId", "demo-person-004", "tagDefinitionId", tag, "tagVersion", tag + "-v1",
                    "tagNameSnapshot", "测试标签", "state", "ACTIVE", "source", "RULE", "manualSuppressed", false,
                    "sourceOrganizationId", "demo-a", "effectiveFrom", "2026-09-01T00:00:00Z"));
            tx.createLink("PersonHasTag", "legacy-p-" + tag, new EntityKey("Person", "demo-person-004"),
                    assignment.key(), Map.of());
            tx.createLink("TagAssignmentUsesDefinition", "legacy-t-" + tag, assignment.key(),
                    new EntityKey("TagDefinition", tag), Map.of());
            tx.commit();
        }
        assign(unit, tag, key(), assignment("REMOVE", 1, List.of("demo-person-004")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contract").value("RemovePersonTag"));
        var legacy = storage.getObject(CONTEXT, "TagContribution", "legacy-" + id);
        assertEquals("RULE", legacy.properties().get("source"));
        assertEquals("ACTIVE", legacy.properties().get("state"));
        assertEquals("2026-09-01T00:00:00Z", legacy.properties().get("effectiveFrom"));
        assertEquals("SUPPRESSED", storage.getObject(CONTEXT, "PersonTagAssignment", id).properties().get("state"));

        assign(unit, tag, key(), assignment("RESTORE", 2, List.of("demo-person-004")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contract").value("RestorePersonTag"));
        assertEquals("RULE", storage.getObject(CONTEXT, "PersonTagAssignment", id).properties().get("source"));
        assertEquals(1, storage.getEntityHistory(CONTEXT, legacy.key()).size());
        mvc.perform(get("/api/people/demo-person-004/tags/" + tag + "/contributions").session(unit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/people/demo-person-004/tags/" + tag + "/contributions").session(login("reviewer")))
                .andExpect(status().isForbidden());
    }

    @Test
    void oldCommandReceiptRemainsReplayableAfterContractRenaming() throws Exception {
        String tag = create(login("admin"), "");
        var actor = accounts.actor("unit");
        String commandId = key();
        var input = new TagService.AssignmentCommand(List.of("demo-person-005"),
                Map.of("demo-person-005", 0L), "ADD", "", 1);
        // This is the exact old protocol digest, not a receipt for a different payload.
        String fingerprint = BusinessCommands.hash("PersonTagADD/" + json.copy()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(List.of(tag, input)));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("BusinessCommandReceipt", BusinessCommands.hash(actor.username() + "/" + commandId),
                    Map.of("action", "PersonTagADD", "actorId", actor.username(), "requestHash", fingerprint,
                            "responseJson", "{\"legacyResult\":true}"));
            tx.commit();
        }
        assertEquals(Map.of("legacyResult", true), tags.assign(actor, tag, input, commandId));
        assertNull(storage.getObject(CONTEXT, "PersonTagAssignment", TagService.assignmentId("demo-person-005", tag)));
        var changed = new TagService.AssignmentCommand(input.personIds(), input.expectedVersions(), "ADD", "changed", 1);
        assertThrows(BusinessConflict.class, () -> tags.assign(actor, tag, changed, commandId));
    }

    @Test
    void cannotAssignAStaleOrUnpublishedConfigurationVersion() throws Exception {
        var admin = login("admin");
        var unit = login("unit");
        String tag = create(admin, "");
        var version = storage.getObject(CONTEXT, "TagVersion", tag + "-v1");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(version.type(), version.id(), Map.of("status", "DRAFT"), version.version());
            tx.commit();
        }
        assign(unit, tag, key(), assignment("ADD", 0, List.of("demo-person-006")))
                .andExpect(status().isConflict());
        assertNull(storage.getObject(CONTEXT, "PersonTagAssignment", TagService.assignmentId("demo-person-006", tag)));
    }
}
