package gov.objectlibrary.server;

import jakarta.servlet.http.HttpSession;
import org.jsoup.Jsoup;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static gov.objectlibrary.server.DeliveryService.text;

@Service
class ReceiverService {
    private final ReceiverSessions sessions;
    private final ReminderService reminders;
    private final ReminderContentPolicy contentPolicy;
    private final MediaService media;
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final DomainContracts contracts;
    private final Accounts accounts;
    private final ReadingService reading;

    ReceiverService(ReceiverSessions sessions, ReminderService reminders, ReminderContentPolicy contentPolicy,
                    MediaService media, StorageProvider storage, DirectoryService directory, DomainContracts contracts,
                    Accounts accounts, ReadingService reading) {
        this.sessions = sessions;
        this.reminders = reminders;
        this.contentPolicy = contentPolicy;
        this.media = media;
        this.storage = storage;
        this.directory = directory;
        this.contracts = contracts;
        this.accounts = accounts;
        this.reading = reading;
    }

    String issueMock(Accounts.Actor actor, String taskId, String recipientId, HttpSession session) {
        var task = reminders.getTaskForOperator(actor, taskId, true);
        accounts.requirePermission(actor, "REMINDER_WRITE");
        var recipient = storage.getObject(actor.context(), "RecipientRecord", recipientId);
        if (recipient == null || !text(recipient, "taskId").equals(task.id())) throw denied();
        var person = storage.getObject(actor.context(), "Person", text(recipient, "personId"));
        if (person == null || !text(person, "identityReference").startsWith("mock:")) throw denied();
        requirePublished(actor, recipient);
        return sessions.issue(actor, recipientId, session);
    }

    Content content(String grantId, HttpSession session) {
        var grant = sessions.require(grantId, session);
        var recipient = recipient(grant);
        var version = requirePublished(grant.actor(), recipient);
        contracts.authorizeRecipient("ReadOwnReminder");
        contracts.validateInputs(grant.actor(), "ReadOwnReminder", Map.of("recipientId", recipient.id(), "sessionReference", grant.id()));
        String html = contentPolicy.renderStored(text(version, "bodySnapshot"));
        var document = Jsoup.parseBodyFragment(html);
        if (document.text().isBlank() && document.select("img").isEmpty()) throw new BusinessConflict("提醒正文暂不可用，请稍后重新打开");
        for (var image : document.select("img")) {
            String id = image.attr("data-media-id");
            requireMediaMembership(grant, version, id);
            image.attr("src", "/api/receiver/" + grantId + "/media/" + version.id() + "/" + id);
        }
        String proof = sessions.renderProof(grant, version.id(), text(version, "contentDigest"));
        return new Content(text(version, "titleSnapshot"), document.body().html(), version.id(), proof,
                text(recipient, "deadlineAt"), "mock", "本提醒仅向本人展示，请勿截图外传。");
    }

    Map<String, Object> read(String grantId, HttpSession session, String versionId, String renderToken, boolean bodyRendered) {
        if (!bodyRendered) throw new BusinessConflict("正文尚未成功展示，不能记录阅读");
        var grant = sessions.require(grantId, session);
        Runnable authorize = () -> {
            var currentGrant = sessions.require(grantId, session);
            var version = requirePublished(currentGrant.actor(), recipient(currentGrant));
            if (!version.id().equals(versionId)) throw new BusinessConflict("提醒已更新，请重新加载最新正文");
            sessions.validateProof(currentGrant, renderToken, version.id(), text(version, "contentDigest"));
        };
        return reading.recordFirstRead(grant.actor(), grant.recipientId(), versionId,
                "mock-grant:" + BusinessCommands.hash(grantId), authorize);
    }

    MediaService.ImageBytes image(String grantId, HttpSession session, String versionId, String mediaId) throws IOException {
        var grant = sessions.require(grantId, session);
        var version = requirePublished(grant.actor(), recipient(grant));
        if (!version.id().equals(versionId)) throw denied();
        return media.readReceiverVersion(grant.actor(), version, mediaId);
    }

    Map<String, Object> report(String grantId, HttpSession session, String versionId, String category, String mediaId) {
        if (!List.of("PAGE_FAILURE", "CONTENT_FAILURE", "IMAGE_FAILURE").contains(category)) throw new IllegalArgumentException("Invalid category");
        String assetId = mediaId == null ? "" : mediaId;
        var grant = sessions.require(grantId, session);
        if (category.equals("IMAGE_FAILURE") && (versionId == null || versionId.isBlank())) {
            throw new IllegalArgumentException("Image diagnostics require a rendered version");
        }
        String resolvedVersionId = versionId == null || versionId.isBlank()
                ? requirePublished(grant.actor(), recipient(grant)).id() : versionId;
        Runnable authorize = () -> {
            var current = sessions.require(grantId, session);
            var version = requirePublished(current.actor(), recipient(current));
            if (!resolvedVersionId.equals(version.id())) throw denied();
            if (category.equals("IMAGE_FAILURE")) requireMediaMembership(current, version, assetId);
            else if (!assetId.isEmpty()) throw new IllegalArgumentException("Unexpected media ID");
        };
        // One diagnostic per category/asset/version in this short session; not a user-behavior tracker.
        String eventId = BusinessCommands.hash(grantId + "/" + resolvedVersionId + "/" + category + "/" + assetId);
        return reading.reportIssue(grant.actor(), grant.recipientId(), resolvedVersionId, category, eventId, assetId, authorize);
    }

    private ObjectRecord recipient(ReceiverSessions.Grant grant) {
        reminders.getTaskForOperator(grant.issuer(), text(required(grant.actor(), "RecipientRecord", grant.recipientId()), "taskId"), true);
        return required(grant.actor(), "RecipientRecord", grant.recipientId());
    }

    private ObjectRecord requirePublished(Accounts.Actor actor, ObjectRecord recipient) {
        if (text(recipient, "withdrawalState").equals("WITHDRAWN")) throw new BusinessConflict("本提醒已撤回");
        var task = required(actor, "ReminderTask", text(recipient, "taskId"));
        if (text(task, "state").equals("WITHDRAWN")) throw new BusinessConflict("本提醒已撤回");
        String versionId = text(task, "currentPublishedVersionId");
        if (versionId.isEmpty()) throw new BusinessConflict("本提醒尚未发布");
        var version = required(actor, "ReminderTaskVersion", versionId);
        if (!text(version, "state").equals("PUBLISHED") || !text(version, "taskId").equals(task.id())) throw denied();
        boolean member = directory.links(actor, version.key(), "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND)
                .stream().anyMatch(link -> link.to().equals(recipient.key()));
        if (!member) throw denied();
        return version;
    }

    private void requireMediaMembership(ReceiverSessions.Grant grant, ObjectRecord version, String id) {
        if (!id.matches("media-[a-f0-9]{64}") || directory.links(grant.actor(), version.key(), "TaskVersionUsesMedia", StorageProvider.Direction.OUTBOUND)
                .stream().noneMatch(link -> link.to().equals(new EntityKey("MediaAsset", id)))) throw denied();
    }

    private ObjectRecord required(Accounts.Actor actor, String type, String id) {
        var record = storage.getObject(actor.context(), type, id);
        if (record == null || record.isDeleted()) throw denied();
        return record;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("无权访问本提醒");
    }

    record Content(String title, String bodyHtml, String versionId, String renderToken, String deadlineAt,
                   String mode, String notice) {}
}
