package gov.objectlibrary.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.EntityKey;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceRestartTest {
    @TempDir Path temporary;
    @Test void fileDatabaseKeepsAccountsObjectsAndHistoryAcrossApplicationRestart() {
        var context=RequestContext.system("mirror","admin");
        var command=new TagService.AssignmentCommand(List.of("persisted-person"),Map.of("persisted-person",0L),"ADD","",1);
        String[] args={"--server.port=0", "--spring.datasource.url=jdbc:h2:file:"+temporary.resolve("mirror"),
                "--mirror.pack=../domain-pack", "--mirror.bootstrap-password=RestartTest-Password-123", "--mirror.demo=false",
                "--logging.level.root=WARN", "--debug=false"};
        try(var app=new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var storage=app.getBean(StorageProvider.class);
            try(var tx=storage.beginTransaction(context)) {
                tx.createObject("Person","persisted-person",Map.of("name","持久化测试","status","ACTIVE"));
                tx.createLink("PersonBelongsToOrganization","persisted-org",new EntityKey("Person","persisted-person"),
                        new EntityKey("Organization","city"),Map.of("startedAt","2026-01-01T00:00:00Z"));tx.commit();
            }
            var actor=app.getBean(Accounts.class).actor("admin");
            var tags=app.getBean(TagService.class);
            tags.create(actor,new TagService.CreateTag("RESTART_TAG","持久化标签","","PERSON",""),"restart-create-command");
            tags.assign(actor,"tag-restart_tag",command,"restart-assign-command");
        }
        try(var app=new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var storage=app.getBean(StorageProvider.class);
            assertEquals("持久化测试",storage.getObject(context,"Person","persisted-person").properties().get("name"));
            assertEquals(1,storage.getEntityHistory(context,new EntityKey("Person","persisted-person")).size());
            assertEquals("SUPER_ADMIN",app.getBean(Accounts.class).actor("admin").role());
            app.getBean(TagService.class).assign(app.getBean(Accounts.class).actor("admin"),"tag-restart_tag",command,"restart-assign-command");
            assertEquals(1,storage.getEntityHistory(context,new EntityKey("PersonTagAssignment",
                    TagService.assignmentId("persisted-person","tag-restart_tag"))).size());
        }
    }
}
