package top.aole.rent.modules.task.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 系统派单调度器(M5-01)。参照 §4.14 cron 双出口精神(Java 版):
 * ① {@link #dailySystemDispatch()} 带 {@code @Scheduled},每日 03:00 触发(@EnableScheduling 已注册);
 * ② 执行体委托 {@link TaskGenService#runSystemDispatch()}——与 cron 解耦,手动/测试直接调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDispatchScheduler {

    private final TaskGenService taskGenService;

    /** 每日 03:00 扫逾期/兑付缺口/合同到期,自动派任务给对应角色(幂等)。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailySystemDispatch() {
        TaskGenService.GenResult r = runSystemDispatchCron();
        if (r.getTotal() > 0) {
            log.info("[系统派单 cron] 新开任务 {} 个(逾期 {} · 兑付缺口 {} · 到期 {})",
                    r.getTotal(), r.getOverdueOpened(), r.getCoverageGapOpened(), r.getExpiryOpened());
        }
    }

    /** 执行体(与 cron 解耦,便于手动触发 /admin 与单测)。 */
    public TaskGenService.GenResult runSystemDispatchCron() {
        return taskGenService.runSystemDispatch();
    }
}
