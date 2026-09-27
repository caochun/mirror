package gov.objectlibrary.server;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders")
class ReminderController {
    private final Accounts accounts;
    private final ReminderService reminders;

    ReminderController(Accounts accounts, ReminderService reminders) {
        this.accounts = accounts;
        this.reminders = reminders;
    }

    @GetMapping
    List<ReminderService.TaskView> list(Principal principal) {
        return reminders.list(accounts.actor(principal.getName()));
    }

    @GetMapping("/{id}")
    ReminderService.Detail detail(Principal principal, @PathVariable String id) {
        return reminders.detail(accounts.actor(principal.getName()), id);
    }

    @PostMapping("/preview")
    ReminderSelection.Result preview(Principal principal, @RequestBody ReminderSelection.Filter input) {
        return reminders.preview(accounts.actor(principal.getName()), input);
    }

    @PostMapping
    Map<String, Object> create(Principal principal, @RequestHeader("Idempotency-Key") String key,
                               @RequestBody ReminderService.Draft input) {
        return reminders.save(accounts.actor(principal.getName()), null, input, key);
    }

    @PutMapping("/{id}")
    Map<String, Object> edit(Principal principal, @PathVariable String id,
                             @RequestHeader("Idempotency-Key") String key, @RequestBody ReminderService.Draft input) {
        return reminders.save(accounts.actor(principal.getName()), id, input, key);
    }

    @PostMapping("/{id}/confirm")
    Map<String, Object> confirm(Principal principal, @PathVariable String id,
                                @RequestHeader("Idempotency-Key") String key, @RequestBody ReminderService.Confirmation input) {
        return reminders.confirm(accounts.actor(principal.getName()), id, input, key);
    }

    @PostMapping("/{id}/submit")
    Map<String, Object> submit(Principal principal, @PathVariable String id,
                               @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Version input) {
        return reminders.submit(accounts.actor(principal.getName()), id, input.expectedVersion(), key);
    }

    @PostMapping("/{id}/review")
    Map<String, Object> review(Principal principal, @PathVariable String id,
                               @RequestHeader("Idempotency-Key") String key, @RequestBody ReminderService.Decision input) {
        return reminders.decide(accounts.actor(principal.getName()), id, input, key);
    }

    @PostMapping("/{id}/withdraw-review")
    Map<String, Object> withdrawReview(Principal principal, @PathVariable String id,
                                       @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Version input) {
        return reminders.withdrawReview(accounts.actor(principal.getName()), id, input.expectedVersion(), key);
    }

    @PostMapping("/{id}/cancel")
    Map<String, Object> cancel(Principal principal, @PathVariable String id,
                               @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Cancel input) {
        return reminders.cancel(accounts.actor(principal.getName()), id, input.expectedVersion(), input.reason(), key);
    }

    record Version(@Positive long expectedVersion) {}
    record Cancel(@Positive long expectedVersion, @NotBlank @Size(max = 1000) String reason) {}
}
