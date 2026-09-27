package gov.objectlibrary.server;

import java.time.Instant;
import java.util.*;
import org.openfoundry.foundation.spi.*;
import org.springframework.stereotype.Service;

@Service
public class TagService {
    private final StorageProvider storage;
    private final TagActivity activity;
    private final DirectoryService directory;
    private final Accounts accounts;
    private final BusinessCommands commands;
    private final PersonTagCommands personTags;
    private final RuleDeactivationService deactivations;

    public TagService(StorageProvider storage, TagActivity activity, DirectoryService directory, Accounts accounts,
                      BusinessCommands commands, PersonTagCommands personTags, RuleDeactivationService deactivations) {
        this.storage = storage;
        this.activity = activity;
        this.directory = directory;
        this.accounts = accounts;
        this.commands = commands;
        this.personTags = personTags;
        this.deactivations = deactivations;
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
                for (var rule : directory.all(actor, "TagRule")) {
                    if (affected.contains(text(rule, "tagDefinitionId")) && "ACTIVE".equals(text(rule, "status"))) {
                        deactivations.enqueue(tx, actor, rule);
                    }
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
    public Map<String, Object> assign(Accounts.Actor actor, String tagId, AssignmentCommand input, String key) {
        return personTags.execute(actor, tagId, input, key);
    }

    private AssignmentView assignmentView(Accounts.Actor actor,ObjectRecord a) {
        var tag=storage.getObject(actor.context(),"TagDefinition",text(a,"tagDefinitionId"));
        return new AssignmentView(a.id(),text(a,"tagDefinitionId"),tag==null?text(a,"tagNameSnapshot"):text(tag,"name"),
                text(a,"tagNameSnapshot"),activity.state(actor,a),text(a,"source"),Boolean.TRUE.equals(a.properties().get("manualSuppressed")),
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
