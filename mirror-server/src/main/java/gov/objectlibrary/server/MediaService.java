package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Immutable local blob storage behind per-object authorization; never mounted as static files. */
@Service
public class MediaService {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final Accounts accounts;
    private final BusinessCommands commands;
    private final DomainContracts contracts;
    private final Clock clock;
    private final Path root;

    public MediaService(StorageProvider storage, DirectoryService directory, Accounts accounts,
                        BusinessCommands commands, DomainContracts contracts, Clock clock,
                        @Value("${mirror.media-directory:./.runtime/media}") String root) {
        this.storage = storage;
        this.directory = directory;
        this.accounts = accounts;
        this.commands = commands;
        this.contracts = contracts;
        this.clock = clock;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Map<String, Object> upload(Accounts.Actor actor, MultipartFile file, String commandId) throws IOException {
        contracts.authorize(actor, "RegisterMediaAsset");
        if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024) throw new BusinessConflict("图片须为不超过2MB的JPG或PNG");
        byte[] source = file.getBytes();
        String sourceDigest = hash(source);
        byte[] normalized;
        int width;
        int height;
        String format;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessConflict("无法识别有效图片");
            var reader = readers.next();
            try {
                format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) throw new BusinessConflict("仅支持JPG和PNG图片");
                reader.setInput(input);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 20_000_000) {
                    throw new BusinessConflict("图片尺寸过大，请缩小后上传");
                }
                var image = reader.read(0);
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, format, output)) throw new BusinessConflict("图片无法规范化保存");
                normalized = output.toByteArray();
                if (normalized.length > 2 * 1024 * 1024) throw new BusinessConflict("图片规范化后超过2MB，请压缩后上传");
            } finally { reader.dispose(); }
        } catch (IOException invalid) {
            throw new BusinessConflict("图片损坏或格式不正确");
        }
        String id = "media-" + BusinessCommands.hash(actor.username() + "/" + commandId);
        String digest = hash(normalized);
        String mime = "image/" + format;
        Path path = path(actor, id);
        var request = Map.of("sourceDigest", sourceDigest, "mimeType", mime, "sizeBytes", normalized.length);
        int imageWidth = width;
        int imageHeight = height;
        return commands.executeDefined(actor, "RegisterMediaAsset", commandId, request,
                () -> contracts.authorize(actor, "RegisterMediaAsset"), tx -> {
            contracts.validateInputs(actor, "RegisterMediaAsset", Map.of("mediaId", id, "mimeType", mime,
                    "sizeBytes", normalized.length, "digest", digest, "storageReference", id));
            writeImmutable(path, normalized);
            var properties = new java.util.HashMap<String, Object>();
            properties.putAll(Map.of("mediaType", "IMAGE", "storageKey", id, "state", "ACTIVE",
                    "createdAt", clock.instant().toString(), "mimeType", mime, "sizeBytes", normalized.length,
                    "contentHash", digest, "width", imageWidth, "height", imageHeight, "uploadedBy", actor.username()));
            properties.put("organizationId", actor.organizationId());
            tx.createObject("MediaAsset", id, properties);
            return Map.of("id", id, "digest", digest, "mimeType", mime, "sizeBytes", normalized.length,
                    "width", imageWidth, "height", imageHeight);
        }, contracts.eventType("RegisterMediaAsset"));
    }

    public Asset metadata(Accounts.Actor actor, String id) {
        ObjectRecord asset = required(actor, id);
        requireAccess(actor, asset);
        return asset(asset);
    }

    public ImageBytes read(Accounts.Actor actor, String id) throws IOException {
        Asset asset = metadata(actor, id);
        byte[] bytes = Files.readAllBytes(path(actor, id));
        if (!hash(bytes).equals(asset.digest())) throw new BusinessConflict("媒体内容校验失败");
        return new ImageBytes(asset.mimeType(), bytes);
    }

    ImageBytes readReceiverVersion(Accounts.Actor actor, ObjectRecord version, String id) throws IOException {
        boolean referenced = directory.links(actor, version.key(), "TaskVersionUsesMedia", StorageProvider.Direction.OUTBOUND)
                .stream().anyMatch(link -> link.to().equals(new EntityKey("MediaAsset", id)));
        if (!referenced) throw new AccessDeniedException("图片不属于本次提醒");
        Asset asset = asset(required(actor, id));
        byte[] bytes = Files.readAllBytes(path(actor, id));
        if (!hash(bytes).equals(asset.digest())) throw new BusinessConflict("媒体内容校验失败");
        return new ImageBytes(asset.mimeType(), bytes);
    }

    /** A frozen version owns its immutable references even if the source example is later disabled. */
    public List<Asset> taskAssets(Accounts.Actor actor, ObjectRecord version) {
        var task = storage.getObject(actor.context(), "ReminderTask", text(version, "taskId"));
        if (task == null || !canReadTask(actor, task)) throw new AccessDeniedException("无权访问该任务媒体");
        return directory.links(actor, version.key(), "TaskVersionUsesMedia", StorageProvider.Direction.OUTBOUND).stream()
                .map(link -> asset(required(actor, link.to().id()))).toList();
    }

    private void requireAccess(Accounts.Actor actor, ObjectRecord asset) {
        var permissions = accounts.permissions(actor);
        if (permissions.contains("REMINDER_WRITE") && actor.username().equals(text(asset, "uploadedBy"))) return;
        if (permissions.contains("CONTENT_CONFIGURE")) return;
        for (var link : directory.links(actor, asset.key(), "VersionUsesMedia", StorageProvider.Direction.INBOUND)) {
            var version = storage.getObject(actor.context(), "ContentVersion", link.from().id());
            var example = version == null ? null : storage.getObject(actor.context(), "ReminderContent", text(version, "contentId"));
            if (permissions.contains("REMINDER_WRITE") && example != null && !example.isDeleted()
                    && "ENABLED".equals(text(example, "state"))) return;
        }
        for (var link : directory.links(actor, asset.key(), "TaskVersionUsesMedia", StorageProvider.Direction.INBOUND)) {
            var version = storage.getObject(actor.context(), "ReminderTaskVersion", link.from().id());
            var task = version == null ? null : storage.getObject(actor.context(), "ReminderTask", text(version, "taskId"));
            if (task != null && canReadTask(actor, task)) return;
        }
        throw new AccessDeniedException("无权访问此图片");
    }

    private boolean canReadTask(Accounts.Actor actor, ObjectRecord task) {
        if (!accounts.permissions(actor).contains("REMINDER_READ") || task.isDeleted()) return false;
        if (actor.role().equals("SUPER_ADMIN")) return true;
        if (actor.role().equals("REVIEWER")) return actor.organizationId().equals(text(task, "organizationId"))
                && !text(task, "state").equals("DRAFT");
        return actor.organizationId().equals(text(task, "organizationId")) || actor.username().equals(text(task, "createdBy"));
    }

    private ObjectRecord required(Accounts.Actor actor, String id) {
        if (id == null || !id.matches("media-[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid media identifier");
        var asset = storage.getObject(actor.context(), "MediaAsset", id);
        if (asset == null || asset.isDeleted() || !"ACTIVE".equals(text(asset, "state"))) {
            throw new AccessDeniedException("图片不可用");
        }
        return asset;
    }

    private Asset asset(ObjectRecord asset) {
        return new Asset(asset.id(), text(asset, "contentHash"), text(asset, "mimeType"),
                ((Number) asset.properties().get("width")).intValue(), ((Number) asset.properties().get("height")).intValue());
    }

    private Path path(Accounts.Actor actor, String id) {
        return root.resolve(BusinessCommands.hash(actor.tenantId())).resolve(id);
    }

    private static void writeImmutable(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                if (!MessageDigest.isEqual(Files.readAllBytes(path), bytes)) throw new BusinessConflict("上传标识已用于另一张图片");
                return;
            }
            Path temporary = Files.createTempFile(path.getParent(), "upload-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException failure) { throw new IllegalStateException("媒体保存失败", failure); }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String text(ObjectRecord object, String key) {
        Object value = object.properties().get(key);
        return value == null ? "" : value.toString();
    }

    public record Asset(String id, String digest, String mimeType, int width, int height) {}
    public record ImageBytes(String mimeType, byte[] bytes) {}
}
