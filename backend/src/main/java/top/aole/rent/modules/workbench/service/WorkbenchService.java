package top.aole.rent.modules.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.analytics.service.CashflowService;
import top.aole.rent.modules.analytics.service.CoverageGapService;
import top.aole.rent.modules.analytics.service.ReturnAttributionService;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.dto.IdleAlertResponse;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.distribution.dto.DistributionDtos;
import top.aole.rent.modules.distribution.service.DistributionService;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.finance.service.TaxThresholdService;
import top.aole.rent.modules.task.dto.TaskDtos;
import top.aole.rent.modules.task.service.TaskService;
import top.aole.rent.modules.workbench.dto.WorkbenchDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工作台聚合服务(WT-01)。登录落地页数据源:各角色待办(task)+ 亮灯红点(逾期/空置/兑付缺口/税务500万/合同到期)
 * + 老板驾驶舱 KPI(在租率/应收/本月分配/加权回报),按角色投影可见(老板全量·财务/供应链/业务各自视图·LP 极简)。
 *
 * <p><b>单一真相源(§4.24)</b>:所有数字复用既有模块 service(不重算),仅做聚合与角色投影;
 * 每个数据源以 try/catch 隔离(某源异常不拖垮整页,记 warn 不静默)。可见性走 {@link DataScope}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchService {

    private final TaskService taskService;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final CashflowService cashflowService;
    private final CoverageGapService coverageGapService;
    private final ReturnAttributionService returnAttributionService;
    private final DistributionService distributionService;
    private final TaxThresholdService taxThresholdService;

    /** 排除在"有效设备"之外的终态。 */
    private static final List<String> DEAD_STATUS = Arrays.asList("已转让", "报废");

    public WorkbenchDtos.Workbench workbench() {
        CurrentUser u = UserContext.require();
        String role = u.getRole();
        WorkbenchDtos.Workbench wb = new WorkbenchDtos.Workbench();
        wb.setRole(role);
        wb.setUserName(u.getUserName());
        wb.setScopeNote(scopeNote(role));

        // KPI(按角色投影)
        wb.setKpi(buildKpi(role));
        // 红点(按角色过滤)
        wb.setRedPoints(buildRedPoints(role));
        // 我的待办
        List<TaskDtos.TaskItem> tasks = taskService.myOpenTasks(role, u.getUserId());
        List<WorkbenchDtos.TaskBrief> briefs = new ArrayList<>();
        for (TaskDtos.TaskItem t : tasks) {
            briefs.add(toBrief(t));
        }
        wb.setMyTasks(briefs);
        wb.setMyTaskCount(briefs.size());
        return wb;
    }

    // ============ KPI ============

    private WorkbenchDtos.Kpi buildKpi(String role) {
        boolean isLp = "LP".equalsIgnoreCase(role);
        boolean boss = "老板".equals(role) || "GP".equalsIgnoreCase(role);
        boolean finance = "财务".equals(role);

        WorkbenchDtos.Kpi kpi = new WorkbenchDtos.Kpi();
        String period = YearMonth.now().toString();
        kpi.setPeriod(period);

        // 在租率(除 LP 外可见)
        if (!isLp) {
            try {
                long active = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getIsDeleted, 0).notIn(Asset::getStatus, DEAD_STATUS));
                long rented = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getIsDeleted, 0).eq(Asset::getStatus, "在租"));
                kpi.setActiveAssetCount((int) active);
                kpi.setRentedCount((int) rented);
                kpi.setRentedRate(active > 0
                        ? BigDecimal.valueOf(rented).divide(BigDecimal.valueOf(active), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
            } catch (Exception e) {
                log.warn("[工作台] 在租率聚合失败: {}", e.getMessage());
            }
        }

        // 应收合计(老板/财务)
        if (boss || finance) {
            try {
                CashflowDtos.Cashflow cf = cashflowService.cashflow(null);
                if (cf != null && cf.getReceivable() != null) {
                    kpi.setReceivableTotal(cf.getReceivable().getTotal());
                }
            } catch (Exception e) {
                log.warn("[工作台] 应收聚合失败: {}", e.getMessage());
            }
        }

        // 本月分配(老板/财务)
        if (boss || finance) {
            try {
                List<DistributionDtos.DistributionItem> ds = distributionService.list(period, true);
                BigDecimal sum = BigDecimal.ZERO;
                for (DistributionDtos.DistributionItem d : ds) {
                    if (d.getDistributable() != null) {
                        sum = sum.add(d.getDistributable());
                    }
                }
                kpi.setMonthDistribution(sum);
            } catch (Exception e) {
                log.warn("[工作台] 本月分配聚合失败: {}", e.getMessage());
            }
        }

        // 加权回报(老板/财务/LP 可见·非成本敏感)
        if (boss || finance || isLp) {
            try {
                CashflowDtos.ReturnAttribution ra = returnAttributionService.attribution(null, "其他");
                if (ra != null) {
                    kpi.setWeightedReturn(ra.getTotalIrr());
                }
            } catch (Exception e) {
                log.warn("[工作台] 加权回报聚合失败: {}", e.getMessage());
            }
        }
        return kpi;
    }

    // ============ 红点 ============

    private List<WorkbenchDtos.RedPoint> buildRedPoints(String role) {
        boolean boss = "老板".equals(role) || "GP".equalsIgnoreCase(role);
        boolean finance = "财务".equals(role);
        boolean supply = "供应链".equals(role);
        boolean business = "业务".equals(role);
        List<WorkbenchDtos.RedPoint> out = new ArrayList<>();

        // 逾期(老板/财务)
        if (boss || finance) {
            try {
                int overdue = openOverdueCount();
                if (overdue > 0) {
                    out.add(redPoint("overdue", "逾期未处置", overdue, "红灯", "/rent"));
                }
            } catch (Exception e) {
                log.warn("[工作台] 逾期红点失败: {}", e.getMessage());
            }
        }
        // 空置(老板/供应链)
        if (boss || supply) {
            try {
                IdleAlertResponse idle = assetService.idleAlert();
                int c = idle != null && idle.getAlertCount() != null ? idle.getAlertCount() : 0;
                if (c > 0) {
                    out.add(redPoint("idle", "空置待处置", c, "预警", "/asset"));
                }
            } catch (Exception e) {
                log.warn("[工作台] 空置红点失败: {}", e.getMessage());
            }
        }
        // 兑付缺口(老板/财务)
        if (boss || finance) {
            try {
                CashflowDtos.CoverageGapReport gap = coverageGapService.coverageGap(null);
                int c = gap != null ? gap.getRedCount() : 0;
                if (c > 0) {
                    out.add(redPoint("coverage_gap", "账期兑付缺口", c, "红灯", "/cashflow"));
                }
            } catch (Exception e) {
                log.warn("[工作台] 兑付缺口红点失败: {}", e.getMessage());
            }
        }
        // 税务 500 万(老板/财务)
        if (boss || finance) {
            try {
                VoucherDtos.TaxThreshold tax = taxThresholdService.threshold(null);
                if (tax != null && !"正常".equals(tax.getLevel())) {
                    out.add(redPoint("tax", "税务 500 万红线(" + tax.getLevel() + ")", 1, tax.getLevel(), "/voucher"));
                }
            } catch (Exception e) {
                log.warn("[工作台] 税务红点失败: {}", e.getMessage());
            }
        }
        // 合同到期跟进(老板/业务)——用系统已开的到期任务数呈现
        if (boss || business) {
            try {
                int c = openExpiryTaskCount();
                if (c > 0) {
                    out.add(redPoint("contract_expiry", "合同到期待跟进", c, "预警", "/contract"));
                }
            } catch (Exception e) {
                log.warn("[工作台] 到期红点失败: {}", e.getMessage());
            }
        }
        return out;
    }

    // ============ 工具 ============

    private int openOverdueCount() {
        // 未完成的系统"催收"任务近似口径,亦可直接读 overdue_case;此处读任务队列避免跨模块重复扫描
        return (int) taskService.list(null, null, null, "系统", "催收", 1, 1000).getTotal();
    }

    private int openExpiryTaskCount() {
        return (int) taskService.list(null, null, null, null, "合同到期跟进", 1, 1000).getTotal();
    }

    private WorkbenchDtos.RedPoint redPoint(String key, String label, int count, String level, String link) {
        WorkbenchDtos.RedPoint r = new WorkbenchDtos.RedPoint();
        r.setKey(key);
        r.setLabel(label);
        r.setCount(count);
        r.setLevel(level);
        r.setLink(link);
        return r;
    }

    private WorkbenchDtos.TaskBrief toBrief(TaskDtos.TaskItem t) {
        WorkbenchDtos.TaskBrief b = new WorkbenchDtos.TaskBrief();
        b.setId(t.getId());
        b.setNo(t.getNo());
        b.setTitle(t.getTitle());
        b.setType(t.getType());
        b.setSource(t.getSource());
        b.setStatus(t.getStatus());
        b.setPriority(t.getPriority());
        b.setOverdue(t.getOverdue());
        b.setDueDate(t.getDueDate() != null ? t.getDueDate().toString() : null);
        return b;
    }

    private String scopeNote(String role) {
        switch (role == null ? "" : role) {
            case "老板":
                return "全量视图 · 驾驶舱 KPI + 全部红点 + 全部待办";
            case "财务":
                return "财务视图 · 应收/分配/回报 + 逾期/兑付缺口/税务红点";
            case "供应链":
                return "供应链视图 · 在租率 + 空置红点 + 集采/BOM 待办";
            case "业务":
                return "业务视图 · 在租率 + 合同到期红点 + 跟进待办(名下+公海)";
            case "LP":
                return "LP 视图 · 仅加权回报(字段级保密:不见成本/应收明细)";
            default:
                return "受限视图 · 仅本人待办";
        }
    }
}
