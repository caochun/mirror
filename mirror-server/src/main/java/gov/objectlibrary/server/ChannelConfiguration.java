package gov.objectlibrary.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;

@Configuration
class ChannelConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReminderChannel.class)
    ReminderChannel reminderChannel(JdbcTemplate jdbc, Clock clock,
                                     @Value("${mirror.delivery-mode:disabled}") String mode) {
        if (!mode.equals("mock") && !mode.equals("disabled")) {
            throw new IllegalArgumentException("No verified production channel adapter is configured");
        }
        return new ReminderChannel() {
            @Override
            public String mode() {
                return mode;
            }

            @Override
            public Result send(Request request) {
                if (!mode.equals("mock")) throw new IllegalStateException("Delivery channel is disabled");
                StringBuilder payload = new StringBuilder();
                for (String field : java.util.List.of(request.recipientId(), request.identityReference(), request.taskVersionId(), request.title())) {
                    payload.append(field.length()).append(':').append(field);
                }
                String fingerprint = BusinessCommands.hash(payload.toString());
                String outcome = request.identityReference().startsWith("mock:") ? "DELIVERED" : "FAILED";
                try {
                    jdbc.update("INSERT INTO mirror_mock_deliveries (tenant_id, request_key, payload_hash, result_state, delivered_at) VALUES (?,?,?,?,?)", request.tenantId(),
                            request.requestKey(), fingerprint, outcome, clock.instant().toString());
                } catch (DuplicateKeyException duplicate) {
                    // Same channel request after restart must return the original outcome/time.
                }
                return jdbc.queryForObject("SELECT * FROM mirror_mock_deliveries WHERE tenant_id=? AND request_key=?",
                        (row, index) -> {
                            if (!fingerprint.equals(row.getString("payload_hash"))) throw new BusinessConflict("渠道请求标识被复用于不同消息");
                            String state = row.getString("result_state");
                            return new Result(state, "mock-" + request.requestKey(), Instant.parse(row.getString("delivered_at")),
                                    state.equals("FAILED") ? "MOCK_IDENTITY_REQUIRED" : "");
                        }, request.tenantId(), request.requestKey());
            }
        };
    }
}
