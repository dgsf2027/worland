package top.aole.rent.modules.pcontract;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.payproof.service.PayProofService;
import top.aole.rent.modules.pcontract.dto.PcDtos;
import top.aole.rent.modules.pcontract.service.PurchaseContractService;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 合同→付款→凭证→发票 端到端(H2·MySQL 模式):真实 V9(凭证)+ V124(采购合同)迁移脚本、真实 VoucherService。
 * 验证:迁移 SQL 可执行;跨节点付款的核销与未付;付款凭证借贷平衡(借应付/贷银行);撤销付款生成红冲凭证;
 * 流水号/发票号唯一;收票对账。数据全部虚构。
 */
@SpringJUnitConfig(PurchaseContractFlowTest.Config.class)
@Sql({"/pc-flow-schema.sql", "/db/migration/V124__purchase_contract.sql"})
class PurchaseContractFlowTest {

    @Configuration
    @EnableTransactionManagement
    @ComponentScan(basePackageClasses = {PurchaseContractService.class, top.aole.rent.modules.finance.service.VoucherService.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {PurchaseContractService.class, top.aole.rent.modules.finance.service.VoucherService.class}))
    @MapperScan({"top.aole.rent.modules.pcontract.mapper", "top.aole.rent.modules.finance.mapper",
            "top.aole.rent.modules.purchase.mapper", "top.aole.rent.modules.contract.mapper",
            "top.aole.rent.modules.customer.mapper",
            "top.aole.rent.modules.billing.mapper", "top.aole.rent.common.audit"})
    static class Config {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:pcflow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean f = new MybatisSqlSessionFactoryBean();
            f.setDataSource(ds);
            return f.getObject();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        AuditLogService auditLogService(top.aole.rent.common.audit.AuditLogMapper m) {
            return new AuditLogService(m);
        }

        @Bean
        SupplierMapper supplierMapper() {
            SupplierMapper m = mock(SupplierMapper.class);
            Supplier s = new Supplier();
            s.setId(7L);
            s.setName("虚构供应商甲");
            when(m.selectById(anyLong())).thenReturn(s);
            return m;
        }

        @Bean
        PayProofService payProofService() {
            PayProofService p = mock(PayProofService.class);
            when(p.countMap(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Collections.emptyMap());
            return p;
        }
    }

