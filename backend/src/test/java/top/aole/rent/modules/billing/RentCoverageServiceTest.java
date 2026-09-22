package top.aole.rent.modules.billing;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.dto.RentCoverageDto;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.billing.service.RentCoverageService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购应付 ←→ 收租单的资金对照:已收/待收/逾期口径、下一期到期、开启中的逾期案、批量不 N+1。
 */
class RentCoverageServiceTest {

    private RentBillMapper billMapper;
    private OverdueCaseMapper caseMapper;
    private RentCoverageService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, RentBill.class);
        TableInfoHelper.initTableInfo(a, OverdueCase.class);
    }

    @BeforeEach
    void setUp() {
        billMapper = mock(RentBillMapper.class);
        caseMapper = mock(OverdueCaseMapper.class);
        service = new RentCoverageService(billMapper, caseMapper);
    }

    private RentBill bill(long id, long contractId, int period, String due, String amount,
                          String received, String status, String kind) {
        RentBill b = new RentBill();
        b.setId(id);
        b.setBillNo("RB-" + contractId + "-P" + period);
        b.setContractId(contractId);
        b.setPeriodNo(period);
        b.setDueDate(LocalDate.parse(due));
        b.setAmount(new BigDecimal(amount));
        b.setReceivedAmount(received == null ? null : new BigDecimal(received));
        b.setStatus(status);
        b.setBillKind(kind);
        return b;
    }

    private OverdueCase openCase(long id, long contractId, String step) {
        OverdueCase c = new OverdueCase();
        c.setId(id);
        c.setContractId(contractId);
        c.setStep(step);
        c.setStatus("开启");
        c.setDeadline(LocalDate.parse("2026-10-10"));
        c.setOwner("财务");
        return c;
    }

    private void stubBills(List<RentBill> bills) {
        when(billMapper.selectList(any())).thenReturn(bills);
    }

    private void stubCases(List<OverdueCase> cases) {
        when(caseMapper.selectList(any())).thenReturn(cases);
    }

    @Test
    void 已收是已核销净额_红冲单不计_退款冲减() {
        stubBills(Arrays.asList(
                bill(1, 60, 1, "2026-10-01", "60000", "60000", "已核销", "正常"),
                bill(2, 60, 2, "2026-11-01", "60000", "60000", "红冲", "正常"),   // 原单被红冲
                bill(3, 60, 2, "2026-11-01", "-60000", "-60000", "红冲", "红冲"), // 红字行
                bill(4, 60, 0, "2026-11-05", "-5000", "-5000", "已核销", "退款")));
        stubCases(Collections.emptyList());

        RentCoverageDto d = service.of(60L);
        assertEquals(new BigDecimal("55000.00"), d.getCollectedAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), d.getPendingAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), d.getOverdueAmount());
        assertEquals(0, d.getOverdueCount());
        assertEquals(2, d.getBillCount()); // 红冲的两行不算在册
    }

    @Test
    void 待收与逾期分开统计_下一期取最早未收() {
        stubBills(Arrays.asList(
                bill(1, 60, 1, "2026-08-01", "60000", "0", "逾期", "正常"),
                bill(2, 60, 2, "2026-09-01", "60000", "0", "逾期", "正常"),
                bill(3, 60, 3, "2026-10-01", "60000", "0", "待收", "正常"),
                bill(4, 60, 4, "2026-11-01", "60000", "0", "待收", "正常")));
        stubCases(Collections.emptyList());

        RentCoverageDto d = service.of(60L);
        assertEquals(new BigDecimal("120000.00"), d.getOverdueAmount());
        assertEquals(2, d.getOverdueCount());
        assertEquals(new BigDecimal("120000.00"), d.getPendingAmount());
        assertEquals(LocalDate.parse("2026-08-01"), d.getNextDueDate());
        assertEquals(new BigDecimal("60000.00"), d.getNextDueAmount());
    }

    @Test
    void 开启中的逾期案取最紧迫一步() {
        stubBills(Collections.singletonList(
                bill(1, 60, 1, "2026-08-01", "60000", "0", "逾期", "正常")));
        stubCases(Arrays.asList(openCase(9, 60, "延期"), openCase(10, 60, "锁机")));

        RentCoverageDto d = service.of(60L);
        assertEquals(2, d.getOpenCaseCount());
        assertEquals("锁机", d.getOpenCaseStep());
    }

    @Test
    void 没有收租单时全部为零而不是空指针() {
        stubBills(Collections.emptyList());
        stubCases(Collections.emptyList());

        RentCoverageDto d = service.of(60L);
        assertEquals(BigDecimal.ZERO.setScale(2), d.getCollectedAmount());
        assertEquals(0, d.getBillCount());
        assertEquals(0, d.getOpenCaseCount());
        assertNull(d.getNextDueDate());
        assertNull(d.getOpenCaseStep());
    }

    @Test
    void 合同为空时返回空对照() {
        assertNull(service.of(null));
        verify(billMapper, times(0)).selectList(any());
    }

    @Test
    void 批量对照一次查完_不按合同逐个查() {
        stubBills(Arrays.asList(
                bill(1, 60, 1, "2026-08-01", "60000", "60000", "已核销", "正常"),
                bill(2, 61, 1, "2026-08-01", "30000", "0", "逾期", "正常")));
        stubCases(Collections.singletonList(openCase(9, 61, "罚息")));

        Map<Long, RentCoverageDto> m = service.byContract(Arrays.asList(60L, 61L, 62L));

        verify(billMapper, times(1)).selectList(any());
        verify(caseMapper, times(1)).selectList(any());
        assertEquals(new BigDecimal("60000.00"), m.get(60L).getCollectedAmount());
        assertEquals(new BigDecimal("30000.00"), m.get(61L).getOverdueAmount());
        assertEquals("罚息", m.get(61L).getOpenCaseStep());
        // 没有收租单的合同也要有一行零值,前端不必判空
        assertEquals(0, m.get(62L).getBillCount());
    }

    @Test
    void 批量传空集合不查库() {
        assertTrue(service.byContract(new ArrayList<>()).isEmpty());
        verify(billMapper, times(0)).selectList(any());
    }
}
