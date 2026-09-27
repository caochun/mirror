package gov.objectlibrary.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@RestController
class ReceiverController {
    private final ReceiverService receiver;
    private final ReceiverSessions sessions;
    private final Accounts accounts;

    ReceiverController(ReceiverService receiver, ReceiverSessions sessions, Accounts accounts) {
        this.receiver = receiver;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @PostMapping("/api/reminders/{taskId}/recipients/{recipientId}/mock-entry")
    ResponseEntity<Map<String, String>> issue(Principal principal, HttpServletRequest request,
                                             @PathVariable String taskId, @PathVariable String recipientId) {
        String url = receiver.issueMock(accounts.actor(principal.getName()), taskId, recipientId, request.getSession(false));
        return privateResponse(Map.of("url", url));
    }

    @PostMapping("/api/receiver/exchange")
    ResponseEntity<Map<String, String>> exchange(HttpServletRequest request, @Valid @RequestBody Ticket input) {
        String grant = sessions.exchange(input.ticket(), request.getSession(false));
        return privateResponse(Map.of("grantId", grant, "mode", "mock"));
    }

    @GetMapping("/api/receiver/{grant}/content")
    ResponseEntity<ReceiverService.Content> content(HttpServletRequest request, @PathVariable String grant) {
        return privateResponse(receiver.content(grant, request.getSession(false)));
    }

    @PostMapping("/api/receiver/{grant}/read")
    ResponseEntity<Map<String, Object>> read(HttpServletRequest request, @PathVariable String grant, @Valid @RequestBody Read input) {
        return privateResponse(receiver.read(grant, request.getSession(false), input.versionId(), input.renderToken(), input.bodyRendered()));
    }

    @PostMapping("/api/receiver/{grant}/issues")
    ResponseEntity<Map<String, Object>> report(HttpServletRequest request, @PathVariable String grant, @Valid @RequestBody Issue input) {
        return privateResponse(receiver.report(grant, request.getSession(false), input.versionId(), input.category(), input.mediaId()));
    }

    @GetMapping("/api/receiver/{grant}/media/{version}/{id}")
    ResponseEntity<byte[]> image(HttpServletRequest request, @PathVariable String grant, @PathVariable String version,
                                 @PathVariable String id) throws IOException {
        var image = receiver.image(grant, request.getSession(false), version, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .header("Content-Disposition", "inline").body(image.bytes());
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
    }

    record Ticket(@NotBlank String ticket) {}
    record Read(@NotBlank String versionId, @NotBlank String renderToken, boolean bodyRendered) {}
    record Issue(String versionId, @NotBlank String category, String mediaId) {}
}
