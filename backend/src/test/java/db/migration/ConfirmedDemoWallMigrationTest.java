package db.migration;

import org.flywaydb.core.api.migration.Context;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmedDemoWallMigrationTest {
    JdbcDataSource ds;
    JdbcTemplate db;
    @BeforeEach void schema() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cleanup" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        table("asset", "serial_no varchar(64),category varchar(32),model varchar(128),market_price decimal(18,2),purchase_price decimal(18,2),status varchar(16),contract_no varchar(64),contract_id bigint,purchase_in_id bigint");
        table("contract", "no varchar(64)");
        table("contract_asset", "contract_id bigint,asset_id bigint");
        table("purchase_in", "contract_id bigint,pc_id bigint");
        table("purchase_item", "purchase_in_id bigint,asset_id bigint");
        table("payable", "purchase_in_id bigint,asset_id bigint");
        table("purchase_contract", "rent_contract_id bigint");
        table("supplier_payment", "pc_id bigint,status varchar(8),voucher_id bigint");
        table("supplier_payment_payable", "payment_id bigint,payable_id bigint,remainder_id bigint");
        table("supplier_payment_alloc", "payment_id bigint");
        table("purchase_invoice", "pc_id bigint,status varchar(8)");
        table("inv_rental", "contract_id bigint");
        table("rent_bill", "contract_id bigint");
        table("transfer_order_line", "asset_id bigint,transfer_order_id bigint,transfer_price decimal(18,2),gain decimal(18,2)");
        table("transfer_order", "contract_id bigint,asset_count int,total_price decimal(18,2),total_gain decimal(18,2)");
        table("voucher", "source_doc_type varchar(32),source_doc_id bigint");
        table("voucher_line", "voucher_id bigint");
        table("ledger_book", "voucher_id bigint");
        table("asset_depreciation_line", "asset_id bigint,voucher_id bigint");
        table("rent_schedule", "contract_id bigint");
        table("overdue_case", "contract_id bigint,rent_bill_id bigint");
        for (String t : new String[]{"repossess_order", "deposit_ledger", "contract_change", "contract_boq", "contract_payment_term"}) table(t, "contract_id bigint");
        for (String t : new String[]{"asset_bom", "asset_boq", "asset_event", "asset_payment_term", "maintenance", "stock_diff"}) table(t, "asset_id bigint");
        table("file_object", "biz_type varchar(32),biz_id bigint");
        table("audit_log", "action varchar(32),target_type varchar(32),target_id bigint,result varchar(16),operator_name varchar(64),operator_role varchar(32),detail varchar(500)");
        // Same model / same customer must not be enough to delete another real device.
        db.update("INSERT INTO yc_rent_asset(id,serial_no,category,model,market_price,purchase_price,status,contract_no) VALUES"
                + "(1,'old-demo','播种墙','播种墙 V2 标准型',1200000,180000,'在租',NULL),"
                + "(2,'GZJTN20260820LGY01','货架','重型货架',280000,280000,'在租','货架001'),"
                + "(3,'mydl-744SA2X','播种墙','播种墙 整机(整机)',1551600,1336000,'在租','001'),"
                + "(4,'similar-real','播种墙','播种墙 V2 标准型',1200000,180000,'在租','真实编号')");
    }
    void table(String name, String cols) {
        db.execute("CREATE TABLE yc_rent_" + name + "(id bigint AUTO_INCREMENT PRIMARY KEY,is_deleted int DEFAULT 0," + cols + ")");
    }
    void run() throws Exception {
        try (Connection connection = ds.getConnection()) {
            Context ctx = mock(Context.class);
            when(ctx.getConnection()).thenReturn(connection);
            new V127__remove_confirmed_demo_wall().migrate(ctx);
        }
    }
    int deleted(String table, long id) {
        return db.queryForObject("SELECT is_deleted FROM yc_rent_" + table + " WHERE id=?", Integer.class, id);
    }
    void linkedData() {
        // Orphan display: current contract id is null, historical link still identifies its contract.
        db.update("INSERT INTO yc_rent_contract(id,no) VALUES(10,'old-demo-contract'),(20,'real-contract')");
        db.update("INSERT INTO yc_rent_contract_asset(id,contract_id,asset_id,is_deleted) VALUES(1,10,1,1),(2,20,2,0)");
        db.update("INSERT INTO yc_rent_purchase_in(id,contract_id,pc_id) VALUES(10,10,10),(20,20,20)");
        db.update("INSERT INTO yc_rent_purchase_item(id,purchase_in_id,asset_id) VALUES(10,10,1),(20,20,2)");
        db.update("INSERT INTO yc_rent_payable(id,purchase_in_id,asset_id) VALUES(10,10,1),(20,20,2)");
        db.update("INSERT INTO yc_rent_purchase_contract(id,rent_contract_id) VALUES(10,10),(20,20)");
        db.update("INSERT INTO yc_rent_supplier_payment(id,pc_id,status,voucher_id) VALUES(10,10,'有效',10),(20,20,'有效',20)");
        db.update("INSERT INTO yc_rent_purchase_invoice(id,pc_id,status) VALUES(10,10,'有效'),(20,20,'有效')");
        db.update("INSERT INTO yc_rent_supplier_payment_alloc(id,payment_id) VALUES(10,10),(20,20)");
        db.update("INSERT INTO yc_rent_supplier_payment_payable(id,payment_id,payable_id) VALUES(10,10,10),(20,20,20)");
        db.update("INSERT INTO yc_rent_voucher(id,source_doc_type,source_doc_id) VALUES(10,'supplier_payment',10),(20,'supplier_payment',20),(11,'depreciation',1)");
        db.update("INSERT INTO yc_rent_voucher_line(id,voucher_id) VALUES(10,10),(20,20),(11,11)");
        db.update("INSERT INTO yc_rent_ledger_book(id,voucher_id) VALUES(10,10),(20,20),(11,11)");
        db.update("INSERT INTO yc_rent_asset_depreciation_line(id,asset_id,voucher_id) VALUES(11,1,11),(20,2,20)");
        db.update("INSERT INTO yc_rent_rent_schedule(id,contract_id) VALUES(10,10),(20,20)");
        db.update("INSERT INTO yc_rent_rent_bill(id,contract_id) VALUES(10,10),(20,20)");
        db.update("INSERT INTO yc_rent_transfer_order(id,asset_count,total_price,total_gain) VALUES(10,2,300,30)");
        db.update("INSERT INTO yc_rent_transfer_order_line(id,asset_id,transfer_order_id,transfer_price,gain) VALUES(10,1,10,100,10),(20,2,10,200,20)");
    }
    @Test void removesOnlyConfirmedDeviceAndItsFinancialChainAndIsIdempotent() throws Exception {
        linkedData(); run(); run();
        assertEquals(1, deleted("asset", 1));
        for (long id : new long[]{2,3,4}) assertEquals(0, deleted("asset", id));
        for (String table : new String[]{"contract","purchase_in","purchase_item","purchase_contract","payable","rent_bill","voucher","voucher_line"}) {
            assertEquals(1, deleted(table,10), table);
            assertEquals(0, deleted(table,20), table);
        }
        assertEquals("撤销", db.queryForObject("SELECT status FROM yc_rent_supplier_payment WHERE id=10", String.class));
        assertEquals("作废", db.queryForObject("SELECT status FROM yc_rent_purchase_invoice WHERE id=10", String.class));
        assertEquals("有效", db.queryForObject("SELECT status FROM yc_rent_supplier_payment WHERE id=20", String.class));
        assertEquals("有效", db.queryForObject("SELECT status FROM yc_rent_purchase_invoice WHERE id=20", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM yc_rent_ledger_book", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM yc_rent_rent_schedule", Integer.class));
        assertEquals(1, db.queryForObject("SELECT asset_count FROM yc_rent_transfer_order WHERE id=10", Integer.class));
        assertEquals(200, db.queryForObject("SELECT total_price FROM yc_rent_transfer_order WHERE id=10", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM yc_rent_audit_log", Integer.class));
    }
    @Test void unlinkedDeviceCanBeRemovedWithoutTouchingOtherData() throws Exception {
        run(); assertEquals(1,deleted("asset",1)); assertEquals(0,deleted("asset",2));
    }
    @Test void missingTargetIsNoOp() throws Exception {
        db.update("UPDATE yc_rent_asset SET market_price=200000 WHERE id=1");
        run(); assertEquals(0,deleted("asset",1));
    }
    @Test void duplicateMatchesFailBeforeAnyMutation() {
        db.update("UPDATE yc_rent_asset SET contract_no=NULL WHERE id=4");
        assertThrows(IllegalStateException.class,this::run);
        assertEquals(0,deleted("asset",1)); assertEquals(0,deleted("asset",4));
    }
    @Test void sharedRentalContractFailsBeforeAnyMutation() {
        linkedData(); db.update("INSERT INTO yc_rent_contract_asset(contract_id,asset_id) VALUES(10,2)");
        assertThrows(IllegalStateException.class,this::run);
        assertEquals(0,deleted("asset",1)); assertEquals(0,deleted("voucher",10));
    }
    @Test void sharedPurchaseContractFailsBeforeAnyMutation() {
        linkedData(); db.update("UPDATE yc_rent_purchase_in SET pc_id=10 WHERE id=20");
        assertThrows(IllegalStateException.class,this::run);
        assertEquals(0,deleted("asset",1)); assertEquals(0,deleted("purchase_contract",10));
    }
    @Test void sharedPaymentFailsBeforeAnyMutation() {
        linkedData(); db.update("INSERT INTO yc_rent_supplier_payment_payable(payment_id,payable_id) VALUES(10,20)");
        assertThrows(IllegalStateException.class,this::run); assertEquals(0,deleted("asset",1));
    }
    @Test void errorAfterWritesRollsBackEntireCleanup() {
        linkedData(); db.execute("ALTER TABLE yc_rent_audit_log ADD CONSTRAINT fail_audit CHECK (target_id <> 1)");
        assertThrows(Exception.class,this::run);
        assertEquals(0,deleted("asset",1)); assertEquals(0,deleted("voucher",10));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM yc_rent_ledger_book",Integer.class));
        assertEquals("有效",db.queryForObject("SELECT status FROM yc_rent_supplier_payment WHERE id=10",String.class));
    }
}
