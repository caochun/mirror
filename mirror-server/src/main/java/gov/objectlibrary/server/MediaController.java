package gov.objectlibrary.server;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/media")
class MediaController {
    private final Accounts accounts;
    private final MediaService media;

    MediaController(Accounts accounts, MediaService media) {
        this.accounts = accounts;
        this.media = media;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(Principal principal, @RequestParam("file") MultipartFile file,
                              @RequestHeader("Idempotency-Key") String key) throws IOException {
        return media.upload(accounts.actor(principal.getName()), file, key);
    }

    @GetMapping("/{id}")
    ResponseEntity<byte[]> image(Principal principal, @PathVariable String id) throws IOException {
        var content = media.read(accounts.actor(principal.getName()), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mimeType()))
                .cacheControl(CacheControl.noStore()).header("Content-Disposition", "inline").body(content.bytes());
    }
}
