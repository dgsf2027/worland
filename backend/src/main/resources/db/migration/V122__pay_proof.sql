-- =====================================================================
-- 资金进出回单(V122 · 10-07 会议纪要「付款凭证强制留存」)
-- 采购付款 / 收租核销(含批量)/ 收租退款 / 逾期还款 / 赔偿收取,登记前必须先上传
-- 银行回单或支付截图。文件本身在 yc_rent_file_object(biz_type=pay_proof),
-- 本表记录「哪个文件挂在哪张单据上」:一个文件可挂多张单(批量核销一张回单对多张收租单)。
-- 回单不可删除,撤销付款/红冲不删关联,保留痕迹。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE yc_rent_pay_proof (
  id              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  file_id         bigint       NOT NULL COMMENT '回单文件(yc_rent_file_object.id,biz_type=pay_proof)',
  biz_type        varchar(16)  NOT NULL COMMENT '单据类型:payable/rent_bill/inv_damage',
  biz_id          bigint       NOT NULL COMMENT '单据 id',
  action          varchar(16)  NOT NULL COMMENT '付款/核销/批量核销/退款/还款/赔偿收取/补传',
  create_by_name  varchar(64)  DEFAULT NULL COMMENT '挂回单的人',
  create_time     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '挂回单时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_proof (file_id, biz_type, biz_id),
  KEY idx_pay_proof_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金进出回单关联';
