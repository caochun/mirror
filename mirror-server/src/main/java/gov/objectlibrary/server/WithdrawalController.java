package gov.objectlibrary.server;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders/{id}/withdrawals")
class WithdrawalController {
    private final WithdrawalService withdrawals;
    private final Accounts accounts;
    private final TaskExecutor executor;

    WithdrawalController(WithdrawalService withdrawals, Accounts accounts,
                         @Qualifier("withdrawalExecutor") TaskExecutor executor) {
        this.withdrawals = withdrawals;
        this.accounts = accounts;
        this.executor = executor;
    }

    @GetMapping
    org.springframework.http.ResponseEntity<DirectoryService.Page<WithdrawalService.HistoryView>> history(
            Principal principal, @PathVariable String id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(withdrawals.history(accounts.actor(principal.getName()), id, page, size));
    }

    @PostMapping
    Map<String, Object> request(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody WithdrawalService.Request input) {
        return request(principal, id, key, input, false);
    }

    @PostMapping("/retry")
    Map<String, Object> retry(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody WithdrawalService.Request input) {
        return request(principal, id, key, input, true);
    }

    private Map<String, Object> request(Principal principal, String id, String key, WithdrawalService.Request input, boolean retry) {
        var actor = accounts.actor(principal.getName());
        var result = withdrawals.request(actor, id, input, key, retry);
        try {
            executor.execute(() -> withdrawals.dispatchTenant(actor.tenantId(), id));
        } catch (TaskRejectedException busy) {
            // The durable intent remains available for the scheduled worker.
        }
        return result;
    }
}
