package top.aole.rent.modules.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.analytics.service.CoverageGapService;
import top.aole.rent.modules.billing.dto.BillDtos;
import top.aole.rent.modules.billing.service.OverdueService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;

import java.time.LocalDate;
import java.util.List;

/**
 * 系统派单服务(M5-01)。把"钱该动没动 / 事该做没做"的信号自动转成任务派给对应角色(幂等)。
 *
 * <p>三源:
 * <ul>
 *   <li>逾期案(未关闭 overdue_case)→ 催收任务派「财务」(biz_type=overdue_case)</li>
 *   <li>兑付缺口红灯(coverage_gap 红点)→ 兑付缺口任务派「财务」(biz_type=coverage_gap·biz_id=payable)</li>
 *   <li>合同到期前 N 天(rule reminder_contract_expiry_days)→ 到期跟进任务派「业务」(biz_type=contract)</li>
 * </ul>
 * 幂等由 {@link TaskService#systemDispatch} 保证(同 biz 未完成不重复开)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskGenService {

    private final TaskService taskService;
    private final OverdueService overdueService;
    private final CoverageGapService coverageGapService;
    private final ContractMapper contractMapper;

    /** 系统派单扫描结果。 */
    @Data
    public static class GenResult {
        private int overdueOpened;
        private int coverageGapOpened;
        private int expiryOpened;

        public int getTotal() {
            return overdueOpened + coverageGapOpened + expiryOpened;
        }
    }

    @Transactional
    public GenResult runSystemDispatch() {
        GenResult r = new GenResult();

        // 1) 逾期案 → 催收(财务)
        try {
            List<BillDtos.OverdueItem> overdue = overdueService.list(null, null, 1, 500).getRecords();
            for (BillDtos.OverdueItem o : overdue) {
                if (o.getClosedAt() != null || "已关闭".equals(o.getStatus()) || "已恢复".equals(o.getStatus())) {
                    continue;
                }
                Long id = taskService.systemDispatch("催收",
                        "逾期跟进 · " + safe(o.getContractNo()) + " · " + safe(o.getCustomerName())
                                + "(" + safe(o.getStep()) + ")",
                        "财务", "overdue_case", o.getId(), o.getDeadline(), "高");
                if (id != null) {
                    r.setOverdueOpened(r.getOverdueOpened() + 1);
                }
            }
        } catch (Exception e) {
            log.warn("[系统派单] 逾期扫描失败(跳过): {}", e.getMessage());
        }

        // 2) 兑付缺口红灯 → 兑付缺口(财务)
        try {
            CashflowDtos.CoverageGapReport gap = coverageGapService.coverageGap(null);
            if (gap != null && gap.getItems() != null) {
                for (CashflowDtos.GapItem g : gap.getItems()) {
                    // 只对红灯缺口(累计流出>累计流入)派单
                    if (g.getCumOutflow() != null && g.getCumInflow() != null
                            && g.getCumOutflow().compareTo(g.getCumInflow()) > 0) {
                        Long id = taskService.systemDispatch("兑付缺口",
                                "账期兑付缺口 · " + safe(g.getStage()) + " 应付到期 " + g.getDueDate()
                                        + " ¥" + g.getPayableAmount(),
                                "财务", "coverage_gap", g.getPayableId(), g.getDueDate(), "高");
                        if (id != null) {
                            r.setCoverageGapOpened(r.getCoverageGapOpened() + 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[系统派单] 兑付缺口扫描失败(跳过): {}", e.getMessage());
        }

        // 3) 合同到期前 N 天 → 到期跟进(业务)
        try {
            int leadDays = 30;
            LocalDate today = LocalDate.now();
            List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getIsDeleted, 0)
                    .ne(Contract::getStatus, "已作废"));
            for (Contract c : contracts) {
                if (c.getStartDate() == null || c.getTermMonths() == null) {
                    continue;
                }
                LocalDate endDate = c.getStartDate().plusMonths(c.getTermMonths());
                long daysToEnd = today.until(endDate).getDays()
                        + today.until(endDate).getMonths() * 30L
                        + today.until(endDate).getYears() * 365L;
                if (!endDate.isBefore(today) && daysToEnd <= leadDays) {
                    Long id = taskService.systemDispatch("合同到期跟进",
                            "合同到期跟进 · " + safe(c.getNo()) + " 于 " + endDate + " 到期(续租/转让)",
                            "业务", "contract", c.getId(), endDate, "中");
                    if (id != null) {
                        r.setExpiryOpened(r.getExpiryOpened() + 1);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[系统派单] 合同到期扫描失败(跳过): {}", e.getMessage());
        }

        log.info("[系统派单] 逾期催收 {} · 兑付缺口 {} · 到期跟进 {}",
                r.getOverdueOpened(), r.getCoverageGapOpened(), r.getExpiryOpened());
        return r;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
