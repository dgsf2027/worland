package top.aole.rent.modules.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.task.domain.Task;
import top.aole.rent.modules.task.dto.TaskDtos;
import top.aole.rent.modules.task.mapper.TaskMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务中心服务(M5-01)。
 *
 * <p>能力:①手动派单(绑角色绑人) ②系统派单(逾期/兑付缺口/到期跟进 由 {@code TaskGenService} 幂等自动开)
 * ③转派留痕(transfer_log 只追加 append,不覆盖) ④完成校验(verify_required=1 缺 evidence 拒绝完成)
 * ⑤按角色/人/状态/来源过滤"我的任务"队列。
 *
 * <p><b>单一真相源(§4.24)</b>:系统任务幂等键 = biz_type+biz_id(未完成则不重复开),避免每日扫描重复堆单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ============ 派单(手动) ============

    @Transactional
    public Long dispatch(TaskDtos.DispatchRequest req) {
        if ((req.getAssigneeRole() == null || req.getAssigneeRole().trim().isEmpty())
                && req.getAssigneeUserId() == null) {
            throw new BizException(400, "派单需指定承接角色或承接人(任务绑角色绑人)");
        }
        CurrentUser u = UserContext.get();
        Task t = new Task();
        t.setNo(genNo());
        t.setTitle(req.getTitle().trim());
        t.setType(req.getType() != null ? req.getType() : "通用");
        t.setAssigneeRole(req.getAssigneeRole());
        t.setAssigneeUserId(req.getAssigneeUserId());
        t.setAssigneeUserName(req.getAssigneeUserName());
        t.setSource("派单");
        t.setStatus("待开始");
        t.setPriority(req.getPriority() != null ? req.getPriority() : "中");
        t.setBizType(req.getBizType());
        t.setBizId(req.getBizId());
        t.setDueDate(req.getDueDate());
        t.setVerifyRequired(Boolean.TRUE.equals(req.getVerifyRequired()) ? 1 : 0);
        t.setRemark(req.getRemark());
        if (u != null) {
            t.setCreatorId(u.getUserId());
            t.setCreatorName(u.getUserName());
        }
        taskMapper.insert(t);
        log.info("手动派单: no={}, title={}, 承接={}/{}, by={}",
                t.getNo(), t.getTitle(), t.getAssigneeRole(), t.getAssigneeUserName(),
                u != null ? u.getUserName() : "-");
        return t.getId();
    }

    // ============ 系统派单(幂等) ============

    /**
     * 系统自动开任务(逾期分派/兑付缺口/到期跟进)。幂等:同 biz_type+biz_id 存在未完成任务则跳过。
     * @return 新建任务 id;已存在则 null。
     */
    @Transactional
    public Long systemDispatch(String type, String title, String assigneeRole,
                               String bizType, Long bizId, LocalDate dueDate, String priority) {
        if (bizType != null && bizId != null) {
            Long dup = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getBizType, bizType)
                    .eq(Task::getBizId, bizId)
                    .notIn(Task::getStatus, "已完成", "已作废"));
            if (dup != null && dup > 0) {
                return null;
            }
        }
        Task t = new Task();
        t.setNo(genNo());
        t.setTitle(title);
        t.setType(type);
        t.setAssigneeRole(assigneeRole);
        t.setSource("系统");
        t.setStatus("待开始");
        t.setPriority(priority != null ? priority : "中");
        t.setBizType(bizType);
        t.setBizId(bizId);
        t.setDueDate(dueDate);
        t.setVerifyRequired(0);
        t.setCreatorName("系统");
        taskMapper.insert(t);
        return t.getId();
    }

    // ============ 转派(留痕只追加) ============

    @Transactional
    public TaskDtos.TaskItem transfer(Long id, TaskDtos.TransferRequest req) {
        Task t = load(id);
        if ("已完成".equals(t.getStatus()) || "已作废".equals(t.getStatus())) {
            throw new BizException(400, "任务状态为" + t.getStatus() + ",不可转派");
        }
        if ((req.getToRole() == null || req.getToRole().trim().isEmpty()) && req.getToUserId() == null) {
            throw new BizException(400, "转派需指定目标角色或目标人");
        }
        CurrentUser u = UserContext.get();
        String from = describeAssignee(t.getAssigneeRole(), t.getAssigneeUserName());
        String to = describeAssignee(req.getToRole(), req.getToUserName());

        // 追加转派留痕(只追加不覆盖)
        List<TaskDtos.TransferLogItem> logs = parseLog(t.getTransferLog());
        TaskDtos.TransferLogItem item = new TaskDtos.TransferLogItem();
        item.setFrom(from);
        item.setTo(to);
        item.setAt(LocalDateTime.now().format(TS));
        item.setBy(u != null ? u.getUserName() : "-");
        item.setReason(req.getReason());
        logs.add(item);
        t.setTransferLog(writeLog(logs));

        // 改承接
        t.setAssigneeRole(req.getToRole());
        t.setAssigneeUserId(req.getToUserId());
        t.setAssigneeUserName(req.getToUserName());
        taskMapper.updateById(t);
        log.info("任务转派留痕: no={}, {} → {}, by={}, reason={}",
                t.getNo(), from, to, item.getBy(), req.getReason());
        return toItem(load(id));
    }

    // ============ 开始/完成(完成需校验) ============

    @Transactional
    public TaskDtos.TaskItem start(Long id) {
        Task t = load(id);
        if ("已完成".equals(t.getStatus()) || "已作废".equals(t.getStatus())) {
            throw new BizException(400, "任务状态为" + t.getStatus() + ",不可开始");
        }
        t.setStatus("进行中");
        taskMapper.updateById(t);
        return toItem(t);
    }

    @Transactional
    public TaskDtos.TaskItem complete(Long id, TaskDtos.CompleteRequest req) {
        Task t = load(id);
        if ("已完成".equals(t.getStatus())) {
            throw new BizException(400, "任务已完成");
        }
        if ("已作废".equals(t.getStatus())) {
            throw new BizException(400, "任务已作废,不可完成");
        }
        String evidence = req != null ? req.getEvidence() : null;
        // 完成校验:verify_required=1 缺证据拒绝(不是打勾就算)
        if (Integer.valueOf(1).equals(t.getVerifyRequired())
                && (evidence == null || evidence.trim().isEmpty())) {
            throw new BizException(400, "本任务需上传完成证据(verify_evidence)方可置「已完成」");
        }
        if (evidence != null && !evidence.trim().isEmpty()) {
            t.setVerifyEvidence(evidence.trim());
        }
        t.setStatus("已完成");
        t.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(t);
        log.info("任务完成: no={}, verifyRequired={}, evidence={}", t.getNo(), t.getVerifyRequired(), t.getVerifyEvidence());
        return toItem(t);
    }

    // ============ 列表(我的任务队列) ============

    public PageResult<TaskDtos.TaskItem> list(String role, Long userId, String status, String source,
                                              String type, int page, int size) {
        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>()
                .eq(Task::getIsDeleted, 0)
                .eq(role != null && !role.isEmpty(), Task::getAssigneeRole, role)
                .eq(userId != null, Task::getAssigneeUserId, userId)
                .eq(status != null && !status.isEmpty(), Task::getStatus, status)
                .eq(source != null && !source.isEmpty(), Task::getSource, source)
                .eq(type != null && !type.isEmpty(), Task::getType, type)
                .orderByDesc(Task::getId);
        List<Task> all = taskMapper.selectList(qw);
        long total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(all.size(), from + size);
        List<TaskDtos.TaskItem> records = new ArrayList<>();
        if (from < all.size()) {
            for (Task t : all.subList(from, to)) {
                records.add(toItem(t));
            }
        }
        return new PageResult<>(total, page, size, records);
    }

    /** 我的任务:按当前登录角色/人过滤(工作台待办用)。 */
    public List<TaskDtos.TaskItem> myOpenTasks(String role, Long userId) {
        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>()
                .eq(Task::getIsDeleted, 0)
                .notIn(Task::getStatus, "已完成", "已作废")
                .and(w -> w.eq(role != null, Task::getAssigneeRole, role)
                        .or().eq(userId != null, Task::getAssigneeUserId, userId))
                .orderByDesc(Task::getId);
        List<TaskDtos.TaskItem> out = new ArrayList<>();
        for (Task t : taskMapper.selectList(qw)) {
            out.add(toItem(t));
        }
        return out;
    }

    // ============ 工具 ============

    private TaskDtos.TaskItem toItem(Task t) {
        TaskDtos.TaskItem it = new TaskDtos.TaskItem();
        it.setId(t.getId());
        it.setNo(t.getNo());
        it.setTitle(t.getTitle());
        it.setType(t.getType());
        it.setAssigneeRole(t.getAssigneeRole());
        it.setAssigneeUserId(t.getAssigneeUserId());
        it.setAssigneeUserName(t.getAssigneeUserName());
        it.setSource(t.getSource());
        it.setStatus(t.getStatus());
        it.setPriority(t.getPriority());
        it.setBizType(t.getBizType());
        it.setBizId(t.getBizId());
        it.setDueDate(t.getDueDate());
        boolean overdue = t.getDueDate() != null
                && !"已完成".equals(t.getStatus()) && !"已作废".equals(t.getStatus())
                && t.getDueDate().isBefore(LocalDate.now());
        it.setOverdue(overdue);
        it.setVerifyRequired(Integer.valueOf(1).equals(t.getVerifyRequired()));
        it.setVerifyEvidence(t.getVerifyEvidence());
        it.setCreatorName(t.getCreatorName());
        it.setFinishedAt(t.getFinishedAt());
        it.setRemark(t.getRemark());
        it.setTransferLog(parseLog(t.getTransferLog()));
        it.setCreateTime(t.getCreateTime());
        return it;
    }

    private Task load(Long id) {
        Task t = taskMapper.selectById(id);
        if (t == null || Integer.valueOf(1).equals(t.getIsDeleted())) {
            throw new BizException(404, "任务不存在: id=" + id);
        }
        return t;
    }

    private String describeAssignee(String role, String userName) {
        if (userName != null && !userName.isEmpty()) {
            return (role != null ? role + "·" : "") + userName;
        }
        return role != null ? role : "-";
    }

    private List<TaskDtos.TransferLogItem> parseLog(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TaskDtos.TransferLogItem>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeLog(List<TaskDtos.TransferLogItem> logs) {
        try {
            return objectMapper.writeValueAsString(logs);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String genNo() {
        String base = "TASK-" + LocalDate.now().format(NO_DAY) + "-" + (System.currentTimeMillis() % 100000);
        String no = base;
        int n = 1;
        while (taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getNo, no)) > 0) {
            no = base + "-" + (++n);
        }
        return no;
    }
}
