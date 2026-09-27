package gov.objectlibrary.server;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Canonical stored HTML contains opaque image IDs; render URLs are added only for an authorized view. */
@Service
public class ReminderContentPolicy {
    private final Set<String> hosts;
    private final MediaService media;

    public ReminderContentPolicy(@Value("${mirror.trusted-link-hosts:}") String allowedHosts, MediaService media) {
        this.media = media;
        hosts = Arrays.stream(allowedHosts.split(",")).map(String::strip)
                .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).collect(Collectors.toSet());
    }

    public Inspected inspect(Accounts.Actor actor, String input) {
        if (input == null || input.length() > 100000) throw new IllegalArgumentException("Invalid body");
        var document = Jsoup.parseBodyFragment(input);
        if (!document.select("video,audio,iframe,object,embed,table,form,input").isEmpty()) {
            throw new BusinessConflict("正文仅支持文字、列表、可信链接和受控图片");
        }
        if (document.select("img").size() > 6) throw new BusinessConflict("每条正文最多包含6张图片");
        for (var link : document.select("a[href]")) {
            try {
                var uri = URI.create(link.attr("href"));
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || (uri.getPort() != -1 && uri.getPort() != 443)
                        || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new BusinessConflict("链接必须为已配置可信域名的完整HTTPS地址");
            }
        }
        List<ImageReference> images = new ArrayList<>();
        for (var image : document.select("img")) {
            String id = image.attr("data-media-id");
            String source = image.attr("src");
            if (!source.isBlank()) {
                if (!source.matches("/api/media/media-[a-f0-9]{64}")) throw new BusinessConflict("图片必须先上传到受控媒体库");
                String sourceId = source.substring("/api/media/".length());
                if (!id.isEmpty() && !id.equals(sourceId)) throw new BusinessConflict("图片标识与内容不一致");
                id = sourceId;
            }
            var asset = media.metadata(actor, id);
            int index = images.size();
            // Same bytes placed twice still require two explicit image confirmations.
            images.add(new ImageReference(index, id, asset.digest(), id + ":" + index + ":" + asset.digest()));
            image.removeAttr("src");
            image.attr("data-media-id", id);
        }
        String cleaned = Jsoup.clean(document.body().html(), whitelist());
        String text = Jsoup.parseBodyFragment(cleaned).text();
        if (text.isBlank() && images.isEmpty()) throw new BusinessConflict("正文至少需要一段有效文字或一张有效图片");
        if (text.matches("(?s).*(?<![0-9])(?:1[3-9][0-9]{9}|[0-9]{17}[0-9Xx])(?![0-9]).*")) {
            throw new BusinessConflict("正文疑似包含手机号或身份证号，请核实并移除");
        }
        return new Inspected(cleaned, List.copyOf(images),
                Jsoup.parseBodyFragment(cleaned).select("a[href]").eachAttr("href"));
    }

    public String renderStored(String html) {
        var document = Jsoup.parseBodyFragment(Jsoup.clean(html, whitelist()));
        for (var image : document.select("img")) {
            String id = image.attr("data-media-id");
            if (!id.matches("media-[a-f0-9]{64}")) throw new BusinessConflict("媒体快照标识无效");
            image.attr("src", "/api/media/" + id);
        }
        return document.body().html();
    }

    public List<ImageReference> storedImages(Accounts.Actor actor, String html) {
        var images = Jsoup.parseBodyFragment(html).select("img");
        List<ImageReference> references = new ArrayList<>();
        for (var image : images) {
            String id = image.attr("data-media-id");
            var asset = media.metadata(actor, id);
            int index = references.size();
            references.add(new ImageReference(index, id, asset.digest(), id + ":" + index + ":" + asset.digest()));
        }
        return List.copyOf(references);
    }

    private static Safelist whitelist() {
        return new Safelist().addTags("p", "br", "strong", "b", "em", "i", "u", "h2", "h3", "ul", "ol", "li", "blockquote", "a", "img")
                .addAttributes("a", "href").addProtocols("a", "href", "https")
                .addAttributes("img", "data-media-id", "alt")
                .addEnforcedAttribute("a", "rel", "noopener noreferrer");
    }

    public record ImageReference(int index, String id, String digest, String confirmationKey) {}
    public record Inspected(String html, List<ImageReference> images, List<String> links) {
        public String mediaDigest() {
            return BusinessCommands.hash(images.stream().map(ImageReference::confirmationKey).collect(Collectors.joining("|")));
        }
    }
}
