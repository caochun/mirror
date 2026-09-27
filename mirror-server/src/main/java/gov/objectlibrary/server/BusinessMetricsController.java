package gov.objectlibrary.server;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;

@RestController
@RequestMapping("/api/metrics")
class BusinessMetricsController {
    private final Accounts accounts;
    private final BusinessMetricsService metrics;

    BusinessMetricsController(Accounts accounts, BusinessMetricsService metrics) {
        this.accounts = accounts;
        this.metrics = metrics;
    }

    @GetMapping
    ResponseEntity<BusinessMetricsService.Report> query(Principal principal,
            @RequestParam(defaultValue = "") String organizationId,
            @RequestParam(defaultValue = "") String creatorOrganization,
            @RequestParam(defaultValue = "") String recipientOrganization,
            @RequestParam(defaultValue = "") String tagId,
            @RequestParam(defaultValue = "") String tagVersionId,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String taskId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "true") boolean includeWithdrawn) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metrics.query(accounts.actor(principal.getName()),
                new BusinessMetricsService.Filter(organizationId, creatorOrganization, recipientOrganization, tagId, tagVersionId, category, taskId, from, to, includeWithdrawn)));
    }

    @GetMapping("/{snapshot}/details")
    ResponseEntity<BusinessMetricsService.Details> details(Principal principal, @PathVariable String snapshot,
            @RequestParam String metric, @RequestParam(defaultValue = "") String group,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metrics.details(accounts.actor(principal.getName()), snapshot, metric, group, page, size));
    }
}
