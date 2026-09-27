package gov.objectlibrary.server;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/content-examples")
class ContentLibraryController {
    private final Accounts accounts;
    private final ContentLibraryService examples;

    ContentLibraryController(Accounts accounts, ContentLibraryService examples) {
        this.accounts = accounts;
        this.examples = examples;
    }

    @GetMapping
    List<ContentLibraryService.Example> list(Principal principal) {
        return examples.list(accounts.actor(principal.getName()));
    }

    @GetMapping("/{id}")
    ContentLibraryService.Example get(Principal principal, @PathVariable String id) {
        return examples.get(accounts.actor(principal.getName()), id);
    }

    @PostMapping
    Map<String, Object> create(Principal principal, @RequestHeader("Idempotency-Key") String key,
                               @RequestBody ContentLibraryService.Draft input) {
        return examples.save(accounts.actor(principal.getName()), null, input, key);
    }

    @PutMapping("/{id}")
    Map<String, Object> edit(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                             @RequestBody ContentLibraryService.Draft input) {
        return examples.save(accounts.actor(principal.getName()), id, input, key);
    }

    @PostMapping("/{id}/availability")
    Map<String, Object> availability(Principal principal, @PathVariable String id,
                                     @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Availability input) {
        return examples.availability(accounts.actor(principal.getName()), id, input.expectedVersion(), input.state(), key);
    }

    record Availability(@Positive long expectedVersion, @NotNull @Pattern(regexp = "ENABLED|DISABLED") String state) {}
}
