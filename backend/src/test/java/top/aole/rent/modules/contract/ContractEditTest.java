package top.aole.rent.modules.contract;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.ContractChange;
import top.aole.rent.modules.contract.domain.DepositLedger;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.dto.ContractEditRequest;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractChangeMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.DepositLedgerMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.contract.service.ContractBoqService;
import top.aole.rent.modules.contract.service.ContractService;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生效合同编辑:保留已出单期次、重排其余期次、押金差额、单台分摊按比例重分、改客户同步设备。
 */
class ContractEditTest {

    private ContractMapper contractMapper;
    private ContractAssetMapper contractAssetMapper;
    private RentScheduleMapper scheduleMapper;
    private DepositLedgerMapper depositMapper;
    private ContractChangeMapper changeMapper;
    private AssetService assetService;
    private CustomerMapper customerMapper;
    private ContractService service;
    private Contract contract;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : new Class<?>[]{Contract.class, ContractAsset.class, RentSchedule.class, DepositLedger.class,
                ContractChange.class, Customer.class}) {
            TableInfoHelper.initTableInfo(a, c);
        }
    }

    @BeforeEach
    void setUp() {
        contractMapper = mock(ContractMapper.class);
        contractAssetMapper = mock(ContractAssetMapper.class);
        scheduleMapper = mock(RentScheduleMapper.class);
        depositMapper = mock(DepositLedgerMapper.class);
        changeMapper = mock(ContractChangeMapper.class);
        assetService = mock(AssetService.class);
        customerMapper = mock(CustomerMapper.class);
        service = new ContractService(contractMapper, contractAssetMapper, mock(ContractBoqService.class),
                scheduleMapper, depositMapper, changeMapper,
                mock(AssetMapper.class), assetService, customerMapper, mock(RuleConfigService.class), mock(AuditLogService.class));
        UserContext.set(new CurrentUser(1005L, "财务", "财务", null));

        contract = new Contract();
        contract.setId(1L);
        contract.setNo("HT-TEST-01");
        contract.setCustomerId(7L);
        contract.setStatus("生效");
        contract.setNature("分期收款销售");
        contract.setTermMonths(12);
        contract.setMonthRent(new BigDecimal("1000.00"));
        contract.setDeposit(new BigDecimal("2000.00"));
        contract.setEndTransferPrice(BigDecimal.ZERO);
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setSignDate(LocalDate.of(2026, 1, 1));
        when(contractMapper.selectById(1L)).thenReturn(contract);

        Customer newCustomer = new Customer();
        newCustomer.setId(8L);
        newCustomer.setName("新客户");
        when(customerMapper.selectById(8L)).thenReturn(newCustomer);
        Customer oldCustomer = new Customer();
        oldCustomer.setId(7L);
        when(customerMapper.selectById(7L)).thenReturn(oldCustomer);

        List<RentSchedule> schedules = new ArrayList<>();
        for (int p = 1; p <= 12; p++) {
            RentSchedule rs = new RentSchedule();
            rs.setId(100L + p);
            rs.setContractId(1L);
            rs.setPeriodNo(p);
            rs.setDueDate(LocalDate.of(2026, 1, 1).plusMonths(p));
            rs.setAmount(new BigDecimal("1000.00"));
            rs.setPlanStatus(p <= 3 ? "已生成单" : "未到期");
            schedules.add(rs);
        }
        when(scheduleMapper.selectList(any())).thenReturn(schedules);

        ContractAsset a1 = link(11L, 501L, "400.00");
        ContractAsset a2 = link(12L, 502L, "600.00");
        when(contractAssetMapper.selectList(any())).thenReturn(Arrays.asList(a1, a2));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void activeContractKeepsBilledPeriodsAndRegeneratesRest() {
        ContractEditRequest req = request(6, "1200", "3000", 8L);

        service.edit(1L, req);

        // 计划行必须物理删(逻辑删的行会继续占着 uk_schedule_period,重排再插入就 Duplicate entry → 500)
        verify(scheduleMapper).hardDeleteReplaceable(1L, 3); // 保留已出单的第 1~3 期,其余物理删
        verify(scheduleMapper, never()).deleteById(anyLong());
        ArgumentCaptor<RentSchedule> inserted = ArgumentCaptor.forClass(RentSchedule.class);
        verify(scheduleMapper, times(3)).insert(inserted.capture()); // 重排第 4~6 期
        assertEquals(Integer.valueOf(4), inserted.getAllValues().get(0).getPeriodNo());
        assertEquals(LocalDate.of(2026, 5, 1), inserted.getAllValues().get(0).getDueDate());
        assertEquals(0, new BigDecimal("1200.00").compareTo(inserted.getAllValues().get(2).getAmount()));

        ArgumentCaptor<DepositLedger> dep = ArgumentCaptor.forClass(DepositLedger.class);
        verify(depositMapper).insert(dep.capture());
        assertEquals("收", dep.getValue().getDirection());
        assertEquals(0, new BigDecimal("1000.00").compareTo(dep.getValue().getAmount()));

        verify(assetService).changeHolder(501L, 8L);
        verify(assetService).changeHolder(502L, 8L);
        verify(contractAssetMapper, times(2)).update(isNull(), any(Wrapper.class)); // 400/600 → 480/720
        verify(changeMapper).insert(any(ContractChange.class));
    }

    @Test
    void termShorterThanBilledPeriodsIsRejected() {
        BizException e = assertThrows(BizException.class, () -> service.edit(1L, request(2, "1000", "2000", 7L)));
        assertTrue(e.getMessage().contains("前 3 期已生成收租单"));
        verify(scheduleMapper, never()).deleteById(anyLong());
        verify(scheduleMapper, never()).hardDeleteReplaceable(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void draftContractSkipsScheduleAndDeposit() {
        contract.setStatus("草稿");
        service.edit(1L, request(24, "1000", "5000", 7L));
        verify(scheduleMapper, never()).insert(any(RentSchedule.class));
        verify(depositMapper, never()).insert(any(DepositLedger.class));
        verify(assetService, never()).changeHolder(anyLong(), eq(7L));
        verify(contractMapper).update(isNull(), any(Wrapper.class));
    }

    private ContractEditRequest request(int term, String rent, String deposit, Long customerId) {
        ContractEditRequest req = new ContractEditRequest();
        req.setCustomerId(customerId);
        req.setNature("分期收款销售");
        req.setTermMonths(term);
        req.setMonthRent(new BigDecimal(rent));
        req.setDeposit(new BigDecimal(deposit));
        req.setStartDate(LocalDate.of(2026, 1, 1));
        return req;
    }

    private ContractAsset link(Long id, Long assetId, String alloc) {
        ContractAsset ca = new ContractAsset();
        ca.setId(id);
        ca.setContractId(1L);
        ca.setAssetId(assetId);
        ca.setAllocRent(new BigDecimal(alloc));
        return ca;
    }
}
