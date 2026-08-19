-- 2026-08-19 澳乐门户 SSO 接入(eco.vvaix.com 免登直达):账号表加门户长期身份键 portal_uid
-- 版本号接在 V99__auth_user 之后(账号段)。out-of-order=true,老库补跑无碍。
-- 幂等说明:Flyway 只跑一次;若手工 apply 前先 SHOW COLUMNS FROM yc_rent_auth_user LIKE 'portal_uid' 确认不存在。
ALTER TABLE yc_rent_auth_user
  ADD COLUMN portal_uid VARCHAR(64) NULL COMMENT '澳乐门户 portal_uid(yc_portal_member.id 字符串形态);NULL=本地注册账号' AFTER last_login_at,
  ADD UNIQUE KEY uk_rent_auth_user_portal_uid (portal_uid);
