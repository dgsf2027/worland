package top.aole.rent.modules.contract;

import org.junit.jupiter.api.Test;
import top.aole.rent.modules.contract.service.PaymentDueDateCalculator;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 月结账期与工作日计算测试（金铁牛 40%/30%/30% 审核月结条款 + 云山快仓 10 个工作日开票）。
 */
class PaymentDueDateCalculatorTest {

    @Test
    void 金铁牛月结40_次月30_次次月30规则精确推算() {
        // 假设 2026-09-25 验收并资料审核通过
        LocalDate auditDate = LocalDate.of(2026, 9, 25);

        // 40%：当月月底(2026-09-30) + 30天 = 2026-10-30
        LocalDate d1 = PaymentDueDateCalculator.computeDueDate(auditDate,
                PaymentDueDateCalculator.RULE_MONTH_END_DAYS, 30, null);
        assertEquals(LocalDate.of(2026, 10, 30), d1);

        // 30%：次月(2026-10) 30日 = 2026-10-30；若未指定固定日则取次月月底(2026-10-31)
        LocalDate d2 = PaymentDueDateCalculator.computeDueDate(auditDate,
                PaymentDueDateCalculator.RULE_NEXT_MONTH_DAY, 0, 30);
        assertEquals(LocalDate.of(2026, 10, 30), d2);

        // 30%：次次月(2026-11) 30日 = 2026-11-30
        LocalDate d3 = PaymentDueDateCalculator.computeDueDate(auditDate,
                PaymentDueDateCalculator.RULE_NEXT_2_MONTH_DAY, 0, 30);
        assertEquals(LocalDate.of(2026, 11, 30), d3);

        // 跨二月自动钳制到月底（不会抛 DateTimeException）
        LocalDate feb = PaymentDueDateCalculator.computeDueDate(LocalDate.of(2027, 1, 15),
                PaymentDueDateCalculator.RULE_NEXT_MONTH_DAY, 0, 31);
        assertEquals(LocalDate.of(2027, 2, 28), feb);
    }

    @Test
    void 规则摘要可读() {
        String s1 = PaymentDueDateCalculator.describeRule("审核通过", "month_end_days", 30, null);
        String s2 = PaymentDueDateCalculator.describeRule("审核通过", "next_month_day", 0, 30);
        String s3 = PaymentDueDateCalculator.describeRule("审核通过", "next_2_month_day", 0, null);
        assertTrue(s1.contains("审核通过") && s1.contains("月结+30天"), s1);
        assertTrue(s2.contains("次月30日"), s2);
        assertTrue(s3.contains("次次月月末"), s3);
    }

    @Test
    void 工作日推算跳过周末() {
        // 2026-11-16 (周一) + 10 工作日 = 2026-11-30 (周一)
        LocalDate due = PaymentDueDateCalculator.addBusinessDays(LocalDate.of(2026, 11, 16), 10);
        assertEquals(LocalDate.of(2026, 11, 30), due);

        // 2026-11-15 (周日) + 10 工作日 = 2026-11-27 (周五)
        LocalDate dueSun = PaymentDueDateCalculator.addBusinessDays(LocalDate.of(2026, 11, 15), 10);
        assertEquals(LocalDate.of(2026, 11, 27), dueSun);
    }
}
