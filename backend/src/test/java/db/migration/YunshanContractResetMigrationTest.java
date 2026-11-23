package db.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YunshanContractResetMigrationTest {
    ConfirmedDemoWallMigrationTest fixture;
    JdbcTemplate db;
    @BeforeEach void setup() {
        fixture = new ConfirmedDemoWallMigrationTest(); fixture.schema(); fixture.linkedData(); db = fixture.db;
        fixture.table("customer", "name varchar(128)");
        fixture.table("customer_followup", "contract_id bigint");
        add("rent_bill", "bill_no varchar(64)");
        db.execute("CREATE UNIQUE INDEX bill_no_unique ON yc_rent_rent_bill(bill_no)");
        db.update("UPDATE yc_rent_rent_bill SET bill_no='RB-001-P01' WHERE id=10");
        add("contract", "customer_id bigint", "term_months int", "status varchar(16)");
        db.execute("CREATE UNIQUE INDEX contract_no_unique ON yc_rent_contract(no)");
        add("asset", "current_holder_customer_id bigint", "override_alloc_rent decimal(18,2)",
                "override_cumulative_rent decimal(18,2)", "override_return_rate decimal(18,8)", "override_in_service_days int", "override_idle_days int");
        add("asset_event", "event_type varchar(16)", "ref_doc_type varchar(32)", "ref_doc_id bigint", "biz_time timestamp", "remark varchar(255)");
        db.update("INSERT INTO yc_rent_customer(id,name) VALUES(1,'广东云山供应链科技有限公司')");
        db.update("UPDATE yc_rent_contract SET no='001',customer_id=1,term_months=36,status='生效' WHERE id=10");
        db.update("UPDATE yc_rent_contract SET no='货架001',customer_id=1,term_months=60,status='生效' WHERE id=20");
        db.update("UPDATE yc_rent_contract_asset SET asset_id=3,is_deleted=0 WHERE id=1");
        db.update("UPDATE yc_rent_asset SET contract_id=10,purchase_in_id=10,current_holder_customer_id=1,override_cumulative_rent=50000 WHERE id=3");
        db.update("UPDATE yc_rent_asset SET contract_id=20 WHERE id=2");
        db.update("UPDATE yc_rent_purchase_item SET asset_id=3 WHERE id=10");
        db.update("UPDATE yc_rent_payable SET asset_id=3 WHERE id=10");
        db.update("INSERT INTO yc_rent_voucher(id,source_doc_type,source_doc_id) VALUES(30,'rent_bill',10)");
        db.update("INSERT INTO yc_rent_voucher_line(id,voucher_id) VALUES(30,30)");
        db.update("INSERT INTO yc_rent_ledger_book(id,voucher_id) VALUES(30,30)");
        db.update("INSERT INTO yc_rent_deposit_ledger(id,contract_id) VALUES(10,10)");
    }
    void add(String table, String... columns) {
        for (String column : columns) db.execute("ALTER TABLE yc_rent_" + table + " ADD " + column);
    }
    void run() throws Exception {
        try(Connection c = fixture.ds.getConnection()) {
            Context ctx = mock(Context.class); when(ctx.getConnection()).thenReturn(c);
            new V128__reset_yunshan_contract_001().migrate(ctx);
        }
    }
    @Test void clearsOnlyRentalSideAndFreesNumberWhileKeepingEquipmentAndProcurement() throws Exception {
        run(); run();
        assertEquals(1,fixture.deleted("contract",10)); assertEquals(0,fixture.deleted("contract",20));
        assertEquals(0,fixture.deleted("asset",3)); assertEquals(0,fixture.deleted("asset",2));
        assertNull(db.queryForObject("SELECT contract_id FROM yc_rent_asset WHERE id=3",Long.class));
        assertNull(db.queryForObject("SELECT override_cumulative_rent FROM yc_rent_asset WHERE id=3",Long.class));
        assertEquals("投放",db.queryForObject("SELECT status FROM yc_rent_asset WHERE id=3",String.class));
        assertEquals(1,fixture.deleted("rent_bill",10)); assertEquals(0,fixture.deleted("rent_bill",20));
        assertEquals(1,fixture.deleted("voucher",30)); assertEquals(0,fixture.deleted("voucher",10));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM yc_rent_rent_schedule",Integer.class));
        for (String table : new String[]{"purchase_in","purchase_item","payable","purchase_contract"}) assertEquals(0,fixture.deleted(table,10),table);
        assertEquals("有效",db.queryForObject("SELECT status FROM yc_rent_purchase_invoice WHERE id=10",String.class));
        assertEquals("有效",db.queryForObject("SELECT status FROM yc_rent_supplier_payment WHERE id=10",String.class));
        assertEquals(10L,db.queryForObject("SELECT contract_id FROM yc_rent_purchase_in WHERE id=10",Long.class));
        assertNull(db.queryForObject("SELECT rent_contract_id FROM yc_rent_purchase_contract WHERE id=10",Long.class));
        db.update("INSERT INTO yc_rent_contract(id,no,customer_id,term_months) VALUES(99,'001',1,36)");
        db.update("INSERT INTO yc_rent_rent_bill(id,contract_id,bill_no) VALUES(99,99,'RB-001-P01')");
    }
    @Test void unrelatedCustomerIsNotDeleted() throws Exception {
        db.update("UPDATE yc_rent_customer SET name='其他客户'"); run(); assertEquals(0,fixture.deleted("contract",10));
    }
    @Test void bothMigrationsTogetherRemoveDemoButKeepRealWallAndRack() throws Exception {
        fixture.run(); run();
        assertEquals(1,fixture.deleted("asset",1));
        assertEquals(0,fixture.deleted("asset",2));
        assertEquals(0,fixture.deleted("asset",3));
        assertEquals(1,fixture.deleted("contract",10));
        assertEquals(0,fixture.deleted("contract",20));
        assertEquals("有效",db.queryForObject("SELECT status FROM yc_rent_supplier_payment WHERE id=10",String.class));
    }
    @Test void unexpectedDeviceOrSecondDeviceStopsBeforeWrites() {
        db.update("INSERT INTO yc_rent_contract_asset(contract_id,asset_id) VALUES(10,2)");
        assertThrows(IllegalStateException.class,this::run); assertEquals(0,fixture.deleted("contract",10));
    }
    @Test void transactionRollsBackIfAuditFails() {
        db.execute("ALTER TABLE yc_rent_audit_log ADD CONSTRAINT fail_audit CHECK (target_id<>10)");
        assertThrows(Exception.class,this::run);
        assertEquals(0,fixture.deleted("contract",10)); assertEquals(0,fixture.deleted("voucher",30));
        assertEquals(10L,db.queryForObject("SELECT contract_id FROM yc_rent_asset WHERE id=3",Long.class));
    }
}
