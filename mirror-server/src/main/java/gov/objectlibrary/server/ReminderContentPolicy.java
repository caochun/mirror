package gov.objectlibrary.server;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Server-side sanitation shared by preview and the frozen review snapshot. */
@Service
public class ReminderContentPolicy {
    private final Set<String> hosts;

    public ReminderContentPolicy(@Value("${mirror.trusted-link-hosts:}") String allowedHosts) {
        hosts = Arrays.stream(allowedHosts.split(",")).map(String::strip)
                .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).collect(Collectors.toSet());
    }

    public String clean(String input) {
        if (input == null || input.length() > 100000) throw new IllegalArgumentException("Invalid body");
        var document = Jsoup.parseBodyFragment(input);
        if (!document.select("img,video,audio,iframe,object,embed,table,form,input").isEmpty()) {
            throw new BusinessConflict("媒体存储尚未接通，请先使用文字、列表和可信链接；图片能力后续接入");
        }
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
        var whitelist = new Safelist().addTags("p", "br", "strong", "b", "em", "i", "u", "h2", "h3", "ul", "ol", "li", "blockquote", "a")
                .addAttributes("a", "href").addProtocols("a", "href", "https")
                .addEnforcedAttribute("a", "rel", "noopener noreferrer");
        String cleaned = Jsoup.clean(input, whitelist);
        String text = Jsoup.parseBodyFragment(cleaned).text();
        if (text.isBlank()) throw new BusinessConflict("正文至少需要一段有效文字");
        if (text.matches("(?s).*(?<![0-9])(?:1[3-9][0-9]{9}|[0-9]{17}[0-9Xx])(?![0-9]).*")) {
            throw new BusinessConflict("正文疑似包含手机号或身份证号，请核实并移除");
        }
        return cleaned;
    }
}
