package gov.objectlibrary.server;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders/{id}")
class DeliveryController {
    private final DeliveryService delivery;
    private final Accounts accounts;

    DeliveryController(DeliveryService delivery, Accounts accounts) {
        this.delivery = delivery;
        this.accounts = accounts;
    }

    @GetMapping("/delivery")
    Map<String, Object> results(Principal principal, @PathVariable String id) {
        var recipients = delivery.recipients(accounts.actor(principal.getName()), id);
        return Map.of("mode", delivery.mode(), "mockEnabled", delivery.mockEnabled(), "recipients", recipients);
    }

    @PostMapping("/mock-dispatch")
    Map<String, Object> dispatch(Principal principal, @PathVariable String id,
                                @Valid @RequestBody ReminderController.Version input) {
        return delivery.dispatchMock(accounts.actor(principal.getName()), id, input.expectedVersion());
    }

    @PostMapping("/retry")
    Map<String, Object> retry(Principal principal, @PathVariable String id,
                             @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody ReminderController.Version input) {
        return delivery.retry(accounts.actor(principal.getName()), id, input.expectedVersion(), key);
    }
}
