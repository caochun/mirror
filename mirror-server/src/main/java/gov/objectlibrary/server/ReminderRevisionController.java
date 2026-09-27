package gov.objectlibrary.server;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders/{id}/revision")
class ReminderRevisionController {
    private final ReminderRevisionService revisions;
    private final Accounts accounts;
    private final TaskExecutor publication;

    ReminderRevisionController(ReminderRevisionService revisions, Accounts accounts,
                               @Qualifier("revisionPublicationExecutor") TaskExecutor publication) {
        this.revisions = revisions;
        this.accounts = accounts;
        this.publication = publication;
    }

    @PostMapping
    Map<String, Object> save(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                             @RequestBody ReminderRevisionService.Draft input) {
        return revisions.save(accounts.actor(principal.getName()), id, input, key);
    }

    @PostMapping("/confirm")
    Map<String, Object> confirm(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody ReminderRevisionService.Confirmation input) {
        return revisions.confirm(accounts.actor(principal.getName()), id, input, key);
    }

    @PostMapping("/submit")
    Map<String, Object> submit(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody ReminderController.Version input) {
        return revisions.submit(accounts.actor(principal.getName()), id, input.expectedVersion(), key);
    }

    @PostMapping("/review")
    Map<String, Object> review(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                               @RequestBody ReminderService.Decision input) {
        var result = revisions.decide(accounts.actor(principal.getName()), id, input, key);
        if (input.decision().equals("APPROVE")) {
            try {
                publication.execute(revisions::publishApproved);
            } catch (TaskRejectedException busy) {
                // Approval is already durable. The publication scheduler will pick it up.
            }
        }
        return result;
    }

    @PostMapping("/withdraw-review")
    Map<String, Object> withdraw(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                 @Valid @RequestBody ReminderController.Version input) {
        return revisions.withdrawReview(accounts.actor(principal.getName()), id, input.expectedVersion(), key);
    }
}
