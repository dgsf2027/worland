package top.aole.rent.modules.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.analytics.service.CashflowService;
import top.aole.rent.modules.bi.dto.BiDtos;
import top.aole.rent.modules.bi.service.BiService;
import top.aole.rent.modules.customer.dto.PipelineResponse;
import top.aole.rent.modules.customer.service.CustomerService;
import top.aole.rent.modules.inventory.dto.InvDtos;
import top.aole.rent.modules.inventory.service.InvService;
import top.aole.rent.modules.workbench.dto.WorkbenchChartsDtos;
import top.aole.rent.modules.workbench.service.WorkbenchChartsService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbenchChartsServiceTest {

    private CustomerService customers;
    private InvService inventory;
    private BiService bi;
    private CashflowService cashflow;
    private WorkbenchChartsService service;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerService.class);
        inventory = mock(InvService.class);
        bi = mock(BiService.class);
        cashflow = mock(CashflowService.class);
        service = new WorkbenchChartsService(customers, inventory, bi, cashflow);
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GP", "LP", "未分配", ""})
    void restrictedRolesNeverLoadSourceData(String role) {
        login(role);
        WorkbenchChartsDtos.Charts charts = service.charts(null);
        assertEquals(6, charts.getMonths());
        assertRestricted(charts.getCustomerStages());
        assertRestricted(charts.getInventory());
        assertRestricted(charts.getRentTrend());
        assertRestricted(charts.getCashflow());
        verifyNoInteractions(customers, inventory, bi, cashflow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"业务", "供应链"})
    void operatingRolesUseExistingCustomerScopeAndDoNotReadFinance(String role) {
        login(role);
        CurrentUser current = UserContext.get();
        when(customers.pipeline()).thenAnswer(call -> {
            assertSame(current, UserContext.get(), "不得切换角色或绕过客户本人+公海过滤");
            return pipeline();
        });
        when(inventory.overview()).thenReturn(overview());

        WorkbenchChartsDtos.Charts charts = service.charts(12);
        assertEquals(12, charts.getMonths());
        assertEquals("ready", charts.getCustomerStages().getStatus());
        assertEquals(Integer.valueOf(3), charts.getCustomerStages().getData().get(0).getCount());
        assertEquals("ready", charts.getInventory().getStatus());
        assertEquals(2, charts.getInventory().getData().getOverdueRentalCount());
        assertRestricted(charts.getRentTrend());
        assertRestricted(charts.getCashflow());
        if ("业务".equals(role)) {
            assertTrue(charts.getCustomerStages().getSource().contains("本人名下及公海"));
        }
        verifyNoInteractions(bi, cashflow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"老板", "财务"})
    void financeReceivesOnlyExplicitAggregateFields(String role) throws Exception {
        login(role);
        readySources(6);
        WorkbenchChartsDtos.Charts charts = service.charts(6);
        assertEquals("ready", charts.getRentTrend().getStatus());
        assertEquals("ready", charts.getCashflow().getStatus());
        assertEquals(6, charts.getRentTrend().getData().getPoints().size());
        assertEquals(new BigDecimal("100"), charts.getRentTrend().getData().getPoints().get(0).getReceivable());
        assertEquals(new BigDecimal("40"), charts.getRentTrend().getData().getPoints().get(0).getCollected());
        assertTrue(charts.getRentTrend().getSource().contains("未抵扣退款或红冲负单"));

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(charts);
        assertEquals(2, json.at("/customerStages/data/0").size());
        assertFalse(json.at("/customerStages/data/0").has("cards"));
        assertEquals(3, json.at("/inventory/data").size());
        assertFalse(json.at("/inventory/data").has("reminders"));
        assertFalse(json.at("/inventory/data").has("pendingCompensation"));
        assertEquals(4, json.at("/cashflow/data").size());
        assertFalse(json.at("/cashflow/data").has("leverage"));
        assertFalse(json.at("/cashflow/data").has("netPosition"));
        assertFalse(json.at("/cashflow/data/forecast/0").has("cumulative"));
        assertFalse(json.toString().contains("不应返回的客户"));
    }

    @Test
    void oneSourceFailureDoesNotTurnIntoZeroOrBreakOtherCharts() {
        login("老板");
        readySources(6);
        when(customers.pipeline()).thenThrow(new IllegalStateException("内部连接信息不得返回"));
        WorkbenchChartsDtos.Charts charts = service.charts(6);
        assertEquals("error", charts.getCustomerStages().getStatus());
        assertNull(charts.getCustomerStages().getData());
        assertFalse(charts.getCustomerStages().getSource().contains("内部连接"));
        assertEquals("ready", charts.getInventory().getStatus());
        assertEquals("ready", charts.getRentTrend().getStatus());
        assertEquals("ready", charts.getCashflow().getStatus());
    }

    @Test
    void incompleteTrendIsErrorRatherThanFabricatedMonths() {
        login("财务");
        readySources(6);
        BiDtos.TrendResp incomplete = trend(6, "40");
        incomplete.getPoints().remove(0);
        when(bi.trend("collection", 6)).thenReturn(incomplete);
        WorkbenchChartsDtos.Charts charts = service.charts(6);
        assertEquals("error", charts.getRentTrend().getStatus());
        assertNull(charts.getRentTrend().getData());
        assertEquals("ready", charts.getCashflow().getStatus());
    }

    @Test
    void unavailableDataIsExplicitNullInJson() {
        login("LP");
        JsonNode json = new ObjectMapper().valueToTree(service.charts(6));
        assertTrue(json.path("cashflow").has("data"));
        assertTrue(json.at("/cashflow/data").isNull());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 7, 24})
    void invalidMonthsAreRejectedBeforeQueryingSources(int months) {
        login("老板");
        BizException error = assertThrows(BizException.class, () -> service.charts(months));
        assertEquals(400, error.getCode());
        verifyNoInteractions(customers, inventory, bi, cashflow);
    }

    @Test
    void authenticationIsRequired() {
        assertThrows(BizException.class, () -> service.charts(6));
        verifyNoInteractions(customers, inventory, bi, cashflow);
    }

    private void login(String role) {
        UserContext.set(new CurrentUser(42L, "测试用户", role, null));
    }

    private void assertRestricted(WorkbenchChartsDtos.Resource<?> resource) {
        assertEquals("restricted", resource.getStatus());
        assertNull(resource.getData());
    }

    private void readySources(int months) {
        when(customers.pipeline()).thenReturn(pipeline());
        when(inventory.overview()).thenReturn(overview());
        when(bi.trend("receivable", months)).thenReturn(trend(months, "100"));
        when(bi.trend("collection", months)).thenReturn(trend(months, "40"));
        when(cashflow.cashflow(months)).thenReturn(cashflow(months));
    }

    private PipelineResponse pipeline() {
        PipelineResponse.Card card = new PipelineResponse.Card();
        card.setName("不应返回的客户");
        card.setAmount(new BigDecimal("999999"));
        PipelineResponse.Column column = new PipelineResponse.Column();
        column.setPhase("在租");
        column.setCount(3);
        column.setCards(Collections.singletonList(card));
        PipelineResponse response = new PipelineResponse();
        response.setColumns(Collections.singletonList(column));
        response.setWeightedForecast(new BigDecimal("999999"));
        return response;
    }

    private InvDtos.Overview overview() {
        InvDtos.Overview out = new InvDtos.Overview();
        out.setItemCount(4);
        out.setActiveRentalCount(5);
        out.setOverdueRentalCount(2);
        out.setPendingCompensation(new BigDecimal("999999"));
        return out;
    }

    private BiDtos.TrendResp trend(int months, String amount) {
        BiDtos.TrendResp out = new BiDtos.TrendResp();
        for (int i = months - 1; i >= 0; i--) {
            out.getPoints().add(new BiDtos.TrendPoint(YearMonth.now().minusMonths(i).toString(), new BigDecimal(amount)));
        }
        return out;
    }

    private CashflowDtos.Cashflow cashflow(int months) {
        CashflowDtos.Cashflow out = new CashflowDtos.Cashflow();
        out.setAsOf(LocalDate.now());
        CashflowDtos.Bucket bucket = new CashflowDtos.Bucket();
        bucket.setWithin1Year(new BigDecimal("80"));
        bucket.setBeyond1Year(new BigDecimal("20"));
        bucket.setTotal(new BigDecimal("100"));
        bucket.setCount(3);
        out.setReceivable(bucket);
        out.setPayable(bucket);
        out.setForecast(new ArrayList<>());
        for (int i = 0; i < months; i++) {
            CashflowDtos.MonthFlow point = new CashflowDtos.MonthFlow();
            point.setPeriod(YearMonth.now().plusMonths(i).toString());
            point.setInflow(new BigDecimal("100"));
            point.setOutflow(new BigDecimal("40"));
            point.setNet(new BigDecimal("60"));
            point.setCumulative(new BigDecimal("999999"));
            out.getForecast().add(point);
        }
        out.setLeverage(new CashflowDtos.Leverage());
        out.setNetPosition(new BigDecimal("999999"));
        return out;
    }
}
