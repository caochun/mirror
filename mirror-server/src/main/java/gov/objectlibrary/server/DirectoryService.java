package gov.objectlibrary.server;

import java.util.*;
import org.openfoundry.foundation.spi.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Permission-filtered read projection. Pagination never relies on the SPI's default 100-row window. */
@Service
public class DirectoryService {
    private final StorageProvider storage;
    private final Accounts accounts;
    public DirectoryService(StorageProvider storage, Accounts accounts) { this.storage=storage; this.accounts=accounts; }
    public List<ObjectRecord> all(Accounts.Actor actor, String type) {
        var result = new ArrayList<ObjectRecord>();
        for (int offset=0;;offset+=500) {
            var page=storage.queryObjects(actor.context(),type,new QueryOptions(500,offset,null,null,false));
            result.addAll(page); if (page.size()<500) return result;
        }
    }
    public List<LinkRecord> links(Accounts.Actor actor, EntityKey key, String type, StorageProvider.Direction direction) {
        var result=new ArrayList<LinkRecord>();
        for (int offset=0;;offset+=500) {
            var page=storage.getLinks(actor.context(),key,type,direction,new QueryOptions(500,offset,null,null,false));
            result.addAll(page); if (page.size()<500) return result;
        }
    }
    public Set<String> scope(Accounts.Actor actor) {
        accounts.requirePermission(actor, "PERSON_READ");
        var roots=switch(actor.role()) {
            case "SUPER_ADMIN" -> all(actor,"Organization").stream().map(ObjectRecord::id).toList();
            case "UNIT_ADMIN" -> List.of(actor.organizationId());
            case "AREA_ADMIN" -> accounts.roots(actor.username());
            default -> throw new AccessDeniedException("Not a directory role");
        };
        var seen=new HashSet<String>(); var pending=new ArrayDeque<>(roots);
        while(!pending.isEmpty()) {
            String id=pending.removeFirst(); if (!seen.add(id)) continue;
            links(actor,new EntityKey("Organization",id),"OrganizationParent",StorageProvider.Direction.INBOUND)
                    .forEach(link -> pending.addLast(link.from().id()));
        }
        return seen;
    }
    public List<OrganizationView> organizations(Accounts.Actor actor) {
        var scope=scope(actor);
        return all(actor,"Organization").stream().filter(o->scope.contains(o.id())).map(o -> {
            var parent=links(actor,o.key(),"OrganizationParent",StorageProvider.Direction.OUTBOUND);
            String parentId=parent.isEmpty()?null:parent.getFirst().to().id();
            return new OrganizationView(o.id(),text(o,"name"),scope.contains(parentId)?parentId:null,text(o,"status"));
        }).toList();
    }
    public Page<PersonView> people(Accounts.Actor actor, String query, String organization, String status, int page, int size) {
        if (page<0 || size<1 || size>100) throw new IllegalArgumentException("Invalid pagination");
        var scope=scope(actor);
        if (!organization.isBlank() && !scope.contains(organization)) throw new AccessDeniedException("Outside organization scope");
        // This first projection scans all pages before filtering. A database query projection is required for the 50k acceptance gate.
        var values=all(actor,"Person").stream().map(p->view(actor,p,scope))
                .filter(Objects::nonNull).filter(p->organization.isBlank() || organization.equals(p.organizationId()))
                .filter(p->status.isBlank() || status.equals(p.status()))
                .filter(p->query.isBlank() || p.name().contains(query) || p.employeeNo().contains(query)).toList();
        int start=(int)Math.min((long)page*size,values.size());
        return new Page<>(values.subList(start,Math.min(start+size,values.size())),values.size(),page,size);
    }
    public PersonDetail person(Accounts.Actor actor, String id) {
        var scope=scope(actor); var object=storage.getObject(actor.context(),"Person",id);
        var view=object==null||object.isDeleted()?null:view(actor,object,scope);
        if(view==null) throw new AccessDeniedException("Object unavailable");
        var history=storage.getEntityHistory(actor.context(),object.key()).stream().map(h ->
                new ChangeView(h.version(),h.operation().name(),h.recordedAt().toString(),h.validFrom().toString(),
                        String.valueOf(h.state().getOrDefault("name","")),String.valueOf(h.state().getOrDefault("status","")))).toList();
        return new PersonDetail(view,history);
    }
    private PersonView view(Accounts.Actor actor, ObjectRecord p, Set<String> scope) {
        var current=links(actor,p.key(),"PersonBelongsToOrganization",StorageProvider.Direction.OUTBOUND);
        String orgId=current.size()==1?current.getFirst().to().id():"";
        if (!actor.role().equals("SUPER_ADMIN")&&!scope.contains(orgId)) return null;
        var org=orgId.isBlank()?null:storage.getObject(actor.context(),"Organization",orgId);
        return new PersonView(p.id(),text(p,"name"),text(p,"employeeNo"),text(p,"status"),text(p,"identityStatus"),
                orgId,org==null?"当前单位待核实":text(org,"name"),text(p,"title"),p.version());
    }
    private static String text(ObjectRecord o,String key) {return String.valueOf(o.properties().getOrDefault(key,""));}
    public record Page<T>(List<T> items,int total,int page,int size) {}
    public record OrganizationView(String id,String name,String parentId,String status) {}
    public record PersonView(String id,String name,String employeeNo,String status,String identityStatus,
                             String organizationId,String organizationName,String title,long version) {}
    public record ChangeView(long version,String operation,String recordedAt,String validFrom,String name,String status) {}
    public record PersonDetail(PersonView person,List<ChangeView> history) {}
}
