package gov.objectlibrary.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Configuration
class ChannelConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReminderChannel.class)
    ReminderChannel reminderChannel(JdbcTemplate jdbc, Clock clock,
                                     @Value("${mirror.delivery-mode:disabled}") String mode) {
        if (!mode.equals("mock") && !mode.equals("disabled")) {
            throw new IllegalArgumentException("No verified production channel adapter is configured");
        }
        return new MockChannel(jdbc, clock, mode);
    }

    /** The Mock provider persists cancellation barriers as well as results, including cancellation before a send arrives. */
    private static class MockChannel implements ReminderChannel {
        private final JdbcTemplate jdbc;
        private final Clock clock;
        private final String mode;
        private final TransactionTemplate transactions;

        MockChannel(JdbcTemplate jdbc, Clock clock, String mode) {
            this.jdbc = jdbc;
            this.clock = clock;
            this.mode = mode;
            transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        }

        @Override
        public String mode() { return mode; }

        @Override
        public Result send(Request request) {
            return locked(request.tenantId(), request.recipientId(), () -> {
                String hash = fingerprint(List.of(request.recipientId(), request.identityReference(), request.taskVersionId(), request.title()));
                var previous = jdbc.queryForList("SELECT * FROM mirror_mock_deliveries WHERE tenant_id=? AND request_key=?", request.tenantId(), request.requestKey());
                if (previous.isEmpty()) {
                    boolean cancelled = jdbc.queryForObject("SELECT COUNT(*) FROM mirror_mock_cancellations WHERE tenant_id=? AND delivery_request_key=?",
                            Integer.class, request.tenantId(), request.requestKey()) > 0;
                    boolean identity = request.identityReference().startsWith("mock:");
                    String state = identity && !cancelled ? "DELIVERED" : "FAILED";
                    String error = cancelled ? "MOCK_WITHDRAWN" : identity ? "" : "MOCK_IDENTITY_REQUIRED";
                    jdbc.update("""
                            INSERT INTO mirror_mock_deliveries (tenant_id, request_key, payload_hash, result_state, delivered_at, error_code)
                            VALUES (?,?,?,?,?,?)
                            """, request.tenantId(), request.requestKey(), hash, state, clock.instant().toString(), error);
                }
                return jdbc.queryForObject("SELECT * FROM mirror_mock_deliveries WHERE tenant_id=? AND request_key=?", (row, i) -> {
                    if (!hash.equals(row.getString("payload_hash"))) throw new BusinessConflict("渠道请求标识被复用于不同消息");
                    String error = row.getString("error_code");
                    if (error == null) error = row.getString("result_state").equals("FAILED") ? "MOCK_IDENTITY_REQUIRED" : "";
                    return new Result(row.getString("result_state"), "mock-" + request.requestKey(), Instant.parse(row.getString("delivered_at")), error);
                }, request.tenantId(), request.requestKey());
            });
        }

        @Override
        public WithdrawalResult withdraw(WithdrawalRequest request) {
            return locked(request.tenantId(), request.recipientId(), () -> {
                var fields = new ArrayList<>(List.of(request.recipientId(), request.reason()));
                fields.addAll(request.deliveryRequestKeys());
                String hash = fingerprint(fields);
                var rows = jdbc.queryForList("SELECT * FROM mirror_mock_withdrawals WHERE tenant_id=? AND request_key=?", request.tenantId(), request.requestKey());
                if (rows.isEmpty()) {
                    String now = clock.instant().toString();
                    for (String key : request.deliveryRequestKeys()) {
                        var existing = jdbc.queryForList("SELECT recipient_id FROM mirror_mock_cancellations WHERE tenant_id=? AND delivery_request_key=?",
                                String.class, request.tenantId(), key);
                        if (!existing.isEmpty() && !existing.getFirst().equals(request.recipientId())) throw new BusinessConflict("撤回请求不属于该接收记录");
                        if (existing.isEmpty()) jdbc.update("INSERT INTO mirror_mock_cancellations VALUES (?,?,?,?)", request.tenantId(), key, request.recipientId(), now);
                    }
                    jdbc.update("INSERT INTO mirror_mock_withdrawals VALUES (?,?,?,?)", request.tenantId(), request.requestKey(), hash, now);
                }
                return jdbc.queryForObject("SELECT * FROM mirror_mock_withdrawals WHERE tenant_id=? AND request_key=?", (row, i) -> {
                    if (!hash.equals(row.getString("payload_hash"))) throw new BusinessConflict("撤回标识被复用于不同请求");
                    return new WithdrawalResult("WITHDRAWN", "mock-withdraw-" + request.requestKey(), Instant.parse(row.getString("withdrawn_at")), "");
                }, request.tenantId(), request.requestKey());
            });
        }

        private <T> T locked(String tenant, String recipient, Supplier<T> operation) {
            if (!mode.equals("mock")) throw new IllegalStateException("Channel is disabled");
            // Initialize outside the transaction: PostgreSQL cannot continue a transaction after a duplicate INSERT.
            try {
                jdbc.update("INSERT INTO mirror_mock_channel_locks VALUES (?,?)", tenant, recipient);
            } catch (DuplicateKeyException exists) {
                // A previous call already initialized the durable per-recipient lock.
            }
            return transactions.execute(status -> {
                jdbc.queryForObject("SELECT recipient_id FROM mirror_mock_channel_locks WHERE tenant_id=? AND recipient_id=? FOR UPDATE",
                        String.class, tenant, recipient);
                return operation.get();
            });
        }

        private static String fingerprint(List<String> fields) {
            var payload = new StringBuilder();
            for (String field : fields) payload.append(field.length()).append(':').append(field);
            return BusinessCommands.hash(payload.toString());
        }
    }
}
