package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/** Delete only the user-selected rental contract so it can be entered again.
 * Keep the real wall, customer, purchasing, supplier invoices and supplier payments.
 * The original purchase_in.contract_id (NOT NULL) stays as a historical reference.
 */
public class V128__reset_yunshan_contract_001 extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 12801; }
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean ownTransaction = connection.getAutoCommit();
        if (ownTransaction) connection.setAutoCommit(false);
        try {
            reset(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            if (ownTransaction) connection.commit();
        } catch (Exception e) {
            if (ownTransaction) connection.rollback();
            throw e;
        } finally {
            if (ownTransaction) connection.setAutoCommit(true);
        }
    }
    private void reset(JdbcTemplate db) {
        List<Long> contracts = db.queryForList("SELECT c.id FROM yc_rent_contract c JOIN yc_rent_customer u ON u.id=c.customer_id "
                + "WHERE c.is_deleted=0 AND TRIM(c.no)='001' AND TRIM(u.name)='广东云山供应链科技有限公司' "
                + "AND c.term_months=36 FOR UPDATE", Long.class);
        if (contracts.isEmpty()) return;
        if (contracts.size() != 1) throw new IllegalStateException("V128: ambiguous contract 001; no changes");
        long id = contracts.get(0);
        Set<Long> assets = new LinkedHashSet<>(db.queryForList("SELECT id FROM yc_rent_asset WHERE is_deleted=0 AND "
                + "(contract_id=? OR (contract_id IS NULL AND TRIM(contract_no)='001'))", Long.class, id));
        assets.addAll(db.queryForList("SELECT a.id FROM yc_rent_asset a JOIN yc_rent_contract_asset ca ON ca.asset_id=a.id "
                + "WHERE a.is_deleted=0 AND ca.is_deleted=0 AND ca.contract_id=?", Long.class, id));
        if (assets.size() > 1) throw new IllegalStateException("V128: contract has multiple devices; no changes");
        String assetIds = assets.isEmpty() ? "(SELECT 1 WHERE 1=0)" : assets.stream().map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
        if (!db.queryForList("SELECT id FROM yc_rent_asset WHERE id IN " + assetIds
                + " AND (category <> '播种墙' OR (contract_id IS NOT NULL AND contract_id<>?))", Long.class, id).isEmpty()
                || !db.queryForList("SELECT id FROM yc_rent_contract_asset WHERE is_deleted=0 AND asset_id IN " + assetIds
                + " AND contract_id<>?", Long.class, id).isEmpty()) {
            throw new IllegalStateException("V128: unexpected/shared device; no changes");
        }
        if (!db.queryForList("SELECT id FROM yc_rent_inv_rental WHERE is_deleted=0 AND contract_id=?", Long.class, id).isEmpty()) {
            throw new IllegalStateException("V128: warehouse rental also references this contract; no changes");
        }
        String bills = "(SELECT id FROM yc_rent_rent_bill WHERE contract_id=" + id + ")";
        String vouchers = "(SELECT id FROM yc_rent_voucher WHERE source_doc_type='rent_bill' AND source_doc_id IN " + bills + ")";
        db.update("DELETE FROM yc_rent_ledger_book WHERE voucher_id IN " + vouchers);
        db.update("UPDATE yc_rent_voucher_line SET is_deleted=1 WHERE voucher_id IN " + vouchers);
        db.update("UPDATE yc_rent_voucher SET is_deleted=1 WHERE source_doc_type='rent_bill' AND source_doc_id IN " + bills);
        // New contract 001 will generate RB-001-Pxx again; release those unique bill numbers too.
        db.update("UPDATE yc_rent_rent_bill SET is_deleted=1,bill_no=CONCAT(LEFT(bill_no,32),'#REENTER',id) WHERE contract_id=?", id);
        db.update("DELETE FROM yc_rent_rent_schedule WHERE contract_id=?", id);
        db.update("DELETE FROM yc_rent_overdue_case WHERE contract_id=?", id);
        // Contract payment terms are historical purchasing terms (V118); preserve them for existing payables.
        for (String table : Arrays.asList("contract_asset", "deposit_ledger", "contract_change", "contract_boq", "repossess_order")) {
            db.update("UPDATE yc_rent_" + table + " SET is_deleted=1 WHERE contract_id=?", id);
        }
        db.update("UPDATE yc_rent_file_object SET is_deleted=1 WHERE biz_type='contract' AND biz_id=?", id);
        db.update("UPDATE yc_rent_asset SET contract_id=NULL,contract_no=NULL,current_holder_customer_id=NULL,"
                + "status=CASE WHEN status='在租' THEN '投放' ELSE status END,"
                + "override_alloc_rent=NULL,override_cumulative_rent=NULL,override_return_rate=NULL,"
                + "override_in_service_days=NULL,override_idle_days=NULL WHERE id IN " + assetIds);
        for (Long asset : assets) {
            db.update("INSERT INTO yc_rent_asset_event(asset_id,event_type,ref_doc_type,ref_doc_id,biz_time,remark) "
                    + "VALUES(?,'再投放','contract',?,CURRENT_TIMESTAMP,'用户要求重录001；实际未回款，清理旧租赁关系')", asset, id);
        }
        db.update("UPDATE yc_rent_purchase_contract SET rent_contract_id=NULL WHERE rent_contract_id=?", id);
        db.update("UPDATE yc_rent_customer_followup SET contract_id=NULL WHERE contract_id=?", id);
        // Free the unique contract number, including soft-deleted records, for re-entry.
        db.update("UPDATE yc_rent_contract SET is_deleted=1,status='已作废',no=? WHERE id=?", "001#REENTER" + id, id);
        db.update("INSERT INTO yc_rent_audit_log(action,target_type,target_id,result,operator_name,operator_role,detail) VALUES(?,?,?,?,?,?,?)",
                "清理合同待重新录入", "contract", id, "EXECUTED", "V128 数据迁移", "系统",
                "按用户截图清理广东云山供应链科技有限公司的36期合同001，实际未回款。清理租金计划/收租/对应凭证；"
                        + "保留真实设备、客户与采购付款发票；编号001已释放。采购入库单保留历史合同ID，重录后需重新关联。");
    }
}
