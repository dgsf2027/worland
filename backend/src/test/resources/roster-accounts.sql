DROP TABLE IF EXISTS yc_rent_auth_user;
CREATE TABLE yc_rent_auth_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL, status TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_login_at TIMESTAMP,
  portal_uid VARCHAR(64)
);
DROP TABLE IF EXISTS yc_rent_user_role_ext;
CREATE TABLE yc_rent_user_role_ext (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNIQUE NOT NULL,
  user_name VARCHAR(64), role VARCHAR(32), data_scope VARCHAR(128),
  cost_visible TINYINT DEFAULT 1, owner_scoped TINYINT DEFAULT 0,
  project_id BIGINT, active TINYINT DEFAULT 1, remark VARCHAR(255),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, is_deleted TINYINT DEFAULT 0
);
DROP TABLE IF EXISTS yc_rent_audit_log;
CREATE TABLE yc_rent_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, action VARCHAR(64), target_type VARCHAR(64),
  target_id BIGINT, result VARCHAR(32), operator_id BIGINT, operator_name VARCHAR(64),
  operator_role VARCHAR(32), client_ip VARCHAR(64), request_uri VARCHAR(255),
  request_id VARCHAR(128), detail VARCHAR(2000), create_time TIMESTAMP
);
INSERT INTO yc_rent_auth_user(id, username, password_hash, display_name, role, status, portal_uid)
VALUES (101, 'owner', 'not-returned', '小洪', '老板', 1, NULL),
       (202, 'colleague', 'not-returned', '小洪', '业务', 1, 'portal-202'),
       (303, 'investor', 'not-returned', '刘总', 'LP', 0, NULL);
INSERT INTO yc_rent_user_role_ext(id, user_id, user_name, role)
VALUES (1, 1003, '小洪', '老板');
