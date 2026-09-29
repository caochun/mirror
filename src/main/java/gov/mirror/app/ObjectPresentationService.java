package gov.mirror.app;

import org.openfoundry.foundation.spi.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Business descriptions for the read-only object browser. Facts continue to live in Foundry. */
@Service
public final class ObjectPresentationService {
    private final FoundryRuntime runtime;
    private final MirrorAccounts accounts;
    private final PersonnelService personnel;

    public ObjectPresentationService(FoundryRuntime runtime, MirrorAccounts accounts, PersonnelService personnel) {
        this.runtime = runtime;
        this.accounts = accounts;
        this.personnel = personnel;
    }

    public record Fact(String label, String value, EntityKey target) {}
    public record Presentation(String title, String summary, List<Fact> facts) {}
    public record PresentedObject(ObjectRecord object, Presentation presentation) {}

    public PresentedObject get(String type, String id) {
        requireType(type);
        var object = runtime.application().getObject(accounts.context(), accounts.principal(), type, id);
        if (object == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Object is unavailable");
        var result = new PresentedObject(object, new Description().describe(object));
        runtime.application().requireCurrentSchema(accounts.context(), accounts.principal());
        return result;
    }

    public List<PresentedObject> list(String type, int offset, int limit) {
        requireType(type);
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid object pagination");
        var description = new Description();
        var result = runtime.application().listObjects(accounts.context(), accounts.principal(), type,
                        new QueryOptions(limit, offset, null, null, false)).stream()
                .map(object -> new PresentedObject(object, description.describe(object))).toList();
        runtime.application().requireCurrentSchema(accounts.context(), accounts.principal());
        return result;
    }

    public Presentation describe(ObjectRecord object) {
        accounts.requireAdmin();
        return object == null ? null : new Description().describe(object);
    }

    private void requireType(String type) {
        accounts.requireAdmin();
        if (runtime.pack().ontology().schema().objectTypes().stream().noneMatch(object -> object.name().equals(type))) {
            throw new IllegalArgumentException("Unknown object type");
        }
    }

    private record Related(EntityKey source, String type) {}

    private final class Description {
        private final Map<Related, ObjectRecord> related = new HashMap<>();

        Presentation describe(ObjectRecord object) {
            return switch (object.type()) {
                case "Person" -> person(object);
                case "Appointment" -> appointment(object);
                case "ObjectMembership" -> membership(object);
                case "PersonTag" -> personTag(object);
                case "TagContribution" -> contribution(object);
                case "ExternalIdentity" -> identity(object);
                case "Tag" -> tag(object);
                case "TagVersion" -> new Presentation(name(object, "标签名称待补充") + " · 第" + value(object, "revision", "?") + "版",
                        value(object, "description", "此版本保存发布时的标签名称和定义。"), List.of());
                case "Organization" -> new Presentation(name(object, "单位名称待补充"), "单位信息及其关联人员、任职和下级单位。",
                        List.of(fact("上级单位", related(object, "OrganizationParent")),
                                new Fact("单位状态", sourceState(object), null)));
                case "Position" -> new Presentation(name(object, "岗位名称待补充"), "这是岗位定义，可通过任职记录查看担任此岗位的人员。", List.of());
                default -> new Presentation(value(object, "name", value(object, "title", null)), "", List.of());
            };
        }

        private Presentation person(ObjectRecord object) {
            var organization = related(object, "PersonCurrentOrganization");
            return new Presentation(name(object, "人员姓名待补充"),
                    "当前所属单位：" + name(organization, "尚未确认") + "。下方可查看该人员的任职、管理状态、标签和数据来源。",
                    List.of(fact("所属单位", organization), new Fact("个人职级", value(object, "personalRankCode", "未填写"), null)));
        }

        private Presentation appointment(ObjectRecord object) {
            var person = related(object, "AppointmentPerson");
            var organization = related(object, "AppointmentOrganization");
            var position = related(object, "AppointmentPosition");
            String who = name(person, "人员待确认");
            String where = name(organization, "任职单位待确认");
            String job = name(position, "岗位待确认");
            String state = value(object, "status", "");
            String status = state.equals("CURRENT") ? "目前在任" : state.equals("ENDED") ? "该任职已结束" : "任职状态待确认";
            return new Presentation(who + "的任职 · " + job + "（" + where + "）",
                    who + "在" + where + "担任“" + job + "”岗位，" + status + "。",
                    List.of(fact("人员", person), fact("任职单位", organization), fact("岗位", position),
                            new Fact("任职状态", status, null), new Fact("开始日期", value(object, "startedOn", "未填写"), null),
                            new Fact("结束日期", value(object, "endedOn", "未填写"), null)));
        }

        private Presentation membership(ObjectRecord object) {
            var person = related(object, "MembershipPerson");
            String status = membershipStatus(value(object, "status", ""));
            return new Presentation(name(person, "人员待确认") + "的管理状态", "在对象库中的管理状态：" + status + "。这不代表登录账号的启用或停用状态。",
                    List.of(fact("人员", person), new Fact("管理状态", status, null),
                            fact("调整单位", related(object, "MembershipDecisionOrganization")),
                            new Fact("调整说明", value(object, "note", "未填写"), null)));
        }

        private Presentation personTag(ObjectRecord object) {
            var person = related(object, "PersonTagPerson");
            var tag = related(object, "PersonTagTag");
            String name = tagName(tag);
            boolean cancelled = "SUPPRESSED".equals(value(object, "suppression", ""));
            String state = cancelled ? "已人工取消" : person != null && personnel.effective(person, object) ? "当前生效" : "当前不生效";
            return new Presentation(name(person, "人员待确认") + "的“" + name + "”标签",
                    "此标签" + state + "。人员标签记录用于关联标签与各项赋标依据，记录保留不等于标签持续生效。",
                    List.of(fact("人员", person), new Fact("标签", name, tag == null ? null : tag.key()),
                            new Fact("当前效果", state, null)));
        }

        private Presentation contribution(ObjectRecord object) {
            var association = related(object, "ContributionForPersonTag");
            var person = related(association, "PersonTagPerson");
            var version = related(object, "ContributionTagVersion");
            String kind = switch (value(object, "kind", "")) {
                case "MANUAL" -> "人工赋标";
                case "RULE" -> "规则判断";
                case "AI_REVIEW" -> "AI复核";
                case "MATTER" -> "专项事项";
                case "RISK" -> "风险线索";
                default -> "赋标";
            };
            String state = switch (value(object, "status", "")) {
                case "ACTIVE" -> "保留中（是否生效还需结合有效期和人员标签状态）";
                case "ENDED" -> "已结束";
                default -> "待核实";
            };
            String tagName = name(version, "标签待确认");
            return new Presentation(name(person, "人员待确认") + "的“" + tagName + "”" + kind + "依据",
                    value(object, "reason", "尚未填写赋标说明。"),
                    List.of(new Fact("依据类型", kind, null), new Fact("依据状态", state, null),
                            fact("来源单位", related(object, "ContributionOrganization")),
                            new Fact("赋标时的标签版本", tagName + " · 第" + value(version, "revision", "?") + "版", version == null ? null : version.key())));
        }

        private Presentation identity(ObjectRecord object) {
            var person = related(object, "IdentityPerson");
            String source = value(object, "sourceSystem", "来源待确认");
            if (source.equals("MOCK_REFERENCE")) source = "演示来源";
            return new Presentation(name(person, "人员待确认") + "的来源身份 · " + source,
                    "用于关联来源系统中的人员记录。来源记录状态独立于对象库管理状态。",
                    List.of(fact("人员", person), new Fact("数据来源", source, null), new Fact("来源状态", sourceState(object), null)));
        }

        private Presentation tag(ObjectRecord object) {
            var version = related(object, "TagCurrentVersion");
            var parent = related(object, "TagParent");
            return new Presentation(tagName(object), value(version, "description", "标签目录中的一个标签。"),
                    List.of(new Fact("目录状态", "ENABLED".equals(value(object, "status", "")) ? "已启用" : "已停用", null),
                            new Fact("上级标签", parent == null ? "未关联" : tagName(parent), parent == null ? null : parent.key())));
        }

        private ObjectRecord related(ObjectRecord source, String type) {
            if (source == null) return null;
            var key = new Related(source.key(), type);
            if (related.containsKey(key)) return related.get(key);
            var links = runtime.storage().getLinks(accounts.context(), source.key(), type, StorageProvider.Direction.OUTBOUND,
                    new QueryOptions(2, 0, null, null, false));
            ObjectRecord target = null;
            if (links.size() == 1) {
                var to = links.getFirst().to();
                target = runtime.application().getObject(accounts.context(), accounts.principal(), to.type(), to.id());
            }
            related.put(key, target);
            return target;
        }

        private String tagName(ObjectRecord tag) {
            return name(related(tag, "TagCurrentVersion"), "标签名称待补充");
        }
    }

    private static Fact fact(String label, ObjectRecord object) {
        return new Fact(label, name(object, "未关联或不可查看"), object == null ? null : object.key());
    }

    private static String name(ObjectRecord object, String fallback) {
        return value(object, "name", fallback);
    }

    private static String value(ObjectRecord object, String property, String fallback) {
        return object == null || object.properties().get(property) == null ? fallback : object.properties().get(property).toString();
    }

    private static String sourceState(ObjectRecord object) {
        return "ACTIVE".equals(value(object, "sourceStatus", "")) ? "有效" : "已停用";
    }

    private static String membershipStatus(String status) {
        return switch (status) {
            case "IN_SCOPE" -> "正常管理";
            case "EXCLUDED" -> "非管理对象";
            case "SUSPENDED" -> "暂停管理";
            default -> "待确认";
        };
    }
}
