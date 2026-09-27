INSERT INTO mirror_role_permissions VALUES ('SUPER_ADMIN', 'REMINDER_WITHDRAW');
INSERT INTO mirror_role_permissions VALUES ('AREA_ADMIN', 'REMINDER_WITHDRAW');
INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN', 'REMINDER_WITHDRAW');
CREATE TABLE mirror_mock_channel_locks (
  tenant_id VARCHAR(100) NOT NULL,
  recipient_id VARCHAR(512) NOT NULL,
  PRIMARY KEY (tenant_id, recipient_id)
);
CREATE TABLE mirror_mock_cancellations (
  tenant_id VARCHAR(100) NOT NULL,
  delivery_request_key VARCHAR(128) NOT NULL,
  recipient_id VARCHAR(512) NOT NULL,
  withdrawn_at VARCHAR(64) NOT NULL,
  PRIMARY KEY (tenant_id, delivery_request_key)
);
CREATE TABLE mirror_mock_withdrawals (
  tenant_id VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  payload_hash VARCHAR(128) NOT NULL,
  withdrawn_at VARCHAR(64) NOT NULL,
  PRIMARY KEY (tenant_id, request_key)
);
ALTER TABLE mirror_mock_deliveries ADD COLUMN error_code VARCHAR(80);
