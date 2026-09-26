package gov.objectlibrary.server;

import java.time.Instant;
import java.util.*;
import org.openfoundry.foundation.spi.*;
import org.springframework.stereotype.Service;

@Service
public class TagService {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final Accounts accounts;
    private final BusinessCommands commands;
    public TagService(StorageProvider storage,DirectoryService directory,Accounts accounts,BusinessCommands commands) {
        this.storage=storage;this.directory=directory;this.accounts=accounts;this.commands=commands;
    }
    public List<TagView> tags(Accounts.Actor actor) {
        accounts.requirePermission(actor,"PERSON_READ");
        var tags=directory.all(actor,"TagDefinition");
        return tags.stream().map(tag->view(tag,tags)).sorted(Comparator.comparing(TagView::code)).toList();
    }
    public Map<String,Object> create(Accounts.Actor actor,CreateTag input,String key) {
        return commands.execute(actor,"CreateTag",key,input,()->accounts.requirePermission(actor,"TAG_CONFIGURE"),tx->{
            String id="tag-"+input.code().toLowerCase(Locale.ROOT);
            if(storage.getObject(actor.context(),"TagDefinition",id)!=null) throw new BusinessConflict("标签编码已存在");
            int level=1;
            if(!input.parentId().isBlank()) {
                var parent=require(actor,"TagDefinition",input.parentId());
                level=((Number)parent.properties().get("level")).intValue()+1;
                if(level>3 || !"ACTIVE".equals(text(parent,"status"))) throw new BusinessConflict("只能在启用的一级或二级目录下创建标签");
                if(!input.dimension().equals(text(parent,"dimension"))) throw new BusinessConflict("子标签维度须与父目录一致");
                if(directory.all(actor,"PersonTagAssignment").stream().anyMatch(a->input.parentId().equals(text(a,"tagDefinitionId"))))
                    throw new BusinessConflict("已用于人员赋标的末级标签不能改为分类目录");
            }
            String versionId=id+"-v1";
            var properties=new HashMap<String,Object>();
            properties.putAll(Map.of("code",input.code(),"name",input.name(),"scope","LONG_TERM","status","ACTIVE",
                    "level",level,"parentId",input.parentId(),"dimension",input.dimension(),"description",input.description(),"currentVersionId",versionId));
            tx.createObject("TagDefinition",id,properties);
            snapshot(tx,id,versionId,1,input.name(),input.description());
            return Map.of("id",id,"version",1,"name",input.name());
        });
    }
    public Map<String,Object> edit(Accounts.Actor actor,String id,EditTag input,String key) {
        return commands.execute(actor,"EditTag",key,List.of(id,input),()->accounts.requirePermission(actor,"TAG_CONFIGURE"),tx->{
            var tag=require(actor,"TagDefinition",id);
            checkVersion(tag,input.expectedVersion());
            var tags=directory.all(actor,"TagDefinition");
            if(input.status().equals("ACTIVE") && !text(tag,"parentId").isBlank()
                    && !"ACTIVE".equals(text(require(actor,"TagDefinition",text(tag,"parentId")),"status")))
                throw new BusinessConflict("请先启用父目录");
            String versionId=id+"-v"+(tag.version()+1);
            tx.updateObject("TagDefinition",id,Map.of("name",input.name(),"description",input.description(),
                    "status",input.status(),"currentVersionId",versionId),tag.version());
            snapshot(tx,id,versionId,tag.version()+1,input.name(),input.description());
            var affected=new HashSet<String>();affected.add(id);
            if(input.status().equals("INACTIVE")) {
                boolean changed;
                do {changed=false;for(var child:tags) if(affected.contains(text(child,"parentId"))) changed|=affected.add(child.id());} while(changed);
                for(var child:tags) if(!child.id().equals(id)&&affected.contains(child.id())&&"ACTIVE".equals(text(child,"status"))) {
                    String childVersionId=child.id()+"-v"+(child.version()+1);
                    tx.updateObject("TagDefinition",child.id(),Map.of("status","INACTIVE","currentVersionId",childVersionId),child.version());
                    snapshot(tx,child.id(),childVersionId,child.version()+1,text(child,"name"),text(child,"description"));
                }
                for(var assignment:directory.all(actor,"PersonTagAssignment"))
                    if(affected.contains(text(assignment,"tagDefinitionId"))&&"ACTIVE".equals(text(assignment,"state")))
                        tx.updateObject(assignment.type(),assignment.id(),Map.of("state","EXPIRED","effectiveTo",Instant.now().toString(),
                                "operatorId",actor.username(),"operatorOrganizationId",actor.organizationId(),"note","标签目录停用"),assignment.version());
            }
            return Map.of("id",id,"version",tag.version()+1,"affectedTagIds",affected.stream().sorted().toList());
        });
    }
    public List<AssignmentView> personTags(Accounts.Actor actor,String personId) {
        directory.person(actor,personId);
        return directory.links(actor,new EntityKey("Person",personId),"PersonHasTag",StorageProvider.Direction.OUTBOUND).stream()
                .map(l->storage.getObject(actor.context(),"PersonTagAssignment",l.to().id())).filter(Objects::nonNull)
                .map(a->assignmentView(actor,a)).toList();
    }
    public List<TagChange> history(Accounts.Actor actor,String personId,String tagId) {
        directory.person(actor,personId);
        return storage.getEntityHistory(actor.context(),new EntityKey("PersonTagAssignment",assignmentId(personId,tagId))).stream()
                .map(h->new TagChange(h.version(),String.valueOf(h.state().get("tagNameSnapshot")),String.valueOf(h.state().get("state")),
                        String.valueOf(h.state().get("source")),String.valueOf(h.state().get("operatorId")),
                        String.valueOf(h.state().getOrDefault("operatorOrganizationId",h.state().getOrDefault("sourceOrganizationId",""))),h.recordedAt().toString(),String.valueOf(h.state().getOrDefault("note","")))).toList();
    }
    public Map<String,Object> assign(Accounts.Actor actor,String tagId,AssignmentCommand input,String key) {
        Runnable authorize=()->{
            accounts.requirePermission(actor,"PERSON_TAG_WRITE");
            for(String person:input.personIds()) directory.person(actor,person);
        };
        return commands.execute(actor,"PersonTag"+input.operation(),key,List.of(tagId,input),authorize,tx->{
            var tag=require(actor,"TagDefinition",tagId);checkVersion(tag,input.tagVersion());
            if(!"ACTIVE".equals(text(tag,"status"))) throw new BusinessConflict("标签已停用，请刷新目录");
            if(directory.all(actor,"TagDefinition").stream().anyMatch(t->tagId.equals(text(t,"parentId"))))
                throw new BusinessConflict("只有末级标签可以赋给人员");
            if(new HashSet<>(input.personIds()).size()!=input.personIds().size()
                    || !input.expectedVersions().keySet().equals(new HashSet<>(input.personIds()))) throw new IllegalArgumentException("Invalid person versions");
            List<Map<String,Object>> results=new ArrayList<>();
            for(String personId:input.personIds()) {
                var person=require(actor,"Person",personId);
                if(!"ACTIVE".equals(text(person,"status"))) throw new BusinessConflict("名单包含已停用人员");
                var orgLinks=directory.links(actor,person.key(),"PersonBelongsToOrganization",StorageProvider.Direction.OUTBOUND);
                if(orgLinks.size()!=1 || !"ACTIVE".equals(text(require(actor,"Organization",orgLinks.getFirst().to().id()),"status")))
                    throw new BusinessConflict("人员当前单位未唯一确定或已停用");
                if(directory.links(actor,person.key(),"EligibilityForPerson",StorageProvider.Direction.INBOUND).stream()
                        .map(l->require(actor,"ObjectEligibility",l.from().id())).anyMatch(e->text(e,"state").startsWith("NON_OBJECT")))
                    throw new BusinessConflict("非对象账号不能赋标");
                String id=assignmentId(personId,tagId);
                var existing=storage.getObject(actor.context(),"PersonTagAssignment",id);
                long expected=input.expectedVersions().get(personId);
                if((existing==null?0:existing.version())!=expected) throw new BusinessConflict("人员标签已变化，请刷新后重试");
                if(existing==null && input.operation().equals("REMOVE")) throw new BusinessConflict("人员尚未具有该标签");
                boolean remove=input.operation().equals("REMOVE");
                if(existing!=null && text(existing,"state").equals(remove?"REMOVED":"ACTIVE")) {
                    results.add(Map.of("personId",personId,"assignmentId",id,"version",expected,"state",text(existing,"state"),"unchanged",true));
                    continue;
                }
                String now=Instant.now().toString();
                var properties=new HashMap<String,Object>();
                properties.putAll(Map.of("state",remove?"REMOVED":"ACTIVE","manualSuppressed",remove,
                        "tagVersion",text(tag,"currentVersionId"),"tagNameSnapshot",text(tag,"name"),"note",input.note(),
                        "operatorId",actor.username(),"operatorOrganizationId",actor.organizationId()));
                properties.put("effectiveTo",remove?now:null);
                if(!remove) properties.put("effectiveFrom",now);
                if(existing==null) {
                    properties.putAll(Map.of("personId",personId,"tagDefinitionId",tagId,"source","MANUAL","sourceOrganizationId",actor.organizationId()));
                    tx.createObject("PersonTagAssignment",id,properties);
                    tx.createLink("PersonHasTag","person-tag-"+id,person.key(),new EntityKey("PersonTagAssignment",id),Map.of("linkedAt",now));
                    tx.createLink("TagAssignmentUsesDefinition","definition-"+id,new EntityKey("PersonTagAssignment",id),tag.key(),Map.of());
                } else tx.updateObject(existing.type(),id,properties,existing.version());
                results.add(Map.of("personId",personId,"assignmentId",id,"version",expected+1,"state",remove?"REMOVED":"ACTIVE"));
            }
            return Map.of("tagId",tagId,"operation",input.operation(),"results",results);
        });
    }
    private AssignmentView assignmentView(Accounts.Actor actor,ObjectRecord a) {
        var tag=storage.getObject(actor.context(),"TagDefinition",text(a,"tagDefinitionId"));
        return new AssignmentView(a.id(),text(a,"tagDefinitionId"),tag==null?text(a,"tagNameSnapshot"):text(tag,"name"),
                text(a,"tagNameSnapshot"),text(a,"state"),text(a,"source"),Boolean.TRUE.equals(a.properties().get("manualSuppressed")),
                text(a,"effectiveFrom"),text(a,"effectiveTo"),text(a,"tagVersion"),text(a,"note"),a.version());
    }
    private static TagView view(ObjectRecord tag,List<ObjectRecord> all) {
        return new TagView(tag.id(),text(tag,"code"),text(tag,"name"),text(tag,"parentId"),text(tag,"dimension"),
                ((Number)tag.properties().get("level")).intValue(),text(tag,"status"),text(tag,"description"),tag.version(),
                all.stream().noneMatch(t->tag.id().equals(text(t,"parentId"))));
    }
    private void snapshot(Transaction tx,String id,String versionId,long version,String name,String description) {
        tx.createObject("TagVersion",versionId,Map.of("tagDefinitionId",id,"version",Long.toString(version),"status","PUBLISHED",
                "publishedAt",Instant.now().toString(),"nameSnapshot",name,"descriptionSnapshot",description));
    }
    private ObjectRecord require(Accounts.Actor actor,String type,String id) {
        var value=storage.getObject(actor.context(),type,id);
        if(value==null||value.isDeleted()) throw new BusinessConflict("业务对象不存在或已删除，请刷新");
        return value;
    }
    private static void checkVersion(ObjectRecord o,long expected) {if(o.version()!=expected) throw new BusinessConflict("标签配置已变化，请刷新后重试");}
    private static String text(ObjectRecord o,String key) {var v=o.properties().get(key);return v==null?"":v.toString();}
    static String assignmentId(String person,String tag) {return "assignment-"+BusinessCommands.hash(person+"/"+tag);}

    public record TagView(String id,String code,String name,String parentId,String dimension,int level,String status,String description,long version,boolean leaf) {}
    public record AssignmentView(String id,String tagId,String name,String nameSnapshot,String state,String source,boolean manualSuppressed,
                                 String effectiveFrom,String effectiveTo,String tagVersion,String note,long version) {}
    public record TagChange(long version,String name,String state,String source,String actorId,String organizationId,String recordedAt,String note) {}
    public record CreateTag(String code,String name,String parentId,String dimension,String description) {}
    public record EditTag(String name,String description,String status,long expectedVersion) {}
    public record AssignmentCommand(List<String> personIds,Map<String,Long> expectedVersions,String operation,String note,long tagVersion) {}
}
