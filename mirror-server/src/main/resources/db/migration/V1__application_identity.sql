CREATE TABLE mirror_accounts (
  username VARCHAR(100) PRIMARY KEY,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  tenant_id VARCHAR(100) NOT NULL,
  organization_id VARCHAR(255) NOT NULL,
  role_name VARCHAR(40) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE mirror_scope_roots (
  username VARCHAR(100) NOT NULL REFERENCES mirror_accounts(username),
  organization_id VARCHAR(255) NOT NULL,
  PRIMARY KEY (username, organization_id)
);
