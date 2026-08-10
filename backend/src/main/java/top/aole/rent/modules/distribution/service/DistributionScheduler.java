package top.aole.rent.modules.distribution.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.rent.modules.distribution.dto.DistributionDtos;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 每月 5 号结账分配调度器(M3-03/04)。参照 §4.14 cron 双出口精神(Java 版):
 * ① {@link #monthlyDistribution()} 带 {@code @Scheduled},每月 5 日 04:00 触发(@EnableScheduling 已注册);
 * ② 执行体委托 {@link DistributionService#run}——与 cron 解耦,手动/测试直接调。
 *
 * <p>结算对象 = 上一自然月(5 号结上月);已存在 active 分配则幂等跳过(不带 force,不覆盖人工结账)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributionScheduler {

    private final DistributionService distributionService;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 每月 5 日 04:00 结算上月分配(幂等:已有 active 则跳过)。 */
    @Scheduled(cron = "0 0 4 5 * ?")
    public void monthlyDistribution() {
        String period = LocalDate.now().minusMonths(1).format(YM);
        try {
            DistributionDtos.RunRequest req = new DistributionDtos.RunRequest();
            req.setPeriod(period);
            req.setRemark("每月5号自动结账");
            DistributionDtos.DistributionDetail d = distributionService.run(req);
            log.info("[分配 cron] 期 {} 结账完成 · 可分配 {} · 现金 {}",
                    period, d.getDistribution().getDistributable(), d.getDistribution().getCash50());
        } catch (Exception e) {
            // 幂等拒/无利润等属预期,记 info 不告警(人工已结账或本月无可分配)
            log.info("[分配 cron] 期 {} 跳过: {}", period, e.getMessage());
        }
    }
}
