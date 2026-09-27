package gov.objectlibrary.server;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")
class RuleController {
    private final Accounts accounts;
    private final RuleConfigurationService rules;
    private final RuleBatchService batches;
    private final TaskExecutor executor;

    RuleController(Accounts accounts, RuleConfigurationService rules, RuleBatchService batches,
                   @Qualifier("ruleExecutor") TaskExecutor executor) {
        this.accounts = accounts;
        this.rules = rules;
        this.batches = batches;
        this.executor = executor;
    }

    @GetMapping
    List<RuleConfigurationService.RuleView> list(Principal principal) { return rules.list(accounts.actor(principal.getName())); }

    @PostMapping
    Map<String, Object> create(Principal principal, @RequestHeader("Idempotency-Key") String key, @RequestBody Create input) {
        return rules.create(accounts.actor(principal.getName()), input.tagId(), input.name(), key);
    }

    @PostMapping("/{id}/preview")
    Map<String, Object> preview(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody Preview input) {
        var result = rules.preview(accounts.actor(principal.getName()), id, input.condition(), key);
        execute(rules::processPreviews);
        return result;
    }

    @GetMapping("/previews/{id}")
    Map<String, Object> preview(Principal principal, @PathVariable String id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return rules.previewDetail(accounts.actor(principal.getName()), id, page, size);
    }

    @PostMapping("/{id}/publish")
    Map<String, Object> publish(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody RuleConfigurationService.Publish input) {
        var result = rules.publish(accounts.actor(principal.getName()), id, input, key);
        execute(batches::processPending);
        return result;
    }

    @GetMapping("/batches")
    List<Map<String, Object>> batches(Principal principal) { return batches.list(accounts.actor(principal.getName())); }

    @PostMapping("/batches")
    Map<String, Object> start(Principal principal, @RequestHeader("Idempotency-Key") String key, @RequestBody Start input) {
        var result = batches.start(accounts.actor(principal.getName()), input.ruleIds(), input.personIds(), key);
        execute(batches::processPending);
        return result;
    }

    @GetMapping("/batches/{id}")
    DirectoryService.Page<Map<String, Object>> results(Principal principal, @PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return batches.results(accounts.actor(principal.getName()), id, page, size);
    }

    private void execute(Runnable action) {
        try { executor.execute(action); }
        catch (TaskRejectedException busy) { /* Persistent work is retried by the scheduler. */ }
    }
    record Create(String tagId, String name) {}
    record Preview(Map<String, Object> condition) {}
    record Start(List<String> ruleIds, List<String> personIds) {}
}
