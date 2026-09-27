package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ContentLibraryService {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final Accounts accounts;
    private final BusinessCommands commands;
    private final DomainContracts contracts;
    private final ReminderContentPolicy content;
    private final Clock clock;

    public ContentLibraryService(StorageProvider storage, DirectoryService directory, Accounts accounts,
                                 BusinessCommands commands, DomainContracts contracts, ReminderContentPolicy content, Clock clock) {
        this.storage = storage;
        this.directory = directory;
        this.accounts = accounts;
        this.commands = commands;
        this.contracts = contracts;
        this.content = content;
        this.clock = clock;
    }

    public List<Example> list(Accounts.Actor actor) {
        requireReader(actor);
        boolean configure = accounts.permissions(actor).contains("CONTENT_CONFIGURE");
        return directory.all(actor, "ReminderContent").stream()
                .filter(c -> configure || text(c, "state").equals("ENABLED"))
                .map(c -> view(actor, c)).toList();
    }

    public Example get(Accounts.Actor actor, String id) {
        requireReader(actor);
        var example = require(actor, "ReminderContent", id);
        if (!accounts.permissions(actor).contains("CONTENT_CONFIGURE") && !text(example, "state").equals("ENABLED")) {
            throw new AccessDeniedException("内容示例未启用");
        }
        return view(actor, example);
    }

    public ObjectRecord requireSelectableVersion(Accounts.Actor actor, String versionId) {
        requireReader(actor);
        var version = require(actor, "ContentVersion", versionId);
        var example = require(actor, "ReminderContent", text(version, "contentId"));
        if (!text(example, "state").equals("ENABLED") || !versionId.equals(text(example, "currentVersionId"))) {
            throw new BusinessConflict("内容示例已更新或停用，请重新选择");
        }
        return version;
    }

    public Map<String, Object> save(Accounts.Actor actor, String id, Draft draft, String key) {
        if (draft.title() == null || draft.title().isBlank() || draft.title().length() > 120
                || draft.category() == null || draft.category().isBlank() || draft.category().length() > 100
                || draft.tagIds() == null || draft.tagIds().size() > 100 || draft.expectedVersion() < 0) {
            throw new IllegalArgumentException("Invalid content example");
        }
        String action = "SaveContentExample";
        String contentId = id == null ? "content-" + UUID.randomUUID() : id;
        return commands.executeDefined(actor, action, key, List.of(id == null ? "new" : id, draft),
                () -> contracts.authorize(actor, action), tx -> {
            var previous = id == null ? null : require(actor, "ReminderContent", id);
            if ((previous == null ? 0 : previous.version()) != draft.expectedVersion()) throw new BusinessConflict("内容示例已变化，请刷新");
            var inspected = content.inspect(actor, draft.bodyHtml());
            var tagIds = draft.tagIds().stream().distinct().sorted().toList();
            contracts.validateInputs(actor, action, Map.of("contentId", contentId, "expectedVersion", draft.expectedVersion(),
                    "title", draft.title(), "body", Map.of("html", inspected.html()), "category", draft.category(), "tagIds", tagIds));
            for (String tagId : tagIds) {
                var tag = require(actor, "TagDefinition", tagId);
                if (!"ACTIVE".equals(text(tag, "status"))) throw new BusinessConflict("适用标签已停用");
            }
            long sequence = previous == null ? 1 : previous.version() + 1;
            String versionId = contentId + "-v" + sequence;
            var values = new HashMap<String, Object>();
            values.putAll(Map.of("title", draft.title().strip(), "category", draft.category(),
                    "state", previous == null ? "DRAFT" : text(previous, "state"), "currentVersionId", versionId));
            ObjectRecord example = previous == null ? tx.createObject("ReminderContent", contentId, values)
                    : tx.updateObject("ReminderContent", contentId, values, previous.version());
            var version = tx.createObject("ContentVersion", versionId, Map.of("contentId", contentId, "version", Long.toString(sequence),
                    "title", draft.title().strip(), "body", inspected.html(), "state", "SAVED", "createdAt", clock.instant().toString(),
                    "categorySnapshot", draft.category(), "contentDigest", BusinessCommands.hash(inspected.html()),
                    "mediaManifestDigest", inspected.mediaDigest()));
            tx.createLink("ContentHasVersion", "version-" + versionId, example.key(), version.key(), Map.of());
            for (String mediaId : inspected.images().stream().map(ReminderContentPolicy.ImageReference::id).distinct().toList()) {
                tx.createLink("VersionUsesMedia", versionId + "-" + mediaId, version.key(), new EntityKey("MediaAsset", mediaId), Map.of());
            }
            for (var old : directory.links(actor, example.key(), "ContentSuggestsTag", StorageProvider.Direction.OUTBOUND)) {
                tx.deleteLink(old.type(), old.id(), old.version());
            }
            for (String tagId : tagIds) {
                tx.createLink("ContentSuggestsTag", versionId + "-" + tagId, example.key(), new EntityKey("TagDefinition", tagId), Map.of());
            }
            return Map.of("id", example.id(), "version", example.version(), "contentVersionId", version.id(), "state", values.get("state"));
        }, contracts.eventType(action));
    }

    public Map<String, Object> availability(Accounts.Actor actor, String id, long expectedVersion, String state, String key) {
        if (!Set.of("ENABLED", "DISABLED").contains(state)) throw new IllegalArgumentException("Invalid content status");
        String action = "SetContentAvailability";
        return commands.executeDefined(actor, action, key, List.of(id, expectedVersion, state), () -> contracts.authorize(actor, action), tx -> {
            var previous = require(actor, "ReminderContent", id);
            if (previous.version() != expectedVersion) throw new BusinessConflict("内容示例已变化，请刷新");
            contracts.validateInputs(actor, action, Map.of("contentId", id, "state", state, "expectedVersion", expectedVersion));
            contracts.requireTransition("ReminderContent", "state", text(previous, "state"), state, action);
            var saved = tx.updateObject(previous.type(), id, Map.of("state", state), previous.version());
            return Map.of("id", id, "version", saved.version(), "state", state);
        }, contracts.eventType(action));
    }

    private Example view(Accounts.Actor actor, ObjectRecord example) {
        var version = require(actor, "ContentVersion", text(example, "currentVersionId"));
        var tags = directory.links(actor, example.key(), "ContentSuggestsTag", StorageProvider.Direction.OUTBOUND)
                .stream().map(l -> l.to().id()).toList();
        boolean used = directory.links(actor, example.key(), "ContentHasVersion", StorageProvider.Direction.OUTBOUND).stream()
                .anyMatch(link -> !directory.links(actor, link.to(), "TaskVersionFromContent", StorageProvider.Direction.INBOUND).isEmpty());
        return new Example(example.id(), example.version(), text(example, "title"), text(example, "category"), text(example, "state"),
                version.id(), content.renderStored(text(version, "body")), tags, used);
    }

    private void requireReader(Accounts.Actor actor) {
        var permissions = accounts.permissions(actor);
        if (!permissions.contains("REMINDER_WRITE") && !permissions.contains("CONTENT_CONFIGURE")) {
            throw new AccessDeniedException("无权浏览内容示例库");
        }
    }

    private ObjectRecord require(Accounts.Actor actor, String type, String id) {
        var record = storage.getObject(actor.context(), type, id);
        if (record == null || record.isDeleted()) throw new BusinessConflict("内容记录不存在或已删除");
        return record;
    }

    private static String text(ObjectRecord record, String key) {
        Object value = record.properties().get(key);
        return value == null ? "" : value.toString();
    }

    public record Draft(long expectedVersion, String title, String category, String bodyHtml, List<String> tagIds) {}
    public record Example(String id, long version, String title, String category, String state,
                          String contentVersionId, String bodyHtml, List<String> tagIds, boolean used) {}
}
