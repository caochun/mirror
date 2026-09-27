package gov.objectlibrary.server;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
class ReadingController {
    private final ReadingService reading;
    private final Accounts accounts;

    ReadingController(ReadingService reading, Accounts accounts) {
        this.reading = reading;
        this.accounts = accounts;
    }

    @GetMapping("/api/reading")
    ResponseEntity<Map<String, Object>> list(Principal principal, @RequestParam(defaultValue = "ALL") String state,
                                           @RequestParam(defaultValue = "") String query,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reading.list(accounts.actor(principal.getName()), state, query, page, size));
    }
}
