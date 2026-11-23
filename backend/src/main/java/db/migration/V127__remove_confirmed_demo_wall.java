package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/** User-confirmed screenshot: unnumbered 播种墙 V2 标准型, market 1,200,000 / cost 180,000.
 * Never infer a target from the customer or status alone. Preflight every shared document
 * before any write. DML only, one transaction, idempotent; keep original financial rows
 * as deleted/void/revoked records and preserve payment evidence for audit.
 */
public class V127__remove_confirmed_demo_wall extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 12701; }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean ownTransaction = connection.getAutoCommit();
        if (ownTransaction) connection.setAutoCommit(false);
        try {
            clean(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            if (ownTransaction) connection.commit();
        } catch (Exception e) {
            if (ownTransaction) connection.rollback();
            throw e;
        } finally {
            if (ownTransaction) connection.setAutoCommit(true);
        }
    }

    private void clean(JdbcTemplate db) {
        Set<Long> assets = ids(db, "SELECT id FROM yc_rent_asset WHERE is_deleted=0 "
                + "AND TRIM(category)='播种墙' AND TRIM(model)='播种墙 V2 标准型' "
                + "AND market_price=1200000 AND purchase_price=180000 AND status='在租' "
                + "AND (contract_no IS NULL OR TRIM(contract_no)='') FOR UPDATE");
        if (assets.isEmpty()) return;
        if (assets.size() != 1) throw new IllegalStateException("V127: multiple matching demo devices; no records changed");
        String a = in(assets);
        Set<Long> purchases = ids(db, "SELECT purchase_in_id FROM yc_rent_asset WHERE id IN " + a);
        purchases.addAll(ids(db, "SELECT purchase_in_id FROM yc_rent_purchase_item WHERE asset_id IN " + a));
        purchases.addAll(ids(db, "SELECT purchase_in_id FROM yc_rent_payable WHERE asset_id IN " + a));
        Set<Long> contracts = ids(db, "SELECT contract_id FROM yc_rent_asset WHERE id IN " + a);
        // Include old/deleted links: the screenshot may be an orphan left by an earlier cleanup.
        contracts.addAll(ids(db, "SELECT contract_id FROM yc_rent_contract_asset WHERE asset_id IN " + a));
        contracts.addAll(ids(db, "SELECT contract_id FROM yc_rent_purchase_in WHERE id IN " + in(purchases)));
        String c = in(contracts);
        purchases.addAll(ids(db, "SELECT id FROM yc_rent_purchase_in WHERE contract_id IN " + c));
        String p = in(purchases);
        Set<Long> payables = ids(db, "SELECT id FROM yc_rent_payable WHERE asset_id IN " + a + " OR purchase_in_id IN " + p);
        String y = in(payables);
        Set<Long> pcs = ids(db, "SELECT pc_id FROM yc_rent_purchase_in WHERE id IN " + p);
        pcs.addAll(ids(db, "SELECT id FROM yc_rent_purchase_contract WHERE rent_contract_id IN " + c));
        pcs.addAll(ids(db, "SELECT pc_id FROM yc_rent_supplier_payment WHERE id IN "
                + "(SELECT payment_id FROM yc_rent_supplier_payment_payable WHERE payable_id IN " + y + ")"));
        String pc = in(pcs);
        Set<Long> payments = ids(db, "SELECT id FROM yc_rent_supplier_payment WHERE pc_id IN " + pc);
        String payment = in(payments);

        // A shared document must never pull a real device or payment into this cleanup.
        guard(db, "SELECT id FROM yc_rent_asset WHERE is_deleted=0 AND id NOT IN " + a
                + " AND (contract_id IN " + c + " OR purchase_in_id IN " + p + ")", "shared device");
        guard(db, "SELECT id FROM yc_rent_contract_asset WHERE is_deleted=0 AND contract_id IN " + c
                + " AND asset_id NOT IN " + a, "shared rental contract");
        guard(db, "SELECT id FROM yc_rent_purchase_item WHERE is_deleted=0 AND purchase_in_id IN " + p
                + " AND (asset_id IS NULL OR asset_id NOT IN " + a + ")", "shared/unassigned purchase item");
        guard(db, "SELECT id FROM yc_rent_payable WHERE is_deleted=0 AND purchase_in_id IN " + p
                + " AND asset_id IS NOT NULL AND asset_id NOT IN " + a, "shared payable");
        guard(db, "SELECT id FROM yc_rent_purchase_in WHERE is_deleted=0 AND pc_id IN " + pc
                + " AND id NOT IN " + p, "shared purchase contract");
        guard(db, "SELECT id FROM yc_rent_purchase_contract WHERE id IN " + pc
                + " AND rent_contract_id IS NOT NULL AND rent_contract_id NOT IN " + c, "other rental contract");
        guard(db, "SELECT id FROM yc_rent_supplier_payment_payable WHERE payment_id IN " + payment
                + " AND (payable_id NOT IN " + y + " OR (remainder_id IS NOT NULL AND remainder_id NOT IN " + y + "))", "shared payment");
        guard(db, "SELECT id FROM yc_rent_inv_rental WHERE is_deleted=0 AND contract_id IN " + c, "warehouse rental");

        Set<Long> bills = ids(db, "SELECT id FROM yc_rent_rent_bill WHERE contract_id IN " + c);
        Set<Long> lines = ids(db, "SELECT id FROM yc_rent_transfer_order_line WHERE asset_id IN " + a);
        Set<Long> transfers = ids(db, "SELECT transfer_order_id FROM yc_rent_transfer_order_line WHERE id IN " + in(lines));
        Set<Long> vouchers = ids(db, "SELECT id FROM yc_rent_voucher WHERE "
                + "(source_doc_type='depreciation' AND source_doc_id IN " + a + ") OR "
                + "(source_doc_type='purchase_in' AND source_doc_id IN " + p + ") OR "
                + "(source_doc_type='rent_bill' AND source_doc_id IN " + in(bills) + ") OR "
                + "(source_doc_type='transfer_line' AND source_doc_id IN " + in(lines) + ") OR "
                + "(source_doc_type='supplier_payment' AND source_doc_id IN " + payment + ")");
        vouchers.addAll(ids(db, "SELECT voucher_id FROM yc_rent_supplier_payment WHERE id IN " + payment));
        vouchers.addAll(ids(db, "SELECT voucher_id FROM yc_rent_asset_depreciation_line WHERE asset_id IN " + a));
        // A voucher id must not be shared with a surviving business record.
        guard(db, "SELECT id FROM yc_rent_supplier_payment WHERE id NOT IN " + payment
                + " AND voucher_id IN " + in(vouchers), "shared payment voucher");
        guard(db, "SELECT id FROM yc_rent_asset_depreciation_line WHERE is_deleted=0 AND asset_id NOT IN " + a
                + " AND voucher_id IN " + in(vouchers), "shared depreciation voucher");

        db.update("DELETE FROM yc_rent_ledger_book WHERE voucher_id IN " + in(vouchers));
        soft(db, "voucher_line", "voucher_id IN " + in(vouchers));
        soft(db, "voucher", "id IN " + in(vouchers));
        // Keep no-soft-delete financial tables as void/revoked historical evidence.
        db.update("UPDATE yc_rent_supplier_payment SET status='撤销' WHERE id IN " + payment);
        db.update("UPDATE yc_rent_purchase_invoice SET status='作废' WHERE pc_id IN " + pc);
        db.update("DELETE FROM yc_rent_supplier_payment_alloc WHERE payment_id IN " + payment);
        db.update("DELETE FROM yc_rent_supplier_payment_payable WHERE payment_id IN " + payment);
        soft(db, "purchase_contract", "id IN " + pc);
        soft(db, "payable", "id IN " + y);
        soft(db, "purchase_item", "purchase_in_id IN " + p + " OR asset_id IN " + a);
        soft(db, "purchase_in", "id IN " + p);
        soft(db, "rent_bill", "id IN " + in(bills));
        db.update("DELETE FROM yc_rent_rent_schedule WHERE contract_id IN " + c);
        db.update("DELETE FROM yc_rent_overdue_case WHERE contract_id IN " + c + " OR rent_bill_id IN " + in(bills));
        for (String table : Arrays.asList("repossess_order", "deposit_ledger", "contract_change", "contract_boq", "contract_payment_term")) {
            soft(db, table, "contract_id IN " + c);
        }
        soft(db, "contract_asset", "contract_id IN " + c + " OR asset_id IN " + a);
        soft(db, "contract", "id IN " + c);
        soft(db, "file_object", "(biz_type='asset_bom' AND biz_id IN (SELECT id FROM yc_rent_asset_bom WHERE asset_id IN " + a + "))"
                + " OR (biz_type='contract' AND biz_id IN " + c + ")");
        for (String table : Arrays.asList("asset_bom", "asset_boq", "asset_event", "asset_payment_term", "asset_depreciation_line", "maintenance", "stock_diff")) {
            soft(db, table, "asset_id IN " + a);
        }
        soft(db, "transfer_order_line", "id IN " + in(lines));
        for (Long transfer : transfers) {
            db.update("UPDATE yc_rent_transfer_order SET asset_count=(SELECT COUNT(*) FROM yc_rent_transfer_order_line WHERE is_deleted=0 AND transfer_order_id=?),"
                    + " total_price=(SELECT COALESCE(SUM(transfer_price),0) FROM yc_rent_transfer_order_line WHERE is_deleted=0 AND transfer_order_id=?),"
                    + " total_gain=(SELECT COALESCE(SUM(gain),0) FROM yc_rent_transfer_order_line WHERE is_deleted=0 AND transfer_order_id=?) WHERE id=?",
                    transfer, transfer, transfer, transfer);
            soft(db, "transfer_order", "id=" + transfer + " AND asset_count=0");
        }
        db.update("UPDATE yc_rent_transfer_order SET contract_id=NULL WHERE contract_id IN " + c);
        // Preserve original identifiers for traceability; no need to recycle a fictional serial.
        soft(db, "asset", "id IN " + a);
        db.update("INSERT INTO yc_rent_audit_log(action,target_type,target_id,result,operator_name,operator_role,detail) VALUES(?,?,?,?,?,?,?)",
                "删除确认虚构设备", "asset", assets.iterator().next(), "EXECUTED", "V127 数据迁移", "系统",
                "用户截图确认：播种墙 V2 标准型，市场价120万/集采价18万；租赁合同=" + c + "；采购合同=" + pc + "；付款=" + payment
                        + "。逻辑删除设备及关联业务，发票作废、付款撤销；回单与审计留存。");
    }

    private static Set<Long> ids(JdbcTemplate db, String sql) {
        return db.query(sql, (rs, row) -> { long id = rs.getLong(1); return rs.wasNull() ? null : id; })
                .stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    private static String in(Set<Long> ids) {
        return ids.isEmpty() ? "(SELECT 1 WHERE 1=0)" : ids.stream().map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
    }
    private static void guard(JdbcTemplate db, String sql, String label) {
        if (!ids(db, sql).isEmpty()) throw new IllegalStateException("V127 refused: " + label + "; no records changed");
    }
    private static void soft(JdbcTemplate db, String table, String where) {
        db.update("UPDATE yc_rent_" + table + " SET is_deleted=1 WHERE is_deleted=0 AND (" + where + ")");
    }
}
