package gov.objectlibrary.server;

import java.time.Instant;

/** Request identity is stable across worker recovery; a timeout must be reported as UNKNOWN. */
public interface ReminderChannel {
    String mode();
    Result send(Request request);

    default WithdrawalResult withdraw(WithdrawalRequest request) {
        throw new UnsupportedOperationException("Channel withdrawal is not configured");
    }

    record WithdrawalRequest(String tenantId, String requestKey, String recipientId,
                             java.util.List<String> deliveryRequestKeys, String reason) {
        public WithdrawalRequest {
            deliveryRequestKeys = java.util.List.copyOf(deliveryRequestKeys);
        }
    }

    record WithdrawalResult(String state, String eventId, Instant occurredAt, String errorCode) {
        public WithdrawalResult {
            if (!java.util.Set.of("WITHDRAWN", "FAILED", "UNKNOWN").contains(state) || eventId == null || eventId.isBlank()
                    || eventId.length() > 200 || occurredAt == null) throw new IllegalArgumentException("Invalid withdrawal result");
            if (errorCode == null) errorCode = "";
        }
    }

    record Request(String tenantId, String requestKey, String recipientId, String identityReference,
                   String taskVersionId, String title) {}
    record Result(String state, String eventId, Instant occurredAt, String errorCode) {
        public Result {
            if (!java.util.Set.of("DELIVERED", "FAILED", "UNKNOWN").contains(state)) {
                throw new IllegalArgumentException("Invalid channel outcome");
            }
            if (eventId == null || eventId.isBlank() || eventId.length() > 200 || occurredAt == null) {
                throw new IllegalArgumentException("Channel event identity and occurrence time are required");
            }
            if (errorCode == null) errorCode = "";
        }
    }
}
