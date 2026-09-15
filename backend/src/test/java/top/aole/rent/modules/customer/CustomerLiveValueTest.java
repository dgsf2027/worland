package top.aole.rent.modules.customer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.domain.CustomerFollowup;
import top.aole.rent.modules.customer.dto.CustomerDetailResponse;
import top.aole.rent.modules.customer.mapper.CustomerFollowupMapper;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.customer.mapper.OpportunityMapper;
import top.aole.rent.modules.customer.service.CustomerService;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户价值 & 风险敞口按合同 / 租金计划 / 收租单实时计算;累计利润、续租率取手工值。
 */
class CustomerLiveValueTest {

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : new Class<?>[]{Customer.class, CustomerFollowup.class, Contract.class, ContractAsset.class,
                RentSchedule.class, RentBill.class, Asset.class}) {
            TableInfoHelper.initTableInfo(a, c);
        }
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void valueExposureIsComputedFromContractsSchedulesAndBills() {
        CustomerMapper customerMapper = mock(CustomerMapper.class);
        ContractMapper contractMapper = mock(ContractMapper.class);
        RentScheduleMapper scheduleMapper = mock(RentScheduleMapper.class);
        RentBillMapper billMapper = mock(RentBillMapper.class);
        CustomerFollowupMapper followupMapper = mock(CustomerFollowupMapper.class);
        ContractAssetMapper contractAssetMapper = mock(ContractAssetMapper.class);
        AssetMapper assetMapper = mock(AssetMapper.class);
        CustomerService service = new CustomerService(customerMapper, followupMapper, mock(OpportunityMapper.class),
                mock(RuleConfigService.class), contractMapper, contractAssetMapper, assetMapper, scheduleMapper,
                billMapper, mock(AuditLogService.class));
        UserContext.set(new CurrentUser(1001L, "老板", "老板", null));

        Customer c = new Customer();
        c.setId(7L);
        c.setName("华南仓储");
        c.setPhase("在租");
        c.setCumulativeProfit(new BigDecimal("88000"));
        c.setRenewRate(new BigDecimal("0.5"));
        c.setExposureAmount(new BigDecimal("999999")); // 旧快照,不应再被使用
        when(customerMapper.selectById(7L)).thenReturn(c);

        Contract active = contract(1L, "生效");
        Contract voided = contract(2L, "已作废");
        when(contractMapper.selectList(any())).thenReturn(Arrays.asList(active, voided));
        when(contractMapper.selectBatchIds(any())).thenReturn(Collections.emptyList());
        when(contractAssetMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(assetMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(followupMapper.selectList(any())).thenReturn(Collections.emptyList());

        LocalDate future = LocalDate.now().plusMonths(1);
        when(scheduleMapper.selectList(any())).thenReturn(Arrays.asList(
                schedule(1L, future, "5000"), schedule(1L, future.plusMonths(1), "5000")));

        LocalDate past = LocalDate.now().minusDays(10);
        when(billMapper.selectList(any())).thenReturn(Arrays.asList(
                bill(1L, "已核销", "正常", "5000", "5000", past),
                bill(1L, "已核销", "正常", "5000", "5000", past),
                bill(1L, "已核销", "退款", "-1000", "-1000", past),
                bill(1L, "逾期", "正常", "5000", "2000", past)));

        CustomerDetailResponse.ValueExposure ve = service.detail(7L).getValueExposure();

        assertEquals(Integer.valueOf(1), ve.getContractCount()); // 作废合同不计
        assertEquals(0, new BigDecimal("11000").compareTo(ve.getCumulativeRent())); // 5000+5000-1000+2000
        assertEquals(0, new BigDecimal("10000").compareTo(ve.getExposureAmount()));
        assertEquals(0, new BigDecimal("3000").compareTo(ve.getReceivableOverdue()));
        assertEquals(0, new BigDecimal("88000").compareTo(ve.getCumulativeProfit()));
        assertEquals(0, new BigDecimal("1").compareTo(ve.getConcentration()));
    }

    private Contract contract(Long id, String status) {
        Contract c = new Contract();
        c.setId(id);
        c.setNo("HT-" + id);
        c.setCustomerId(7L);
        c.setStatus(status);
        return c;
    }

    private RentSchedule schedule(Long contractId, LocalDate due, String amount) {
        RentSchedule rs = new RentSchedule();
        rs.setContractId(contractId);
        rs.setDueDate(due);
        rs.setAmount(new BigDecimal(amount));
        rs.setPlanStatus("未到期");
        return rs;
    }

    private RentBill bill(Long contractId, String status, String kind, String amount, String received, LocalDate due) {
        RentBill b = new RentBill();
        b.setContractId(contractId);
        b.setStatus(status);
        b.setBillKind(kind);
        b.setAmount(new BigDecimal(amount));
        b.setReceivedAmount(new BigDecimal(received));
        b.setDueDate(due);
        return b;
    }
}
