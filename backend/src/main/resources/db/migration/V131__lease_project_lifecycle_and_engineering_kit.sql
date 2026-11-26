-- V131: 项目一单到底主轴(yc_rent_project) + 工程套装建档(货位数/库区) + 采购合同直发交付字段 + 合同提前买断字段

-- 1. 租赁项目主表（一单到底：报价→签约→采购排产→过磅安装验收→收租开票→质保维保→留购结项）
CREATE TABLE IF NOT EXISTS yc_rent_project (
    id                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_no           VARCHAR(64)   NOT NULL                COMMENT '项目编号(唯一)',
    name                 VARCHAR(128)  NOT NULL                COMMENT '项目名称(如 茶塘园区B区1楼重型货架)',
    asset_mode           VARCHAR(16)   NOT NULL DEFAULT '工程套装' COMMENT '资产形态:工程套装/单机设备',
    customer_id          BIGINT        DEFAULT NULL            COMMENT '承租客户/意向客户',
    supplier_id          BIGINT        DEFAULT NULL            COMMENT '主供应商',
    rent_contract_id     BIGINT        DEFAULT NULL            COMMENT '关联租赁合同(可先采后签)',
    purchase_contract_id BIGINT        DEFAULT NULL            COMMENT '关联采购合同',
    primary_asset_id     BIGINT        DEFAULT NULL            COMMENT '主工程套装/主设备台账ID',
    stage                VARCHAR(24)   NOT NULL DEFAULT '报价测算' COMMENT '阶段:报价测算/合同落定/采购排产/交付验收/收租运营/质保维保/已结项',
    pallet_slots         INT           DEFAULT NULL            COMMENT '总货位数(如 2279)',
    planned_tons         DECIMAL(10,2) DEFAULT NULL            COMMENT '合同约定吨数(如 45.00)',
    actual_weigh_tons    DECIMAL(10,2) DEFAULT NULL            COMMENT '现场实际过磅吨数',
    warehouse_zone       VARCHAR(128)  DEFAULT NULL            COMMENT '安装库区/楼层(如 茶塘园区B区1楼)',
    delivery_address     VARCHAR(255)  DEFAULT NULL            COMMENT '交付/安装地址',
    delivery_contact     VARCHAR(64)   DEFAULT NULL            COMMENT '现场联系人及电话',
    production_start_date DATE         DEFAULT NULL            COMMENT '排产日期',
    shipped_date         DATE          DEFAULT NULL            COMMENT '发货日期',
    install_done_date    DATE          DEFAULT NULL            COMMENT '安装完成日期',
    load_test_date       DATE          DEFAULT NULL            COMMENT '载荷检测日期',
    acceptance_date      DATE          DEFAULT NULL            COMMENT '三方竣工验收合格日(起算质保)',
    audit_pass_date      DATE          DEFAULT NULL            COMMENT '结算资料审核通过日(触发月结账期)',
    warranty_years       INT           DEFAULT 5               COMMENT '免费质保年限(年)',
    warranty_end_date    DATE          DEFAULT NULL            COMMENT '质保到期日',
    quote_principal      DECIMAL(14,2) DEFAULT NULL            COMMENT '报价测算本金/成交价(元)',
    quote_annual_rate    DECIMAL(8,4)  DEFAULT NULL            COMMENT '报价测算名义年化利率(%)',
    quote_term_months    INT           DEFAULT NULL            COMMENT '报价测算租期(月)',
    quote_month_rent     DECIMAL(14,2) DEFAULT NULL            COMMENT '报价测算月租(元)',
    quote_buyout_price   DECIMAL(14,2) DEFAULT NULL            COMMENT '报价测算期末留购价(元)',
    remark               VARCHAR(500)  DEFAULT NULL            COMMENT '备注',
    owner_user_id        BIGINT        DEFAULT NULL            COMMENT '项目负责人',
    create_by_name       VARCHAR(64)   DEFAULT NULL            COMMENT '创建人',
    create_time          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted           TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_no (project_no),
    KEY idx_project_customer (customer_id),
    KEY idx_project_rent_contract (rent_contract_id),
    KEY idx_project_pc (purchase_contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租赁项目主表(一单到底进度与交付主轴)';

-- 2. 设备台账支持「工程套装(按套建档)」货位数与库区位置
ALTER TABLE yc_rent_asset
    ADD COLUMN asset_mode VARCHAR(16) NOT NULL DEFAULT '单机设备' COMMENT '资产形态:单机设备/工程套装' AFTER category,
    ADD COLUMN pallet_slots INT DEFAULT NULL COMMENT '总货位数(工程套装)' AFTER weigh_tons,
    ADD COLUMN warehouse_zone VARCHAR(128) DEFAULT NULL COMMENT '安装库区/楼层' AFTER pallet_slots;

-- 3. 采购合同支持直发客户仓交付/过磅/安装/检测（减少与采购入库单重复录单）
ALTER TABLE yc_rent_purchase_contract
    ADD COLUMN shipped_date DATE DEFAULT NULL COMMENT '发货日期' AFTER delivery_contact,
    ADD COLUMN install_done_date DATE DEFAULT NULL COMMENT '安装完成日期' AFTER shipped_date,
    ADD COLUMN load_test_date DATE DEFAULT NULL COMMENT '载荷检测日期' AFTER install_done_date,
    ADD COLUMN weigh_tons DECIMAL(10,2) DEFAULT NULL COMMENT '现场过磅吨数' AFTER load_test_date,
    ADD COLUMN pallet_slots INT DEFAULT NULL COMMENT '安装货位数' AFTER weigh_tons;

-- 4. 租赁合同支持提前买断违约金比例与实际买断额留痕
ALTER TABLE yc_rent_contract
    ADD COLUMN early_buyout_penalty_rate DECIMAL(6,4) DEFAULT 0.0300 COMMENT '提前买断违约金率(占剩余本金比例,默认3%)' AFTER overdue_daily_rate,
    ADD COLUMN early_buyout_amount DECIMAL(14,2) DEFAULT NULL COMMENT '实际提前买断成交额(元)' AFTER early_buyout_penalty_rate;
