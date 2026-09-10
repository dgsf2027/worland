-- 配件树 BOM:手动小计列(补 2026-09-07 提交 9433528 "Add subtotalOverride field to AssetBom class" 漏掉的迁移)
-- 实体 AssetBom.subtotalOverride 由 MyBatis-Plus 映射为 subtotal_override;缺列时 selectList/update 全部 1054 报错。
-- 幂等:列已存在(如已由 ops sql 先行 apply)则跳过,Flyway 重跑不报 Duplicate column。
SET NAMES utf8mb4;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME   = 'yc_rent_asset_bom'
     AND COLUMN_NAME  = 'subtotal_override'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE yc_rent_asset_bom ADD COLUMN subtotal_override decimal(18,2) DEFAULT NULL COMMENT ''手动小计(元);NULL=按 qty×unit_cost 自动计算'' AFTER unit_cost',
  'SELECT ''subtotal_override already exists'' AS skipped');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
