-- V125 采购入库单关联采购合同
-- 一份采购合同可对应多张采购入库单;已关联的入库单,其应付只能经「采购合同」页登记付款,
-- 登记时按到期日先后自动冲销该合同下各入库单的待付应付(不足一整行则拆行),撤销付款时精确还原。

ALTER TABLE yc_rent_purchase_in
  ADD COLUMN pc_id bigint DEFAULT NULL COMMENT '关联采购合同 yc_rent_purchase_contract.id(可空)',
  ADD KEY idx_purchase_in_pc (pc_id);

CREATE TABLE yc_rent_supplier_payment_payable (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  payment_id    bigint        NOT NULL COMMENT '供应商付款 id',
  payable_id    bigint        NOT NULL COMMENT '被冲销的应付行(yc_rent_payable.id)',
  paid_amount   decimal(14,2) NOT NULL COMMENT '本次冲销金额',
  orig_amount   decimal(14,2) NOT NULL COMMENT '冲销前该应付行的金额(撤销时还原用)',
  remainder_id  bigint        DEFAULT NULL COMMENT '部分冲销时拆出的差额待付行 id',
  PRIMARY KEY (id),
  KEY idx_spp_payment (payment_id),
  KEY idx_spp_payable (payable_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商付款↔应付行冲销记录';
