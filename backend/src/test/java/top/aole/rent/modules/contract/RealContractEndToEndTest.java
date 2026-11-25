package top.aole.rent.modules.contract;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.DepositLedger;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.DepositLedgerMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.contract.service.ProjectPanoramaService;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.domain.PurchaseInvoice;
import top.aole.rent.modules.pcontract.domain.SupplierPayment;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseInvoiceMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实双合同端到端利润全景验证：
 * - 上游采购合同：金铁牛重型货架 ¥280,000（原价 284,838，优惠 4,838），审核通过后 40%/30%/30% 月结，5 年质保
 * - 下游租赁合同：云山快仓 60 期，月租 ¥7,418.29，0 押金，1.00 元留购，3% 专票，每月 15 日收租
 * 验证老板视角：毛利 ¥165,098.40、估 3% 增值税 ¥12,963.99、税后净利 ¥152,134.41、第 38 期回本、最大垫资峰值 < ¥280,000、GP/LP 成本脱敏。
 */
class RealContractEndToEndTest {

    private ContractMapper contractMapper;
    private CustomerMapper customerMapper;
    private RentScheduleMapper scheduleMapper;
    private RentBillMapper billMapper;
    private DepositLedgerMapper depositMapper;
    private PurchaseContractMapper pcMapper;
    private PurchaseContractTermMapper pcTermMapper;
    private SupplierPaymentMapper spMapper;
    private PurchaseInvoiceMapper piMapper;
    private PurchaseInMapper purchaseInMapper;
    private PayableMapper payableMapper;
    private SupplierMapper supplierMapper;
    private ContractAssetMapper contractAssetMapper;
    private AssetMapper assetMapper;
    private ProjectPanoramaService panoramaService;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : Arrays.asList(
                Contract.class, Customer.class, RentSchedule.class, RentBill.class,
                DepositLedger.class, PurchaseContract.class, PurchaseContractTerm.class,
                SupplierPayment.class, PurchaseInvoice.class, PurchaseIn.class,
                Payable.class, Supplier.class, ContractAsset.class, Asset.class)) {
            TableInfoHelper.initTableInfo(a, c);
        }
    }

    @BeforeEach
    void setUp() {
        contractMapper = mock(ContractMapper.class);
        customerMapper = mock(CustomerMapper.class);
        scheduleMapper = mock(RentScheduleMapper.class);
        billMapper = mock(RentBillMapper.class);
        depositMapper = mock(DepositLedgerMapper.class);
        pcMapper = mock(PurchaseContractMapper.class);
        pcTermMapper = mock(PurchaseContractTermMapper.class);
        spMapper = mock(SupplierPaymentMapper.class);
        piMapper = mock(PurchaseInvoiceMapper.class);
        purchaseInMapper = mock(PurchaseInMapper.class);
        payableMapper = mock(PayableMapper.class);
        supplierMapper = mock(SupplierMapper.class);
        contractAssetMapper = mock(ContractAssetMapper.class);
        assetMapper = mock(AssetMapper.class);

        panoramaService = new ProjectPanoramaService(
                contractMapper, customerMapper, scheduleMapper, billMapper, depositMapper,
                pcMapper, pcTermMapper, spMapper, piMapper, purchaseInMapper, payableMapper,
                supplierMapper, contractAssetMapper, assetMapper);

        // 组装真实双合同数据
        Contract lease = new Contract();
        lease.setId(100L);
        lease.setNo("YAOSHI-货架租赁-2026001");
        lease.setCustomerId(20L);
        lease.setNature("设备融资租赁");
        lease.setStatus("生效");
        lease.setSignDate(LocalDate.of(2026, 11, 24));
        lease.setStartDate(LocalDate.of(2026, 10, 1));
        lease.setFirstPayDate(LocalDate.of(2026, 11, 15));
        lease.setPayDayOfMonth(15);
        lease.setTermMonths(60);
        lease.setMonthRent(new BigDecimal("7418.29"));
        lease.setDeposit(BigDecimal.ZERO);
        lease.setEndTransferPrice(new BigDecimal("1.00"));
        lease.setLeaseAnnualRate(new BigDecimal("20.00"));
        lease.setInterestMode("b");
        lease.setInvoiceTaxRate(new BigDecimal("0.03"));
        lease.setInvoiceDeadlineDays(10);
        lease.setFinalDealPrice(new BigDecimal("280000.00"));
        lease.setEquipmentTotal(new BigDecimal("280000.00"));
        lease.setIsDeleted(0);
        when(contractMapper.selectById(100L)).thenReturn(lease);
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(lease));

        Customer yunshan = new Customer();
        yunshan.setId(20L);
        yunshan.setName("广东云山供应链科技有限公司（云山快仓）");
        when(customerMapper.selectById(20L)).thenReturn(yunshan);
        when(customerMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(yunshan));

        // 60 期本息摊还计划（280,000 @ 20% 等额本息，月租 7,418.29，首期 2026-11-15）
        List<RentSchedule> schedules = new ArrayList<>();
        BigDecimal balance = new BigDecimal("280000.00");
        BigDecimal monthlyRate = new BigDecimal("0.20").divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
        for (int p = 1; p <= 60; p++) {
            RentSchedule s = new RentSchedule();
            s.setId((long) p);
            s.setContractId(100L);
            s.setPeriodNo(p);
            s.setDueDate(LocalDate.of(2026, 11, 15).plusMonths(p - 1));
            s.setAmount(new BigDecimal("7418.29"));
            s.setOpeningPrincipal(balance);
            BigDecimal interest;
            BigDecimal principal;
            if (p == 60) {
                principal = balance;
                interest = new BigDecimal("7418.29").subtract(principal);
                balance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            } else {
                interest = balance.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
                principal = new BigDecimal("7418.29").subtract(interest);
                balance = balance.subtract(principal).max(BigDecimal.ZERO);
            }
            s.setInterestAmount(interest);
            s.setPrincipalAmount(principal);
            s.setClosingPrincipal(balance);
            s.setPlanStatus(p <= 2 ? "已生成单" : "未到期");
            s.setIsDeleted(0);
            schedules.add(s);
        }
        when(scheduleMapper.selectList(any())).thenReturn(schedules);

        // 第 1 期已核销（已开票），第 2 期部分已收 ¥3,000（待开票）
        RentBill b1 = new RentBill();
        b1.setId(1L);
        b1.setContractId(100L);
        b1.setPeriodNo(1);
        b1.setDueDate(LocalDate.of(2026, 11, 15));
        b1.setAmount(new BigDecimal("7418.29"));
        b1.setReceivedAmount(new BigDecimal("7418.29"));
        b1.setStatus("已核销");
        b1.setBillKind("正常");
        b1.setMatchedAt(LocalDateTime.of(2026, 11, 15, 10, 0));
        b1.setInvoiceStatus("已开票");
        b1.setInvoiceAmount(new BigDecimal("7418.29"));
        b1.setIsDeleted(0);

        RentBill b2 = new RentBill();
        b2.setId(2L);
        b2.setContractId(100L);
        b2.setPeriodNo(2);
        b2.setDueDate(LocalDate.of(2026, 12, 15));
        b2.setAmount(new BigDecimal("7418.29"));
        b2.setReceivedAmount(new BigDecimal("3000.00"));
        b2.setStatus("部分已收");
        b2.setBillKind("正常");
        b2.setInvoiceStatus("待开票");
        b2.setInvoiceDueDate(LocalDate.of(2026, 12, 29));
        b2.setIsDeleted(0);
        when(billMapper.selectList(any())).thenReturn(Arrays.asList(b1, b2));
        when(depositMapper.selectList(any())).thenReturn(Collections.emptyList());

        // 金铁牛采购合同 ¥280,000（2026-09-25 验收并审核通过 → 10-30 付 40% / 11-30 付 30% / 12-30 付 30%，5 年质保至 2031-09-25）
        PurchaseContract pc = new PurchaseContract();
        pc.setId(50L);
        pc.setContractNo("GZJTN20260820LGY01");
        pc.setSupplierId(7L);
        pc.setRentContractId(100L);
        pc.setDetailSubtotal(new BigDecimal("284838.00"));
        pc.setDiscountAmount(new BigDecimal("4838.00"));
        pc.setTotalAmount(new BigDecimal("280000.00"));
        pc.setTaxRate(new BigDecimal("0.13"));
        pc.setSignedDate(LocalDate.of(2026, 8, 20));
        pc.setWarrantyYears(5);
        pc.setAcceptanceDate(LocalDate.of(2026, 9, 25));
        pc.setAuditPassDate(LocalDate.of(2026, 9, 25));
        pc.setStatus("执行中");
        when(pcMapper.selectList(any())).thenReturn(Collections.singletonList(pc));

        PurchaseContractTerm t1 = new PurchaseContractTerm();
        t1.setId(1L); t1.setPcId(50L); t1.setSortNo(1); t1.setStageName("首笔验收款40%");
        t1.setRatio(new BigDecimal("0.40")); t1.setAmount(new BigDecimal("112000.00"));
        t1.setDueDate(LocalDate.of(2026, 10, 30));

        PurchaseContractTerm t2 = new PurchaseContractTerm();
        t2.setId(2L); t2.setPcId(50L); t2.setSortNo(2); t2.setStageName("次月进度款30%");
        t2.setRatio(new BigDecimal("0.30")); t2.setAmount(new BigDecimal("84000.00"));
        t2.setDueDate(LocalDate.of(2026, 11, 30));

        PurchaseContractTerm t3 = new PurchaseContractTerm();
        t3.setId(3L); t3.setPcId(50L); t3.setSortNo(3); t3.setStageName("次次月尾款30%");
        t3.setRatio(new BigDecimal("0.30")); t3.setAmount(new BigDecimal("84000.00"));
        t3.setDueDate(LocalDate.of(2026, 12, 30));
        when(pcTermMapper.selectList(any())).thenReturn(Arrays.asList(t1, t2, t3));

        // 已支付首笔 40% = 112,000
        SupplierPayment sp1 = new SupplierPayment();
        sp1.setId(1L); sp1.setPcId(50L); sp1.setSupplierId(7L);
        sp1.setAmount(new BigDecimal("112000.00"));
        sp1.setPaidDate(LocalDate.of(2026, 10, 30));
        sp1.setStatus("有效");
        when(spMapper.selectList(any())).thenReturn(Collections.singletonList(sp1));
        when(piMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(purchaseInMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(contractAssetMapper.selectList(any())).thenReturn(Collections.emptyList());

        Supplier jtn = new Supplier();
        jtn.setId(7L);
        jtn.setName("广州金铁牛货架有限公司");
        when(supplierMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(jtn));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 老板视角_双合同真实毛利_税后净利_回本期数_最大垫资峰值全精准() {
        UserContext.set(new CurrentUser(1001L, "老板", "老板", null));

        ProjectPanoramaDtos.Panorama p = panoramaService.panorama(100L);
        assertTrue(p.isCostVisible());

        // 租赁产出侧：60 * 7418.29 = 445,097.40 + 1.00 留购 = 445,098.40
        assertEquals(0, new BigDecimal("445097.40").compareTo(p.getPlannedTotalRent()));
        assertEquals(0, new BigDecimal("445098.40").compareTo(p.getPlannedCustomerTotal()));
        // 实收租金含「已核销 7418.29 + 部分已收 3000.00」= 10,418.29
        assertEquals(0, new BigDecimal("10418.29").compareTo(p.getReceivedRent()));
        assertEquals(1, p.getSettledPeriods());
        assertEquals(1, p.getPendingInvoiceCount());

        // 采购投入侧：280,000 投入，已付 112,000，未付 168,000，下笔应付 2026-11-30 / 84,000，5 年质保至 2031-09-25
        assertEquals(0, new BigDecimal("280000.00").compareTo(p.getPurchaseTotal()));
        assertEquals(0, new BigDecimal("112000.00").compareTo(p.getPurchasePaid()));
        assertEquals(0, new BigDecimal("168000.00").compareTo(p.getPurchaseUnpaid()));
        assertEquals(LocalDate.of(2026, 11, 30), p.getNextPayableDate());
        assertEquals(0, new BigDecimal("84000.00").compareTo(p.getNextPayableAmount()));
        assertEquals(LocalDate.of(2031, 9, 25), p.getWarrantyEndDate());

        // 老板真实利润与回本：毛利 165,098.40，估 3% 增值税 445,097.40*0.03/1.03 = 12,964.00，税后净利 152,134.40
        assertEquals(0, new BigDecimal("165098.40").compareTo(p.getExpectedGrossProfit()));
        assertEquals(0, new BigDecimal("12964.00").compareTo(p.getEstimatedOutputVat()));
        assertEquals(0, new BigDecimal("152134.40").compareTo(p.getEstimatedAfterTaxProfit()));
        // 当前实际净现金流 = 10,418.29 - 112,000.00 = -101,581.71
        assertEquals(0, new BigDecimal("-101581.71").compareTo(p.getActualNetCashflow()));
        // 第 38 期累计租金 281,895.02 >= 280,000 → 回本期为第 38 期
        assertEquals(Integer.valueOf(38), p.getPaybackPeriodNo());
        // 由于供应商月结错期（10/11/12月分三笔付），11月与12月已有租金流入 2*7418.29 = 14,836.58，峰值垫资 265,163.42 < 280,000
        assertEquals(0, new BigDecimal("265163.42").compareTo(p.getPeakCapitalOutlay()));
        assertEquals("2026-12", p.getPeakOutlayMonth());
        assertFalse(p.getMonthlyCashflows().isEmpty());
    }

    @Test
    void 投资人LP视角_采购成本与利润完全脱敏() {
        UserContext.set(new CurrentUser(1009L, "投资人", "LP", null));

        ProjectPanoramaDtos.Panorama p = panoramaService.panorama(100L);
        assertFalse(p.isCostVisible());
        assertNull(p.getPurchaseTotal());
        assertNull(p.getPurchasePaid());
        assertNull(p.getExpectedGrossProfit());
        assertNull(p.getEstimatedAfterTaxProfit());
        assertNull(p.getPeakCapitalOutlay());
        assertTrue(p.getLinkedPurchases().isEmpty());
        assertTrue(p.getMonthlyCashflows().isEmpty());
        // 租赁侧回款仍可见
        assertEquals(0, new BigDecimal("10418.29").compareTo(p.getReceivedRent()));
    }
}
