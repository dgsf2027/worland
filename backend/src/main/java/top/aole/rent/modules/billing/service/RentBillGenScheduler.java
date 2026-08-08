package top.aole.rent.modules.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.rent.modules.billing.dto.BillDtos;

/**
 * 收租单生成调度器(M2-01)。参照 §4.14 cron 双出口精神(Java 版):
 * ① {@link #dailyGenRentBill()} 带 {@code @Scheduled},每日 01:00 触发(@EnableScheduling 已在启动类注册);
 * ② 执行体委托 {@link RentBillService#runRentBillGen()}——与 cron 解耦,手动/测试可直接调,不必等到点。
 * 禁 {@code require.main} 式自触发(那是 Node/ESM 反模式,Java 无此问题)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RentBillGenScheduler {

    private final RentBillService rentBillService;

    /** 每日 01:00 生成到期(T-lead)收租单。 */
    @Scheduled(cron = "0 0 1 * * ?")
    public void dailyGenRentBill() {
        BillDtos.GenResult r = runRentBillGenCron();
        if (r.getGenerated() > 0) {
            log.info("[收租单生成 cron] 生成 {} 张(窗口<= {})", r.getGenerated(), r.getHorizon());
        }
    }

    /** 执行体(与 cron 解耦,便于手动触发 /admin 与单测)。 */
    public BillDtos.GenResult runRentBillGenCron() {
        return rentBillService.runRentBillGen();
    }
}
