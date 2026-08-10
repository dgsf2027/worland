package top.aole.rent.modules.pdca.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.rent.modules.pdca.dto.PdcaDtos;

/**
 * PDCA 到期回查调度器(M5-05)。§4.14 cron 双出口:
 * ① {@link #dailyRecheckDue()} 带 {@code @Scheduled} 每日 04:00(@EnableScheduling 已注册);
 * ② 执行体委托 {@link ActionItemService#recheckDue()}——手动/测试可直调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdcaRecheckScheduler {

    private final ActionItemService actionItemService;

    /** 每日 04:00 自动回查到期改进项(通过关闭 / 未达升级 / 取不到人工判定)。 */
    @Scheduled(cron = "0 0 4 * * ?")
    public void dailyRecheckDue() {
        PdcaDtos.RecheckBatchResp r = runRecheckDueCron();
        if (r.getTotal() > 0) {
            log.info("[PDCA 回查 cron] 到期 {} 项 → 通过 {} · 未达升级 {} · 人工 {}",
                    r.getTotal(), r.getPassed(), r.getFailed(), r.getManual());
        }
    }

    /** 执行体(与 cron 解耦)。 */
    public PdcaDtos.RecheckBatchResp runRecheckDueCron() {
        return actionItemService.recheckDue();
    }
}
