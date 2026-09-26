CREATE TABLE mirror_role_permissions (
  role_name VARCHAR(40) NOT NULL,
  permission_name VARCHAR(80) NOT NULL,
  PRIMARY KEY (role_name, permission_name)
);
INSERT INTO mirror_role_permissions VALUES ('SUPER_ADMIN', 'PERSON_READ');
INSERT INTO mirror_role_permissions VALUES ('SUPER_ADMIN', 'PERSON_TAG_WRITE');
INSERT INTO mirror_role_permissions VALUES ('SUPER_ADMIN', 'TAG_CONFIGURE');
INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN', 'PERSON_READ');
INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN', 'PERSON_TAG_WRITE');
INSERT INTO mirror_role_permissions VALUES ('AREA_ADMIN', 'PERSON_READ');
INSERT INTO mirror_role_permissions VALUES ('AREA_ADMIN', 'PERSON_TAG_WRITE');
