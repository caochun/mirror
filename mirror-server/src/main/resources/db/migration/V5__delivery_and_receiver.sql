INSERT INTO mirror_role_permissions VALUES ('SUPER_ADMIN', 'REMINDER_RETRY');
INSERT INTO mirror_role_permissions VALUES ('AREA_ADMIN', 'REMINDER_RETRY');
INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN', 'REMINDER_RETRY');
INSERT INTO mirror_role_permissions VALUES ('SUPER_ADMIN', 'OVERDUE_READ');
INSERT INTO mirror_role_permissions VALUES ('AREA_ADMIN', 'OVERDUE_READ');
INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN', 'OVERDUE_READ');
CREATE TABLE mirror_mock_deliveries (
  tenant_id VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  payload_hash VARCHAR(128) NOT NULL,
  result_state VARCHAR(32) NOT NULL,
  delivered_at VARCHAR(64) NOT NULL,
  PRIMARY KEY (tenant_id, request_key)
);
CREATE TABLE mirror_receiver_tickets (
  ticket_hash VARCHAR(128) PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL,
  recipient_id VARCHAR(512) NOT NULL,
  session_binding VARCHAR(128) NOT NULL,
  issued_by VARCHAR(100) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  consumed_at TIMESTAMP WITH TIME ZONE NULL
);
