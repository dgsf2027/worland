-- =========================================================================
-- 曜石租赁生产上线前数据清理脚本（2026-11-26）
--
-- 【严格保留（一条不删）】：
--   1. 客户 CRM 全部档案与跟进记录：yc_rent_customer / yc_rent_customer_followup / yc_rent_opportunity
--   2. 供应商档案与供货报价：yc_rent_supplier / yc_rent_supplier_supply
--   3. 供应商考察记录与全部考察图片/压缩包附件：yc_rent_supplier_inspection / yc_rent_file_object(supplier_inspection*)
--   4. 登录账号、花名册角色、系统规则、投资人档案、自动化规则/通道、审计日志与 Flyway 迁移历史
--
-- 【彻底清空（归零重新建正式单）】：
--   合同、采购合同、采购入库、应付、收租单、租金计划、逾期案、设备台账、工程套装、项目中心、
--   转让处置、维保、仓库短租物资、凭证、月报、分配、提成、任务审批、提醒、银行流水对账等全部交易数据。
--
-- 使用前请务必先执行 mysqldump 全库备份！
-- =========================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- 1. 保留客户跟进记录，但清空指向已删旧合同的孤儿外键，避免详情点击 404
UPDATE yc_rent_customer_followup SET contract_id = NULL WHERE contract_id IS NOT NULL;
-- 重置客户手工累计利润/续租率不影响基本档案与信用画像；阶段仍保留原有 CRM 阶段

-- 2. 清空项目中心与租赁合同主线
DELETE FROM yc_rent_project;
DELETE FROM yc_rent_overdue_case;
DELETE FROM yc_rent_repossess_order;
DELETE FROM yc_rent_rent_bill;
DELETE FROM yc_rent_rent_schedule;
DELETE FROM yc_rent_deposit_ledger;
DELETE FROM yc_rent_contract_change;
DELETE FROM yc_rent_contract_boq;
DELETE FROM yc_rent_contract_asset;
DELETE FROM yc_rent_contract_payment_term;
DELETE FROM yc_rent_contract;

-- 3. 清空采购合同与采购入库主线
DELETE FROM yc_rent_supplier_payment_payable;
DELETE FROM yc_rent_supplier_payment_alloc;
DELETE FROM yc_rent_supplier_payment;
DELETE FROM yc_rent_purchase_invoice;
DELETE FROM yc_rent_purchase_contract_term;
DELETE FROM yc_rent_purchase_contract;
DELETE FROM yc_rent_payable;
DELETE FROM yc_rent_purchase_item;
DELETE FROM yc_rent_purchase_in;

-- 4. 清空设备台账、转让处置、维保与盘点
DELETE FROM yc_rent_transfer_order_line;
DELETE FROM yc_rent_transfer_order;
DELETE FROM yc_rent_asset_depreciation_line;
DELETE FROM yc_rent_asset_event;
DELETE FROM yc_rent_asset_bom;
DELETE FROM yc_rent_asset_boq;
DELETE FROM yc_rent_asset_payment_term;
DELETE FROM yc_rent_asset;
DELETE FROM yc_rent_maintenance;
DELETE FROM yc_rent_stock_diff;
DELETE FROM yc_rent_stocktake;

-- 5. 清空仓库短租物资台账与出入库（保留企业抬头 yc_rent_inv_company）
DELETE FROM yc_rent_inv_damage;
DELETE FROM yc_rent_inv_movement;
DELETE FROM yc_rent_inv_rental;
DELETE FROM yc_rent_inv_comp_price;
DELETE FROM yc_rent_inv_item;

-- 6. 清空财务凭证、总账、月报、分配、提成、任务、提醒与自动化历史流水
DELETE FROM yc_rent_voucher_line;
DELETE FROM yc_rent_voucher;
DELETE FROM yc_rent_ledger_book;
DELETE FROM yc_rent_accounting_period;
DELETE FROM yc_rent_monthly_report;
DELETE FROM yc_rent_rent_distribution;
DELETE FROM yc_rent_commission;
DELETE FROM yc_rent_task;
DELETE FROM yc_rent_approval;
DELETE FROM yc_rent_action_item;
DELETE FROM yc_rent_reminder;
DELETE FROM yc_rent_bank_statement;
DELETE FROM yc_rent_pay_proof;
DELETE FROM yc_rent_automation_log;
DELETE FROM yc_rent_import_job;
DELETE FROM yc_rent_llm_call_log;

-- 7. 清理已删交易单据的附件元数据（严格保留供应商考察压缩包/产品图、客户及供应商附件）
DELETE FROM yc_rent_file_object
WHERE biz_type NOT IN (
    'supplier_inspection',
    'supplier_inspection_image',
    'supplier',
    'customer'
);

-- 8. 重置自动化规则最近执行摘要（保留规则开关和参数配置）
UPDATE yc_rent_automation_rule
SET last_run_at = NULL, last_status = NULL, last_summary = NULL;

SET FOREIGN_KEY_CHECKS = 1;

-- 9. 写入不可篡改审计日志
INSERT INTO yc_rent_audit_log
    (action, target_type, target_id, result, operator_id, operator_name, operator_role, client_ip, request_uri, request_id, detail, create_time)
VALUES
    ('上线前历史业务清零', 'system', 0, 'EXECUTED', 0, '运维上线脚本', '老板', '127.0.0.1', '/deploy/reset-business-keep-crm-supplier.sql', UUID(),
     '按老板指令保留客户CRM、供应商档案与供应商考察记录及账号配置，清空其余全部历史测试业务数据', NOW());

-- 10. 输出保留表与清空表核对清单
SELECT 'KEEP: yc_rent_customer (客户CRM)' AS check_item, COUNT(*) AS row_count FROM yc_rent_customer WHERE is_deleted = 0
UNION ALL SELECT 'KEEP: yc_rent_customer_followup (客户跟进)', COUNT(*) FROM yc_rent_customer_followup WHERE is_deleted = 0
UNION ALL SELECT 'KEEP: yc_rent_supplier (供应商档案)', COUNT(*) FROM yc_rent_supplier WHERE is_deleted = 0
UNION ALL SELECT 'KEEP: yc_rent_supplier_supply (供应商供货)', COUNT(*) FROM yc_rent_supplier_supply WHERE is_deleted = 0
UNION ALL SELECT 'KEEP: yc_rent_supplier_inspection (供应商考察)', COUNT(*) FROM yc_rent_supplier_inspection WHERE is_deleted = 0
UNION ALL SELECT 'KEEP: yc_rent_auth_user (登录账号)', COUNT(*) FROM yc_rent_auth_user
UNION ALL SELECT 'CLEAN: yc_rent_project (项目)', COUNT(*) FROM yc_rent_project
UNION ALL SELECT 'CLEAN: yc_rent_contract (租赁合同)', COUNT(*) FROM yc_rent_contract
UNION ALL SELECT 'CLEAN: yc_rent_purchase_contract (采购合同)', COUNT(*) FROM yc_rent_purchase_contract
UNION ALL SELECT 'CLEAN: yc_rent_asset (设备台账)', COUNT(*) FROM yc_rent_asset
UNION ALL SELECT 'CLEAN: yc_rent_rent_bill (收租单)', COUNT(*) FROM yc_rent_rent_bill
UNION ALL SELECT 'CLEAN: yc_rent_voucher (会计凭证)', COUNT(*) FROM yc_rent_voucher
UNION ALL SELECT 'CLEAN: yc_rent_task (系统待办)', COUNT(*) FROM yc_rent_task;
