package top.aole.rent.modules.billing;

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
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.billing.domain.AccountingPeriod;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.dto.BillDtos;
import top.aole.rent.modules.billing.mapper.AccountingPeriodMapper;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.billing.service.RentBillService;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.payproof.service.PayProofService;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 收租核销/批量核销/退款都要先挂回单;稽核统计已核销无回单。 */
class RentBillProofTest {

    private RentBillMapper billMapper;
    private PayProofService payProofService;
    private RentBillService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : Arrays.asList(RentBill.class, OverdueCase.class, AccountingPeriod.class, RentSchedule.class)) {
            TableInfoHelper.initTableInfo(a, c);
        }
    }

    @BeforeEach
    void setUp() {
        billMapper = mock(RentBillMapper.class);
        payProofService = mock(PayProofService.class);
        service = new RentBillService(billMapper, mock(RentScheduleMapper.class), mock(ContractMapper.class),
                mock(CustomerMapper.class), mock(AccountingPeriodMapper.class), mock(OverdueCaseMapper.class),
                mock(RuleConfigService.class), mock(AuditLogService.class), mock(VoucherService.class), payProofService);
        UserContext.set(new CurrentUser(1005L, "财务", "财务", null));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private RentBill bill(long id, String status) {
        RentBill b = new RentBill();
        b.setId(id);
        b.setBillNo("SZ-" + id);
        b.setContractId(60L);
        b.setPeriodNo(1);
        b.setAmount(new BigDecimal("6720"));
        b.setReceivedAmount(BigDecimal.ZERO);
        b.setStatus(status);
        b.setBillKind("正常");
        when(billMapper.selectById(id)).thenReturn(b);
        return b;
    }

    @Test
    void 核销先绑回单() {
        bill(1L, "待收");
        BillDtos.MatchRequest req = new BillDtos.MatchRequest();
        req.setProofFileIds(Collections.singletonList(80L));
        service.match(1L, req);
        verify(payProofService).bind(eq(PayProofService.RENT_BILL), eq(Collections.singletonList(1L)),
                eq("核销"), eq(Collections.singletonList(80L)));
    }

    @Test
    void 回单不过不核销() {
        bill(1L, "待收");
        doThrow(new BizException(400, "请先上传回单(银行回单或支付截图)再登记"))
                .when(payProofService).bind(any(), any(), any(), any());
        assertThrows(BizException.class, () -> service.match(1L, new BillDtos.MatchRequest()));
        verify(billMapper, never()).updateById(any(RentBill.class));
    }

    @Test
    void 批量核销一张回单挂全部() {
        bill(1L, "待收");
        bill(2L, "逾期");
        service.batchMatch(Arrays.asList(1L, 2L), Collections.singletonList(81L));
        verify(payProofService).bind(eq(PayProofService.RENT_BILL), eq(Arrays.asList(1L, 2L)),
                eq("批量核销"), eq(Collections.singletonList(81L)));
    }

    @Test
    void 稽核统计已核销无回单() {
        RentBill a = bill(1L, "已核销");
        RentBill b = bill(2L, "已核销");
        when(billMapper.selectList(any())).thenReturn(Arrays.asList(a, b));
        Map<Long, Integer> counts = new HashMap<>();
        counts.put(1L, 1);
        when(payProofService.countMap(eq(PayProofService.RENT_BILL), any())).thenReturn(counts);
        BillDtos.CashCheck cc = service.cashCheck();
        assertEquals(1, cc.getMatchedNoProof());
        assertEquals(1, cc.getMatchedNoProofDetail().size());
    }
}
