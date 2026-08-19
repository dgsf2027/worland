-- 2026-08-19 澳乐门户 SSO 接入(eco.vvaix.com 免登直达):账号表加门户长期身份键 portal_uid
-- 版本号接在 V99__auth_user 之后(账号段)。out-of-order=true,老库补跑无碍。
-- 执行方式:本系统 Flyway 随 boot 自动跑(compose 已开 spring.flyway.enabled),**禁止手动 apply**(手动跑会让 flyway_schema_history 缺记录/双跑)。
-- 幂等:先查 INFORMATION_SCHEMA 再加列/加唯一索引,重跑不报错(照 vend V1.0.100 写法)。
SET @tbl := 'yc_rent_auth_user';

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'portal_uid');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE yc_rent_auth_user ADD COLUMN portal_uid varchar(64) NULL DEFAULT NULL COMMENT ''澳乐门户 portal_uid(yc_portal_member.id 字符串形态;NULL=本地注册账号)'' AFTER last_login_at',
  'SELECT ''portal_uid already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'uk_rent_auth_user_portal_uid');
SET @sql := IF(@idx_exists = 0,
  'ALTER TABLE yc_rent_auth_user ADD UNIQUE KEY uk_rent_auth_user_portal_uid (portal_uid)',
  'SELECT ''uk_rent_auth_user_portal_uid already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
