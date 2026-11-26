package top.aole.rent.modules.workbench;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.domain.SupplierPayment;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.rule.domain.RuleConfig;
import top.aole.rent.modules.rule.mapper.RuleConfigMapper;
import top.aole.rent.modules.task.domain.Task;
import top.aole.rent.modules.task.mapper.TaskMapper;
import top.aole.rent.modules.workbench.dto.WorkbenchWaterlineDtos;
import top.aole.rent.modules.workbench.service.WorkbenchCashWaterlineService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资金水位线预测 & 历史孤儿系统任务体检清理单元测试。
 */
class WorkbenchCashWaterlineServiceTest {

    private RuleConfigMapper ruleConfigMapper;
    private ContractMapper contractMapper;
    private RentScheduleMapper scheduleMapper;
    private RentBillMapper billMapper;
    private PurchaseContractMapper pcMapper;
    private PurchaseContractTermMapper pcTermMapper;
    private SupplierPaymentMapper spMapper;
    private PurchaseInMapper purchaseInMapper;
    private PayableMapper payableMapper;
    private TaskMapper taskMapper;
    private OverdueCaseMapper overdueCaseMapper;
    private WorkbenchCashWaterlineService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : Arrays.asList(
                RuleConfig.class, Contract.class, RentSchedule.class, RentBill.class,
                PurchaseContract.class, PurchaseContractTerm.class, SupplierPayment.class,
                PurchaseIn.class, Payable.class, Task.class, OverdueCase.class)) {
            TableInfoHelper.initTableInfo(a, c);
        }
    }

    @BeforeEach
    void setUp() {
        ruleConfigMapper = mock(RuleConfigMapper.class);
        contractMapper = mock(ContractMapper.class);
        scheduleMapper = mock(RentScheduleMapper.class);
        billMapper = mock(RentBillMapper.class);
        pcMapper = mock(PurchaseContractMapper.class);
        pcTermMapper = mock(PurchaseContractTermMapper.class);
        spMapper = mock(SupplierPaymentMapper.class);
        purchaseInMapper = mock(PurchaseInMapper.class);
        payableMapper = mock(PayableMapper.class);
        taskMapper = mock(TaskMapper.class);
        overdueCaseMapper = mock(OverdueCaseMapper.class);

        service = new WorkbenchCashWaterlineService(
                ruleConfigMapper, contractMapper, scheduleMapper, billMapper,
                pcMapper, pcTermMapper, spMapper, purchaseInMapper, payableMapper,
                taskMapper, overdueCaseMapper, mock(AuditLogService.class));

        when(ruleConfigMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(billMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(scheduleMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(pcMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(purchaseInMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(payableMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(taskMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 资金水位线精准识别缺口月份与需补资金额() {
        UserContext.set(new CurrentUser(1001L, "老板", "老板", null));

        // 已保存银行可用余额 100,000 元
        RuleConfig rc = new RuleConfig();
        rc.setId(1L);
        rc.setRuleKey(WorkbenchCashWaterlineService.RULE_BANK_CASH_BALANCE);
        rc.setScopeKey("");
        rc.setRuleValue(new BigDecimal("100000.00"));
        rc.setVersion(1);
        when(ruleConfigMapper.selectList(any())).thenReturn(Collections.singletonList(rc));

        // 生效租赁合同：每月租金流入 7,418.29
        Contract c = new Contract();
        c.setId(10L);
        c.setStatus("生效");
        c.setIsDeleted(0);
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(c));

        YearMonth ym0 = YearMonth.now();
        RentSchedule s0 = new RentSchedule();
        s0.setContractId(10L);
        s0.setPeriodNo(1);
        s0.setDueDate(ym0.atDay(15));
        s0.setAmount(new BigDecimal("7418.29"));

        RentSchedule s1 = new RentSchedule();
        s1.setContractId(10L);
        s1.setPeriodNo(2);
        s1.setDueDate(ym0.plusMonths(1).atDay(15));
        s1.setAmount(new BigDecimal("7418.29"));
        when(scheduleMapper.selectList(any())).thenReturn(Arrays.asList(s0, s1));

        // 采购合同：本月应付 84,000，次月应付 84,000
        PurchaseContract pc = new PurchaseContract();
        pc.setId(50L);
        pc.setTotalAmount(new BigDecimal("168000.00"));
        pc.setStatus("执行中");
        when(pcMapper.selectList(any())).thenReturn(Collections.singletonList(pc));
        when(spMapper.selectList(any())).thenReturn(Collections.emptyList());

        PurchaseContractTerm t1 = new PurchaseContractTerm();
        t1.setId(1L);
        t1.setPcId(50L);
        t1.setAmount(new BigDecimal("84000.00"));
        t1.setDueDate(ym0.atEndOfMonth());

        PurchaseContractTerm t2 = new PurchaseContractTerm();
        t2.setId(2L);
        t2.setPcId(50L);
        t2.setAmount(new BigDecimal("84000.00"));
        t2.setDueDate(ym0.plusMonths(1).atEndOfMonth());
        when(pcTermMapper.selectList(any())).thenReturn(Arrays.asList(t1, t2));

        WorkbenchWaterlineDtos.CashWaterline wl = service.cashWaterline(6, null);
        assertTrue(wl.isCostVisible());
        assertEquals(6, wl.getPoints().size());
        assertEquals(0, new BigDecimal("100000.00").compareTo(wl.getOpeningCashBalance()));

        // 第 1 月期末 = 100,000 + 7,418.29 - 84,000 = 23,418.29（无缺口）
        assertEquals(0, new BigDecimal("23418.29").compareTo(wl.getPoints().get(0).getEndingBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(wl.getPoints().get(0).getShortfall()));

        // 第 2 月期末 = 23,418.29 + 7,418.29 - 84,000 = -53,163.42（缺口 53,163.42）
        assertEquals(0, new BigDecimal("-53163.42").compareTo(wl.getPoints().get(1).getEndingBalance()));
        assertEquals(ym0.plusMonths(1).toString(), wl.getFirstShortfallMonth());
        assertEquals(0, new BigDecimal("53163.42").compareTo(wl.getMaxShortfall()));
        assertEquals(0, new BigDecimal("-53163.42").compareTo(wl.getMinBalance()));
    }

    @Test
    void LP视角资金水位线脱敏() {
        UserContext.set(new CurrentUser(1009L, "投资人", "LP", null));
        WorkbenchWaterlineDtos.CashWaterline wl = service.cashWaterline(6, new BigDecimal("500000"));
        assertFalse(wl.isCostVisible());
        assertNull(wl.getOpeningCashBalance());
        assertTrue(wl.getPoints().isEmpty());
    }

    @Test
    void 体检并一键清理历史孤儿系统任务() {
        UserContext.set(new CurrentUser(1001L, "老板", "老板", null));

        Task orphan = new Task();
        orphan.setId(99L);
        orphan.setNo("TSK-OLD-01");
        orphan.setTitle("历史测试逾期催收");
        orphan.setType("催收");
        orphan.setSource("系统");
        orphan.setBizType("overdue_case");
        orphan.setBizId(888L);
        orphan.setStatus("待开始");
        orphan.setIsDeleted(0);

        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(orphan));
        when(overdueCaseMapper.selectById(888L)).thenReturn(null);

        WorkbenchWaterlineDtos.DataHealth health = service.dataHealth();
        assertEquals(1, health.getStaleTaskCount());
        assertFalse(health.isHealthy());

        WorkbenchWaterlineDtos.CleanupResult cleaned = service.cleanStaleTasks();
        assertEquals(1, cleaned.getCleanedCount());
        assertEquals("已作废", orphan.getStatus());
        assertNotNull(orphan.getRemark());
        verify(taskMapper).updateById(orphan);
    }
}
