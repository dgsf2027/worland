-- =====================================================================
-- 设备单台收益手工覆盖 / 意向承接客户 / 跟进关联合同(V106)
-- 1) 单台收益五项(月租分摊/累计收租/回报率/在租天数/空置天数)允许手工覆盖:
--    override_* 为 NULL 时按系统自动计算;非 NULL 时展示手工值。只影响设备展示,不改合同与收租单。
-- 2) 意向承接客户:未签约设备可预设意向客户;签约起租后清空,以合同客户(current_holder_customer_id)为准。
-- 3) 客户跟进可关联一份合同(可空)。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_asset
  ADD COLUMN intended_customer_id bigint DEFAULT NULL COMMENT '意向承接客户(未签约设备预设;签约后清空)';
ALTER TABLE yc_rent_asset
  ADD COLUMN override_alloc_rent decimal(18,2) DEFAULT NULL COMMENT '单台收益手工覆盖:月租分摊(元);NULL=自动';
ALTER TABLE yc_rent_asset
  ADD COLUMN override_cumulative_rent decimal(18,2) DEFAULT NULL COMMENT '单台收益手工覆盖:累计收租(元);NULL=自动';
ALTER TABLE yc_rent_asset
  ADD COLUMN override_return_rate decimal(18,8) DEFAULT NULL COMMENT '单台收益手工覆盖:回报率(小数,0.12=12%);NULL=自动';
ALTER TABLE yc_rent_asset
  ADD COLUMN override_in_service_days int DEFAULT NULL COMMENT '单台收益手工覆盖:在租天数;NULL=自动';
ALTER TABLE yc_rent_asset
  ADD COLUMN override_idle_days int DEFAULT NULL COMMENT '单台收益手工覆盖:空置天数;NULL=自动';
CREATE INDEX idx_asset_intended_customer ON yc_rent_asset (intended_customer_id);

ALTER TABLE yc_rent_customer_followup
  ADD COLUMN contract_id bigint DEFAULT NULL COMMENT '关联合同(yc_rent_contract.id,可空)';
