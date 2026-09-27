package gov.objectlibrary.server;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mappings")
class MappingController {
    private final Accounts accounts;
    private final MappingService mappings;
    private final RuleBatchService batches;
    private final TaskExecutor executor;

    MappingController(Accounts accounts, MappingService mappings, RuleBatchService batches,
                      @Qualifier("ruleExecutor") TaskExecutor executor) {
        this.accounts = accounts;
        this.mappings = mappings;
        this.batches = batches;
        this.executor = executor;
    }

    @GetMapping
    List<Map<String, Object>> list(Principal principal) {
        return mappings.list(accounts.actor(principal.getName()));
    }

    @GetMapping("/{id}/history")
    List<Map<String, Object>> history(Principal principal, @PathVariable String id) {
        return mappings.history(accounts.actor(principal.getName()), id);
    }

    @PostMapping("/previews")
    Map<String, Object> preview(Principal principal, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody MappingService.Proposal input) {
        var result = mappings.preview(accounts.actor(principal.getName()), input, key);
        execute(mappings::processPending);
        return result;
    }

    @GetMapping("/previews/{id}")
    Map<String, Object> detail(Principal principal, @PathVariable String id,
                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return mappings.detail(accounts.actor(principal.getName()), id, page, size);
    }

    @PostMapping("/{id}/publish")
    Map<String, Object> publish(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody MappingService.Publish input) {
        var result = mappings.publish(accounts.actor(principal.getName()), id, input, key);
        execute(batches::processPending);
        return result;
    }

    private void execute(Runnable work) {
        try { executor.execute(work); }
        catch (TaskRejectedException busy) { /* A persisted preview or batch remains available to the scheduler. */ }
    }
}
