package top.aole.rent.modules.workbench.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 复用源模块统计，只做权限与字段投影；每个资源独立失败，不补零。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchChartsService {

    private final CustomerService customerService;
    private final InvService invService;
    private final BiService biService;
    private final CashflowService cashflowService;

    private static final List<String> OPERATING_ROLES = Arrays.asList("老板", "财务", "供应链", "业务");
    private static final List<String> FINANCE_ROLES = Arrays.asList("老板", "财务");
    private static final String INVENTORY_SOURCE = "仓库实物汇总：资产档案数；活跃出租单为已预订或出租中；逾期归还单为仍有在外数量且已过预计归还日。按单计数，不合并套、组、件等实物单位。";
    private static final String TREND_SOURCE = "正常收租原单：应收按记账月份汇总原单金额，已收按核销月份汇总收款金额。包含后来已红冲的原单，未抵扣退款或红冲负单，不代表净回款，两列也不代表同一批应收的回款率。";
    private static final String CASHFLOW_SOURCE = "租金计划未被已核销收租单结清的应收，与待付应付按到期月预测；仅含未删除合同的计划。当月起统计，不滚入以前月份逾期款，不代表银行余额。期限结构按今天起一年划分，一年内含已逾期及无到期日记录。";

    public WorkbenchChartsDtos.Charts charts(Integer requestedMonths) {
        CurrentUser user = UserContext.require();
        int months = requestedMonths == null ? 6 : requestedMonths;
        if (months != 6 && months != 12) {
            throw new BizException(400, "图表月份仅支持 6 或 12");
        }
        boolean operating = OPERATING_ROLES.contains(user.getRole());
        boolean finance = FINANCE_ROLES.contains(user.getRole());
        String customerSource = "业务".equals(user.getRole())
                ? "客户 CRM 当前阶段分布：仅本人名下及公海客户；阶段客户数不代表历史转化率。"
                : "客户 CRM 当前阶段分布：全部可见客户；阶段客户数不代表历史转化率。";

        WorkbenchChartsDtos.Charts out = new WorkbenchChartsDtos.Charts();
        out.setMonths(months);
        out.setCustomerStages(resource(operating, "客户阶段", customerSource, this::customerStages));
        out.setInventory(resource(operating, "仓库出租", INVENTORY_SOURCE, this::inventory));
        out.setRentTrend(resource(finance, "收租原单趋势", TREND_SOURCE, () -> rentTrend(months)));
        out.setCashflow(resource(finance, "计划现金流", CASHFLOW_SOURCE, () -> cashflow(months)));
        return out;
    }

    private List<WorkbenchChartsDtos.CustomerStage> customerStages() {
        // 源 service 已使用当前 UserContext 做业务角色本人+公海过滤；只投影计数。
        PipelineResponse pipeline = Objects.requireNonNull(customerService.pipeline());
        List<WorkbenchChartsDtos.CustomerStage> out = new ArrayList<>();
        for (PipelineResponse.Column col : Objects.requireNonNull(pipeline.getColumns())) {
            WorkbenchChartsDtos.CustomerStage stage = new WorkbenchChartsDtos.CustomerStage();
            stage.setPhase(Objects.requireNonNull(col.getPhase()));
            stage.setCount(Objects.requireNonNull(col.getCount()));
            out.add(stage);
        }
        return out;
    }

    private WorkbenchChartsDtos.InventorySummary inventory() {
        InvDtos.Overview overview = Objects.requireNonNull(invService.overview());
        WorkbenchChartsDtos.InventorySummary out = new WorkbenchChartsDtos.InventorySummary();
        out.setItemCount(overview.getItemCount());
        out.setActiveRentalCount(overview.getActiveRentalCount());
        out.setOverdueRentalCount(overview.getOverdueRentalCount());
        return out;
    }

    private WorkbenchChartsDtos.RentTrend rentTrend(int months) {
        // 沿用 BI 正常原单口径，不自行推算净收款，也不把来源缺失的月份补成零。
        List<BiDtos.TrendPoint> receivable = Objects.requireNonNull(biService.trend("receivable", months).getPoints());
        List<BiDtos.TrendPoint> collected = Objects.requireNonNull(biService.trend("collection", months).getPoints());
        if (receivable.size() != months || collected.size() != months) {
            throw new IllegalStateException("收租趋势月份不完整");
        }
        List<WorkbenchChartsDtos.RentTrendPoint> points = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            BiDtos.TrendPoint due = receivable.get(i);
            BiDtos.TrendPoint received = collected.get(i);
            String period = Objects.requireNonNull(due.getPeriod());
            if (!period.equals(received.getPeriod())) {
                throw new IllegalStateException("收租趋势月份不一致");
            }
            WorkbenchChartsDtos.RentTrendPoint point = new WorkbenchChartsDtos.RentTrendPoint();
            point.setPeriod(period);
            point.setReceivable(Objects.requireNonNull(due.getValue()));
            point.setCollected(Objects.requireNonNull(received.getValue()));
            points.add(point);
        }
        WorkbenchChartsDtos.RentTrend out = new WorkbenchChartsDtos.RentTrend();
        out.setPoints(points);
        return out;
    }

    private WorkbenchChartsDtos.CashflowSummary cashflow(int months) {
        CashflowDtos.Cashflow cashflow = Objects.requireNonNull(cashflowService.cashflow(months));
        WorkbenchChartsDtos.CashflowSummary out = new WorkbenchChartsDtos.CashflowSummary();
        out.setAsOf(Objects.requireNonNull(cashflow.getAsOf()));
        out.setReceivable(bucket(cashflow.getReceivable()));
        out.setPayable(bucket(cashflow.getPayable()));
        List<CashflowDtos.MonthFlow> flows = Objects.requireNonNull(cashflow.getForecast());
        if (flows.size() != months) {
            throw new IllegalStateException("计划现金流月份不完整");
        }
        List<WorkbenchChartsDtos.ForecastPoint> points = new ArrayList<>();
        for (CashflowDtos.MonthFlow flow : flows) {
            WorkbenchChartsDtos.ForecastPoint point = new WorkbenchChartsDtos.ForecastPoint();
            point.setPeriod(Objects.requireNonNull(flow.getPeriod()));
            point.setInflow(Objects.requireNonNull(flow.getInflow()));
            point.setOutflow(Objects.requireNonNull(flow.getOutflow()));
            point.setNet(Objects.requireNonNull(flow.getNet()));
            points.add(point);
        }
        out.setForecast(points);
        return out;
    }

    private WorkbenchChartsDtos.Bucket bucket(CashflowDtos.Bucket bucket) {
        Objects.requireNonNull(bucket);
        WorkbenchChartsDtos.Bucket out = new WorkbenchChartsDtos.Bucket();
        out.setWithin1Year(Objects.requireNonNull(bucket.getWithin1Year()));
        out.setBeyond1Year(Objects.requireNonNull(bucket.getBeyond1Year()));
        out.setTotal(Objects.requireNonNull(bucket.getTotal()));
        out.setCount(bucket.getCount());
        return out;
    }

    private <T> WorkbenchChartsDtos.Resource<T> resource(boolean allowed, String name, String source, Supplier<T> loader) {
        WorkbenchChartsDtos.Resource<T> out = new WorkbenchChartsDtos.Resource<>();
        out.setSource(source);
        if (!allowed) {
            out.setStatus("restricted");
            return out;
        }
        try {
            out.setData(Objects.requireNonNull(loader.get()));
            out.setStatus("ready");
        } catch (Exception e) {
            log.warn("[工作台图表] {} 聚合失败", name, e);
            out.setStatus("error");
            out.setData(null);
        }
        return out;
    }
}
