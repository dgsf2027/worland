package top.aole.rent.modules.pdca.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.pdca.domain.ActionItem;
import top.aole.rent.modules.pdca.dto.PdcaDtos;
import top.aole.rent.modules.pdca.mapper.ActionItemMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * PDCA 改进项服务(M5-05)。登记(自动取基线)/编辑/关闭 + 到期回查三分支
 * (通过关闭 / 未达升级 / 取不到需人工判定)。指标取值全程复用 {@link PdcaMetricService}(单一真相源)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionItemService {

    private final ActionItemMapper mapper;
    private final PdcaMetricService metricService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ============================== 查询 ==============================

    public List<PdcaDtos.ItemRow> list(String status, String scene) {
        LambdaQueryWrapper<ActionItem> qw = new LambdaQueryWrapper<ActionItem>()
                .eq(ActionItem::getIsDeleted, 0)
                .eq(status != null && !status.isEmpty(), ActionItem::getStatus, status)
                .eq(scene != null && !scene.isEmpty(), ActionItem::getMetricScene, scene)
                .orderByDesc(ActionItem::getId);
        List<PdcaDtos.ItemRow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (ActionItem a : mapper.selectList(qw)) {
            rows.add(toRow(a, today));
        }
        return rows;
    }

    // ============================== 登记 ==============================

    @Transactional
    public Long create(PdcaDtos.ItemSaveReq req) {
        CurrentUser u = UserContext.get();
        ActionItem a = new ActionItem();
        a.setNo(genNo());
        a.setMetricScene(req.getMetricScene());
        a.setIssue(req.getIssue());
        a.setAction(req.getAction());
        applyMetric(a, req);
        a.setRecheckDate(req.getRecheckDate() != null ? req.getRecheckDate() : LocalDate.now().plusMonths(1));
        a.setOwnerRole(req.getOwnerRole());
        a.setOwnerUserId(req.getOwnerUserId());
        a.setOwnerUserName(req.getOwnerUserName());
        a.setStatus(ActionItem.ST_OPEN);
        a.setAiDraft(0);
        a.setRemark(req.getRemark());
        if (u != null) {
            a.setCreatorId(u.getUserId());
            a.setCreatorName(u.getUserName());
        }
        mapper.insert(a);
        return a.getId();
    }

    @Transactional
    public void update(Long id, PdcaDtos.ItemSaveReq req) {
        ActionItem a = require(id);
        if (!ActionItem.ST_OPEN.equals(a.getStatus()) && !ActionItem.ST_ESCALATED.equals(a.getStatus())) {
            throw new BizException(400, "仅「进行中/未见效升级」的改进项可编辑");
        }
        a.setMetricScene(req.getMetricScene());
        a.setIssue(req.getIssue());
        a.setAction(req.getAction());
        applyMetric(a, req);
        if (req.getRecheckDate() != null) {
            a.setRecheckDate(req.getRecheckDate());
        }
        a.setOwnerRole(req.getOwnerRole());
        a.setOwnerUserId(req.getOwnerUserId());
        a.setOwnerUserName(req.getOwnerUserName());
        a.setRemark(req.getRemark());
        mapper.updateById(a);
    }

    @Transactional
    public void close(Long id, String note) {
        ActionItem a = require(id);
        a.setStatus(ActionItem.ST_CLOSED);
        a.setVerifyNote(note);
        mapper.updateById(a);
    }

    /** 登记/编辑时:绑定指标键→自动取基线值、默认方向 */
    private void applyMetric(ActionItem a, PdcaDtos.ItemSaveReq req) {
        a.setMetricKey(req.getMetricKey());
        a.setMetricParam(req.getMetricParam());
        a.setTargetValue(req.getTargetValue());
        if (req.getMetricKey() != null && !req.getMetricKey().isEmpty()) {
            PdcaMetricService.MetricDef def = metricService.def(req.getMetricKey());
            if (def == null) {
                throw new BizException(400, "未知验证指标键: " + req.getMetricKey());
            }
            a.setCompareOp(req.getCompareOp() != null && !req.getCompareOp().isEmpty()
                    ? req.getCompareOp() : def.compareOp);
            // 登记时取基线(改进前起点);编辑换指标时重取
            a.setBaselineValue(metricService.value(req.getMetricKey()));
            if (req.getTargetValue() == null) {
                a.setTargetValue(metricService.threshold(req.getMetricKey()));
            }
        } else {
            a.setCompareOp(null); // 无指标键 = 需人工判定
        }
    }

    // ============================== 到期回查 ==============================

    /** 单条一键回查:指标当前值 vs 目标 → 通过关闭 / 未达升级 / 需人工判定 */
    @Transactional
    public PdcaDtos.RecheckResp recheck(Long id) {
        ActionItem a = require(id);
        return doRecheck(a);
    }

    /** 批量到期回查:recheck_date 到点的「进行中」逐条判定 */
    @Transactional
    public PdcaDtos.RecheckBatchResp recheckDue() {
        List<ActionItem> due = mapper.selectList(new LambdaQueryWrapper<ActionItem>()
                .eq(ActionItem::getIsDeleted, 0)
                .eq(ActionItem::getStatus, ActionItem.ST_OPEN)
                .le(ActionItem::getRecheckDate, LocalDate.now()));
        PdcaDtos.RecheckBatchResp batch = new PdcaDtos.RecheckBatchResp();
        for (ActionItem a : due) {
            PdcaDtos.RecheckResp r = doRecheck(a);
            batch.getResults().add(r);
            batch.setTotal(batch.getTotal() + 1);
            if (ActionItem.VR_PASS.equals(r.getVerifyResult())) {
                batch.setPassed(batch.getPassed() + 1);
            } else if (ActionItem.VR_FAIL.equals(r.getVerifyResult())) {
                batch.setFailed(batch.getFailed() + 1);
            } else {
                batch.setManual(batch.getManual() + 1);
            }
        }
        log.info("[PDCA] 批量到期回查完成: 共{} 通过{} 未达{} 人工{}",
                batch.getTotal(), batch.getPassed(), batch.getFailed(), batch.getManual());
        return batch;
    }

    private PdcaDtos.RecheckResp doRecheck(ActionItem a) {
        PdcaDtos.RecheckResp r = new PdcaDtos.RecheckResp();
        r.setId(a.getId());
        r.setTargetValue(a.getTargetValue());

        BigDecimal value = a.getMetricKey() == null ? null : metricService.value(a.getMetricKey());
        r.setVerifyValue(value);

        String result;
        String newStatus;
        String note;
        if (a.getMetricKey() == null || value == null || a.getTargetValue() == null) {
            result = ActionItem.VR_MANUAL;
            newStatus = a.getStatus(); // 保持进行中,等人工判定
            note = "取不到指标当前值,需人工判定";
        } else {
            boolean pass = ActionItem.OP_LE.equals(a.getCompareOp())
                    ? value.compareTo(a.getTargetValue()) <= 0
                    : value.compareTo(a.getTargetValue()) >= 0;
            if (pass) {
                result = ActionItem.VR_PASS;
                newStatus = ActionItem.ST_PASSED;
                note = "指标已达目标,改进见效,自动关闭";
            } else {
                result = ActionItem.VR_FAIL;
                newStatus = ActionItem.ST_ESCALATED;
                note = "指标未达目标,改进未见效,升级";
            }
        }
        a.setVerifyValue(value);
        a.setVerifyResult(result);
        a.setVerifyNote(note);
        a.setVerifiedAt(LocalDateTime.now());
        a.setStatus(newStatus);
        mapper.updateById(a);

        r.setVerifyResult(result);
        r.setNewStatus(newStatus);
        r.setNote(note);
        return r;
    }

    // ============================== 工具 ==============================

    private ActionItem require(Long id) {
        ActionItem a = mapper.selectById(id);
        if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
            throw new BizException(404, "改进项不存在: id=" + id);
        }
        return a;
    }

    private PdcaDtos.ItemRow toRow(ActionItem a, LocalDate today) {
        PdcaDtos.ItemRow row = new PdcaDtos.ItemRow();
        row.setId(a.getId());
        row.setNo(a.getNo());
        row.setMetricScene(a.getMetricScene());
        row.setIssue(a.getIssue());
        row.setAction(a.getAction());
        row.setMetricKey(a.getMetricKey());
        row.setMetricParam(a.getMetricParam());
        row.setTargetValue(a.getTargetValue());
        row.setCompareOp(a.getCompareOp());
        row.setBaselineValue(a.getBaselineValue());
        row.setVerifyValue(a.getVerifyValue());
        row.setVerifyResult(a.getVerifyResult());
        row.setVerifyNote(a.getVerifyNote());
        row.setRecheckDate(a.getRecheckDate());
        row.setDueOrOverdue(ActionItem.ST_OPEN.equals(a.getStatus())
                && a.getRecheckDate() != null && !a.getRecheckDate().isAfter(today));
        row.setOwnerRole(a.getOwnerRole());
        row.setOwnerUserName(a.getOwnerUserName());
        row.setStatus(a.getStatus());
        row.setTaskId(a.getTaskId());
        row.setAiDraft(a.getAiDraft());
        row.setCreatorName(a.getCreatorName());
        row.setCreateTime(a.getCreateTime());
        return row;
    }

    private String genNo() {
        String day = LocalDate.now().format(NO_FMT);
        Long cnt = mapper.selectCount(new LambdaQueryWrapper<ActionItem>()
                .likeRight(ActionItem::getNo, "PDCA-" + day));
        return String.format("PDCA-%s-%03d", day, cnt + 1);
    }
}