    @Autowired
    private PurchaseContractService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void login() {
        UserContext.set(new CurrentUser(1005L, "财务", "财务", null));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    private PcDtos.TermInput term(String name, String amount) {
        PcDtos.TermInput t = new PcDtos.TermInput();
        t.setStageName(name);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    private Long newContract(String no) {
        PcDtos.CreateRequest r = new PcDtos.CreateRequest();
        r.setContractNo(no);
        r.setSupplierId(7L);
        r.setTotalAmount(new BigDecimal("100000"));
        r.setTerms(Arrays.asList(term("首付", "40000"), term("验收", "30000"), term("尾款", "30000")));
        return service.create(r);
    }

    private PcDtos.PaymentRequest pay(String amount, String serial) {
        PcDtos.PaymentRequest r = new PcDtos.PaymentRequest();
        r.setAmount(new BigDecimal(amount));
        r.setPaidDate(LocalDate.of(2026, 1, 5));
        r.setBankSerial(serial);
        r.setProofFileIds(Collections.singletonList(1L));
        return r;
    }

    private PcDtos.InvoiceRequest inv(String no, String ex, String tax, String total) {
        PcDtos.InvoiceRequest r = new PcDtos.InvoiceRequest();
        r.setInvoiceNo(no);
        r.setInvoiceType("专票");
        r.setIssueDate(LocalDate.of(2026, 1, 6));
        r.setAmountExTax(new BigDecimal(ex));
        r.setTaxAmount(new BigDecimal(tax));
        r.setTotalAmount(new BigDecimal(total));
        return r;
    }

    @Test
    void 合同_付款_凭证_收票_撤销_全链路() {
        Long pc = newContract("IT-001");

        // 一笔 55000 的付款跨「首付 40000」与「验收 15000」
        Long pid = service.pay(pc, pay("55000", "BANK-1"));
        PcDtos.Detail d = service.detail(pc);
        assertEquals(0, new BigDecimal("0").compareTo(d.getTerms().get(0).getUnpaid()));
        assertEquals(0, new BigDecimal("15000").compareTo(d.getTerms().get(1).getUnpaid()));
        assertEquals(0, new BigDecimal("45000").compareTo(d.getContract().getSummary().getUnpaid()));
        assertEquals(1, d.getPayments().size());
        assertEquals(2, d.getPayments().get(0).getAllocs().size());

        // 付款凭证:借 2202 应付账款 / 贷 1002 银行存款,借贷平衡
        List<Map<String, Object>> lines = jdbc.queryForList(
                "select l.account_code, l.direction, l.amount from yc_rent_voucher v join yc_rent_voucher_line l on l.voucher_id=v.id"
                        + " where v.source_doc_type='supplier_payment' and v.source_doc_id=? and v.is_reversal=0 order by l.id", pid);
        assertEquals(2, lines.size());
        assertEquals("2202", lines.get(0).get("account_code"));
        assertEquals("dr", lines.get(0).get("direction"));
        assertEquals("1002", lines.get(1).get("account_code"));
        assertEquals("cr", lines.get(1).get("direction"));
        assertEquals(0, new BigDecimal("55000").compareTo((BigDecimal) lines.get(0).get("amount")));
        assertEquals(0, new BigDecimal("55000").compareTo((BigDecimal) lines.get(1).get("amount")));
        assertTrue(d.getPayments().get(0).getVoucherId() != null);

        // 收票对账:已付 55000,收票 20000 → 已付未收票 35000
        service.addInvoice(pc, inv("IT-INV-1", "17699.12", "2300.88", "20000"));
        PcDtos.Summary s = service.detail(pc).getContract().getSummary();
        assertEquals(0, new BigDecimal("35000").compareTo(s.getPaidNotInvoiced()));
        assertEquals(0, new BigDecimal("80000").compareTo(s.getUninvoiced()));

        // 撤销付款:状态撤销、红冲凭证出现、节点额度释放
        service.voidPayment(pid, null);
        Long reversals = jdbc.queryForObject(
                "select count(*) from yc_rent_voucher where source_doc_type='supplier_payment' and source_doc_id=? and is_reversal=1",
                Long.class, pid);
        assertEquals(1L, reversals);
        PcDtos.Detail after = service.detail(pc);
        assertEquals("撤销", after.getPayments().get(0).getStatus());
        assertEquals(0, new BigDecimal("40000").compareTo(after.getTerms().get(0).getUnpaid()));
        assertEquals(0, new BigDecimal("100000").compareTo(after.getContract().getSummary().getUnpaid()));
    }

    @Test
    void 唯一约束_流水号与发票号与合同号() {
        Long pc = newContract("IT-002");
        service.pay(pc, pay("10000", "BANK-DUP"));
        assertThrows(BizException.class, () -> service.pay(pc, pay("10000", "BANK-DUP")));
        service.addInvoice(pc, inv("INV-DUP", "8849.56", "1150.44", "10000"));
        assertThrows(BizException.class, () -> service.addInvoice(pc, inv("INV-DUP", "8849.56", "1150.44", "10000")));
        assertThrows(BizException.class, () -> newContract("IT-002"));
    }

    @Test
    void 付清后结清_超付被拒() {
        Long pc = newContract("IT-003");
        service.pay(pc, pay("100000", "BANK-ALL"));
        assertEquals("已结清", service.detail(pc).getContract().getStatus());
        assertThrows(BizException.class, () -> service.pay(pc, pay("1", "BANK-MORE")));
    }

    private long insertPurchase(String no, long supplierId) {
        jdbc.update("insert into yc_rent_purchase_in(no, supplier_id, status, total_amount) values (?,?,?,?)",
                no, supplierId, "已入库", new BigDecimal("100000"));
        return jdbc.queryForObject("select id from yc_rent_purchase_in where no=?", Long.class, no);
    }

    private void insertPayable(long purchaseId, String stage, String due, String amount) {
        jdbc.update("insert into yc_rent_payable(purchase_in_id, stage, due_date, amount, status) values (?,?,?,?,?)",
                purchaseId, stage, LocalDate.parse(due), new BigDecimal(amount), "待付");
    }

    private BigDecimal sumPayable(long purchaseId, String status) {
        BigDecimal v = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from yc_rent_payable where purchase_in_id=? and status=?",
                BigDecimal.class, purchaseId, status);
        return v;
    }

    @Test
    void 关联采购单_付款自动冲销应付_撤销精确还原() {
        Long pc = newContract("IT-LINK-1");
        long pi = insertPurchase("PI-LINK-1", 7L);
        insertPayable(pi, "首付", "2026-02-01", "40000");
        insertPayable(pi, "验收", "2026-03-01", "30000");
        insertPayable(pi, "尾款", "2026-05-01", "30000");
        PcDtos.LinkRequest lr = new PcDtos.LinkRequest();
        lr.setPurchaseInIds(Collections.singletonList(pi));
        service.linkPurchases(pc, lr);
        assertEquals(1, service.detail(pc).getPurchases().size());
        assertEquals(0, new BigDecimal("100000").compareTo(service.detail(pc).getPurchases().get(0).getPendingPayable()));

        Long pid = service.pay(pc, pay("55000", "BANK-LINK-1"));
        // 首付整行已付;验收拆成 已付15000 + 待付15000;尾款不动
        assertEquals(0, new BigDecimal("55000").compareTo(sumPayable(pi, "已付")));
        assertEquals(0, new BigDecimal("45000").compareTo(sumPayable(pi, "待付")));
        assertEquals(4, jdbc.queryForObject("select count(*) from yc_rent_payable where purchase_in_id=?", Long.class, pi).intValue());
        assertEquals(0, new BigDecimal("45000").compareTo(service.detail(pc).getPurchases().get(0).getPendingPayable()));

        // 已冲销的单不能解除关联
        assertThrows(BizException.class, () -> service.unlinkPurchase(pc, pi));

        service.voidPayment(pid, null);
        // 还原:3 行全部待付,金额 40000/30000/30000,拆出的差额行被删除
        assertEquals(3, jdbc.queryForObject("select count(*) from yc_rent_payable where purchase_in_id=?", Long.class, pi).intValue());
        assertEquals(0, new BigDecimal("100000").compareTo(sumPayable(pi, "待付")));
        assertEquals(0, BigDecimal.ZERO.compareTo(sumPayable(pi, "已付")));
        // 撤销后可以解除关联
        service.unlinkPurchase(pc, pi);
        assertEquals(0, service.detail(pc).getPurchases().size());
    }

    @Test
    void 关联采购单_供应商必须一致() {
        Long pc = newContract("IT-LINK-2");
        long other = insertPurchase("PI-LINK-2", 8L);
        PcDtos.LinkRequest lr = new PcDtos.LinkRequest();
        lr.setPurchaseInIds(Collections.singletonList(other));
        BizException e = assertThrows(BizException.class, () -> service.linkPurchases(pc, lr));
        assertTrue(e.getMessage().contains("供应商"));
    }
}
