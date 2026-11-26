package top.aole.rent.modules.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.distribution.dto.DistributionDtos;
import top.aole.rent.modules.distribution.service.DistributionService;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.finance.service.TaxThresholdService;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.task.dto.TaskDtos;
import top.aole.rent.modules.task.service.TaskService;
import top.aole.rent.modules.workbench.dto.WorkbenchDtos;
import top.aole.rent.modules.workbench.dto.WorkbenchWaterlineDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Autowired(required = false)
    private RentBillMapper rentBillMapper;
    @Autowired(required = false)
    private ContractMapper contractMapper;
    @Autowired(required = false)
    private PurchaseContractMapper purchaseContractMapper;
    @Autowired(required = false)
    private PurchaseInMapper purchaseInMapper;
    @Autowired(required = false)
    private WorkbenchCashWaterlineService waterlineService;

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

        // 加权回报(老板/财务/LP 可见·优先按真实生效合同本金加权年化利率计算,避免静态 30% 规则值误导)
        if (boss || finance || isLp) {
            try {
                BigDecimal realRate = realWeightedContractRate();
                if (realRate != null) {
                    kpi.setWeightedReturn(realRate);
                } else {
                    CashflowDtos.ReturnAttribution ra = returnAttributionService.attribution(null, "其他");
                    if (ra != null) {
                        kpi.setWeightedReturn(ra.getTotalIrr());
                    }
                }
            } catch (Exception e) {
                log.warn("[工作台] 加权回报聚合失败: {}", e.getMessage());
            }
        }
        return kpi;
    }

    // ============ 红点(全部来自真实业务单据实时口径,彻底消除历史残留假红灯) ============

    private List<WorkbenchDtos.RedPoint> buildRedPoints(String role) {
        boolean boss = "老板".equals(role) || "GP".equalsIgnoreCase(role);
        boolean finance = "财务".equals(role);
        boolean supply = "供应链".equals(role);
        boolean business = "业务".equals(role);
        List<WorkbenchDtos.RedPoint> out = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // 1. 真实逾期收租单(直查生效合同下的逾期/过期未结清正常收租单,不再读残留催收任务)
        if (boss || finance) {
            try {
                OverdueSummary ov = realOverdueBills(today);
                if (ov.count > 0) {
                    out.add(redPoint("overdue", "逾期收租单", ov.count, "红灯",
                            "/rent?status=逾期", "未收 ¥" + formatMoney(ov.amount)));
                }
            } catch (Exception e) {
                log.warn("[工作台] 逾期红点失败: {}", e.getMessage());
            }
        }

        // 2. 待开销项专票(含超期未开票标红)
        if (boss || finance) {
            try {
                InvoiceSummary inv = realPendingInvoices(today);
                if (inv.pendingCount > 0) {
                    String level = inv.overdueCount > 0 ? "红灯" : "预警";
                    String sub = inv.overdueCount > 0 ? ("含超期 " + inv.overdueCount + " 张") : "收款后待开具";
                    out.add(redPoint("pending_invoice", "待开销项专票", inv.pendingCount, level,
                            "/rent?invoiceStatus=待开票", sub));
                }
            } catch (Exception e) {
                log.warn("[工作台] 待开票红点失败: {}", e.getMessage());
            }
        }

        // 3. 近 30 天到期应付 / 资金水位缺口(老板/财务)
        if (boss || finance) {
            try {
                if (waterlineService != null) {
                    WorkbenchWaterlineDtos.CashWaterline wl = waterlineService.cashWaterline(6, null);
                    if (wl != null && wl.getDuePayable30dCount() > 0) {
                        boolean hasGap = wl.getMaxShortfall() != null && wl.getMaxShortfall().signum() > 0;
                        String level = (wl.getOverduePayable() != null && wl.getOverduePayable().signum() > 0) || hasGap
                                ? "红灯" : "预警";
                        String sub = "合计 ¥" + formatMoney(wl.getDuePayable30dAmount());
                        out.add(redPoint("payable_due_30d", "近30天到期应付", wl.getDuePayable30dCount(), level,
                                "/purchase-contract", sub));
                    }
                } else {
                    CashflowDtos.CoverageGapReport gap = coverageGapService.coverageGap(null);
                    int c = gap != null ? gap.getRedCount() : 0;
                    if (c > 0) {
                        out.add(redPoint("coverage_gap", "账期兑付缺口", c, "红灯", "/cashflow", null));
                    }
                }
            } catch (Exception e) {
                log.warn("[工作台] 应付/兑付缺口红点失败: {}", e.getMessage());
            }
        }

        // 4. 质保 90 天内到期(老板/供应链)
        if (boss || supply) {
            try {
                int expiring = warrantyExpiringCount(today);
                if (expiring > 0) {
                    out.add(redPoint("warranty_expiring", "质保90天内到期", expiring, "预警", "/asset", "及时核查质保索赔"));
                }
            } catch (Exception e) {
                log.warn("[工作台] 质保红点失败: {}", e.getMessage());
            }
        }

        // 5. 空置待处置(老板/供应链)
        if (boss || supply) {
            try {
                IdleAlertResponse idle = assetService.idleAlert();
                int c = idle != null && idle.getAlertCount() != null ? idle.getAlertCount() : 0;
                if (c > 0) {
                    out.add(redPoint("idle", "空置待处置", c, "预警", "/asset", "长期空置设备"));
                }
            } catch (Exception e) {
                log.warn("[工作台] 空置红点失败: {}", e.getMessage());
            }
        }

        // 6. 税务 500 万红线(老板/财务)
        if (boss || finance) {
            try {
                VoucherDtos.TaxThreshold tax = taxThresholdService.threshold(null);
                if (tax != null && !"正常".equals(tax.getLevel())) {
                    out.add(redPoint("tax", "税务 500 万红线(" + tax.getLevel() + ")", 1, tax.getLevel(), "/voucher", null));
                }
            } catch (Exception e) {
                log.warn("[工作台] 税务红点失败: {}", e.getMessage());
            }
        }

        // 7. 合同到期待跟进(老板/业务)——仅统计真实生效合同的到期跟进任务
        if (boss || business) {
            try {
                int c = openExpiryTaskCount();
                if (c > 0) {
                    out.add(redPoint("contract_expiry", "合同到期待跟进", c, "预警", "/contract", null));
                }
            } catch (Exception e) {
                log.warn("[工作台] 到期红点失败: {}", e.getMessage());
            }
        }
        return out;
    }

    // ============ 工具 ============

    private static class OverdueSummary {
        int count;
        BigDecimal amount = BigDecimal.ZERO;
    }

    private static class InvoiceSummary {
        int pendingCount;
        int overdueCount;
    }

    private BigDecimal realWeightedContractRate() {
        if (contractMapper == null) {
            return null;
        }
        List<Contract> active = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0)
                .eq(Contract::getStatus, "生效"));
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        for (Contract c : active) {
            BigDecimal rate = null;
            if (c.getLeaseAnnualRate() != null && c.getLeaseAnnualRate().signum() > 0) {
                rate = c.getLeaseAnnualRate().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            } else if (c.getTargetIrr() != null && c.getTargetIrr().signum() > 0) {
                rate = c.getTargetIrr();
            }
            if (rate == null) {
                continue;
            }
            BigDecimal weight = c.getFinalDealPrice() != null && c.getFinalDealPrice().signum() > 0
                    ? c.getFinalDealPrice()
                    : (c.getEquipmentTotal() != null && c.getEquipmentTotal().signum() > 0
                    ? c.getEquipmentTotal()
                    : BigDecimal.ONE);
            weightedSum = weightedSum.add(weight.multiply(rate));
            weightTotal = weightTotal.add(weight);
        }
        if (weightTotal.signum() <= 0) {
            return null;
        }
        return weightedSum.divide(weightTotal, 4, RoundingMode.HALF_UP);
    }

    private OverdueSummary realOverdueBills(LocalDate today) {
        OverdueSummary sum = new OverdueSummary();
        if (rentBillMapper == null || contractMapper == null) {
            sum.count = (int) taskService.list(null, null, null, "系统", "催收", 1, 1000).getTotal();
            return sum;
        }
        Set<Long> validContracts = activeNonVoidContractIds();
        if (validContracts.isEmpty()) {
            return sum;
        }
        List<RentBill> bills = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .in(RentBill::getContractId, validContracts)
                .eq(RentBill::getBillKind, "正常")
                .in(RentBill::getStatus, Arrays.asList("逾期", "待收", "部分已收")));
        for (RentBill b : bills) {
            boolean overdue = "逾期".equals(b.getStatus())
                    || (b.getDueDate() != null && b.getDueDate().isBefore(today));
            if (!overdue) {
                continue;
            }
            BigDecimal rem = (b.getAmount() == null ? BigDecimal.ZERO : b.getAmount())
                    .subtract(b.getReceivedAmount() == null ? BigDecimal.ZERO : b.getReceivedAmount())
                    .max(BigDecimal.ZERO);
            if (rem.signum() > 0) {
                sum.count++;
                sum.amount = sum.amount.add(rem);
            }
        }
        return sum;
    }

    private InvoiceSummary realPendingInvoices(LocalDate today) {
        InvoiceSummary sum = new InvoiceSummary();
        if (rentBillMapper == null || contractMapper == null) {
            return sum;
        }
        Set<Long> validContracts = activeNonVoidContractIds();
        if (validContracts.isEmpty()) {
            return sum;
        }
        List<RentBill> bills = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .in(RentBill::getContractId, validContracts)
                .eq(RentBill::getBillKind, "正常")
                .ne(RentBill::getStatus, "红冲")
                .eq(RentBill::getInvoiceStatus, "待开票"));
        for (RentBill b : bills) {
            sum.pendingCount++;
            if (b.getInvoiceDueDate() != null && b.getInvoiceDueDate().isBefore(today)) {
                sum.overdueCount++;
            }
        }
        return sum;
    }

    private int warrantyExpiringCount(LocalDate today) {
        LocalDate horizon = today.plusDays(90);
        int count = 0;
        if (purchaseInMapper != null) {
            Long poCnt = purchaseInMapper.selectCount(new LambdaQueryWrapper<PurchaseIn>()
                    .eq(PurchaseIn::getIsDeleted, 0)
                    .ne(PurchaseIn::getStatus, "已红冲")
                    .isNotNull(PurchaseIn::getWarrantyEndDate)
                    .ge(PurchaseIn::getWarrantyEndDate, today)
                    .le(PurchaseIn::getWarrantyEndDate, horizon));
            count += poCnt == null ? 0 : poCnt.intValue();
        }
        if (count == 0 && purchaseContractMapper != null) {
            List<PurchaseContract> pcs = purchaseContractMapper.selectList(new LambdaQueryWrapper<PurchaseContract>()
                    .ne(PurchaseContract::getStatus, "作废")
                    .isNotNull(PurchaseContract::getAcceptanceDate)
                    .isNotNull(PurchaseContract::getWarrantyYears));
            for (PurchaseContract pc : pcs) {
                if (pc.getWarrantyYears() != null && pc.getWarrantyYears() > 0) {
                    LocalDate end = pc.getAcceptanceDate().plusYears(pc.getWarrantyYears());
                    if (!end.isBefore(today) && !end.isAfter(horizon)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private Set<Long> activeNonVoidContractIds() {
        Set<Long> ids = new HashSet<>();
        List<Contract> list = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0)
                .ne(Contract::getStatus, "已作废"));
        for (Contract c : list) {
            ids.add(c.getId());
        }
        return ids;
    }

    private int openExpiryTaskCount() {
        return (int) taskService.list(null, null, null, null, "合同到期跟进", 1, 1000).getTotal();
    }

    private WorkbenchDtos.RedPoint redPoint(String key, String label, int count, String level, String link, String subtitle) {
        WorkbenchDtos.RedPoint r = new WorkbenchDtos.RedPoint();
        r.setKey(key);
        r.setLabel(label);
        r.setCount(count);
        r.setLevel(level);
        r.setLink(link);
        r.setSubtitle(subtitle);
        return r;
    }

    private static String formatMoney(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        BigDecimal abs = v.abs();
        if (abs.compareTo(new BigDecimal("10000")) >= 0) {
            return v.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "万";
        }
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
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
