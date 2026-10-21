-- V124 采购合同 · 付款 · 进项发票(合同→付款→发票三单对账)
-- 供应商付款条款原先挂在客户租赁合同上(V118),与真实业务不符:条款属于与供应商签的采购合同。
-- 本迁移只新增表,不动旧表;旧付款条款与应付计划继续保留。

CREATE TABLE yc_rent_purchase_contract (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_no      varchar(64)   NOT NULL COMMENT '采购合同号(唯一)',
  supplier_id      bigint        NOT NULL COMMENT '供应商 yc_rent_supplier.id',
  rent_contract_id bigint        DEFAULT NULL COMMENT '对应的客户租赁合同(先签约后采购;可空)',
  total_amount     decimal(14,2) NOT NULL COMMENT '合同含税总价(元)',
  tax_rate         decimal(6,4)  NOT NULL DEFAULT 0.1300 COMMENT '合同税率,如 0.13',
  signed_date      date          DEFAULT NULL COMMENT '签订日期',
  status           varchar(16)   NOT NULL DEFAULT '执行中' COMMENT '执行中/已结清/作废',
  remark           varchar(500)  DEFAULT NULL,
  create_by_name   varchar(64)   DEFAULT NULL,
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted       tinyint       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pc_no (contract_no),
  KEY idx_pc_supplier (supplier_id),
  KEY idx_pc_rent_contract (rent_contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购合同(与供应商签)';

CREATE TABLE yc_rent_purchase_contract_term (
  id          bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  pc_id       bigint        NOT NULL COMMENT '采购合同 id',
  sort_no     int           NOT NULL DEFAULT 1 COMMENT '节点顺序',
  stage_name  varchar(32)   NOT NULL COMMENT '节点名称:首付/进度款/验收款/尾款…',
  ratio       decimal(6,4)  DEFAULT NULL COMMENT '占合同总价比例(可空)',
  amount      decimal(14,2) NOT NULL COMMENT '应付金额(元)',
  condition_text varchar(200) DEFAULT NULL COMMENT '触发条件说明',
  PRIMARY KEY (id),
  KEY idx_pct_pc (pc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购合同付款节点';

CREATE TABLE yc_rent_supplier_payment (
  id          bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  pc_id       bigint        NOT NULL COMMENT '采购合同 id',
  supplier_id bigint        NOT NULL COMMENT '收款供应商',
  amount      decimal(14,2) NOT NULL COMMENT '实付金额(元)',
  paid_date   date          NOT NULL COMMENT '付款日期',
  bank_serial varchar(64)   DEFAULT NULL COMMENT '银行回单流水号(同一流水不可重复登记)',
  voucher_id  bigint        DEFAULT NULL COMMENT '付款凭证(税务账)',
  status      varchar(8)    NOT NULL DEFAULT '有效' COMMENT '有效/撤销',
  remark      varchar(500)  DEFAULT NULL,
  create_by_name varchar(64) DEFAULT NULL,
  create_time datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sp_serial (bank_serial),
  KEY idx_sp_pc (pc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商付款(银行流水)';

CREATE TABLE yc_rent_supplier_payment_alloc (
  id          bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  payment_id  bigint        NOT NULL COMMENT '付款 id',
  term_id     bigint        NOT NULL COMMENT '核销的合同付款节点',
  amount      decimal(14,2) NOT NULL COMMENT '核销金额(元)',
  PRIMARY KEY (id),
  KEY idx_spa_payment (payment_id),
  KEY idx_spa_term (term_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款↔合同节点核销(一笔付款可核销多个节点)';

CREATE TABLE yc_rent_purchase_invoice (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  pc_id         bigint        NOT NULL COMMENT '采购合同 id',
  invoice_no    varchar(32)   NOT NULL COMMENT '发票号码(唯一)',
  invoice_type  varchar(8)    NOT NULL COMMENT '专票/普通',
  issue_date    date          NOT NULL COMMENT '开票日期',
  seller_name   varchar(128)  DEFAULT NULL COMMENT '销方名称',
  amount_ex_tax decimal(14,2) NOT NULL COMMENT '不含税金额',
  tax_amount    decimal(14,2) NOT NULL COMMENT '税额',
  total_amount  decimal(14,2) NOT NULL COMMENT '价税合计',
  tax_rate      decimal(6,4)  NOT NULL COMMENT '税率',
  status        varchar(8)    NOT NULL DEFAULT '有效' COMMENT '有效/作废',
  remark        varchar(500)  DEFAULT NULL,
  create_by_name varchar(64)  DEFAULT NULL,
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pi_no (invoice_no),
  KEY idx_pi_pc (pc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='进项发票(供应商开给我方)';
