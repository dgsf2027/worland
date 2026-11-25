package top.aole.rent.modules.contract.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 付款到期日与工作日计算器(V129)。
 *
 * <p>支持 4 种触发时点(下单 / 入库 / 验收合格 / 审核通过)与 4 种到期规则:
 * <ul>
 *   <li>{@code after_days}: 基准日 + N 天(兼容既有逻辑)</li>
 *   <li>{@code month_end_days}: 当月月末 + N 天(如「当月月结 30 天」)</li>
 *   <li>{@code next_month_day}: 次月指定日(缺省取次月月末,如「次月付 30%」)</li>
 *   <li>{@code next_2_month_day}: 次次月指定日(缺省取次次月月末,如「次次月付 30%」)</li>
 * </ul>
 */
public final class PaymentDueDateCalculator {

    public static final String TRIGGER_ORDER = "下单";
    public static final String TRIGGER_RECEIVE = "入库";
    public static final String TRIGGER_ACCEPT = "验收合格";
    public static final String TRIGGER_AUDIT = "审核通过";

    public static final String RULE_AFTER_DAYS = "after_days";
    public static final String RULE_MONTH_END_DAYS = "month_end_days";
    public static final String RULE_NEXT_MONTH_DAY = "next_month_day";
    public static final String RULE_NEXT_2_MONTH_DAY = "next_2_month_day";

    public static final Set<String> VALID_TRIGGERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            TRIGGER_ORDER, TRIGGER_RECEIVE, TRIGGER_ACCEPT, TRIGGER_AUDIT)));

    public static final Set<String> VALID_RULES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            RULE_AFTER_DAYS, RULE_MONTH_END_DAYS, RULE_NEXT_MONTH_DAY, RULE_NEXT_2_MONTH_DAY)));

    private PaymentDueDateCalculator() {
    }

    public static String normalizeRule(String rule) {
        if (rule == null || rule.trim().isEmpty()) {
            return RULE_AFTER_DAYS;
        }
        String r = rule.trim();
        return VALID_RULES.contains(r) ? r : RULE_AFTER_DAYS;
    }

    public static LocalDate computeDueDate(LocalDate baseDate, String dueRule, Integer dueDays, Integer dueDayOfMonth) {
        if (baseDate == null) {
            return null;
        }
        String rule = normalizeRule(dueRule);
        int days = dueDays == null || dueDays < 0 ? 0 : dueDays;
        switch (rule) {
            case RULE_MONTH_END_DAYS: {
                LocalDate monthEnd = YearMonth.from(baseDate).atEndOfMonth();
                return monthEnd.plusDays(days);
            }
            case RULE_NEXT_MONTH_DAY:
                return monthDay(YearMonth.from(baseDate).plusMonths(1), dueDayOfMonth).plusDays(days);
            case RULE_NEXT_2_MONTH_DAY:
                return monthDay(YearMonth.from(baseDate).plusMonths(2), dueDayOfMonth).plusDays(days);
            case RULE_AFTER_DAYS:
            default:
                return baseDate.plusDays(days);
        }
    }

    /** 规则人类可读描述(如「入库+90天」「审核通过·当月月结+30天」「审核通过·次月」「审核通过·次次月」)。 */
    public static String describeRule(String triggerPoint, String dueRule, Integer dueDays, Integer dueDayOfMonth) {
        String trigger = triggerPoint == null ? TRIGGER_RECEIVE : triggerPoint;
        String rule = normalizeRule(dueRule);
        int days = dueDays == null ? 0 : dueDays;
        switch (rule) {
            case RULE_MONTH_END_DAYS:
                return trigger + "·当月月结" + (days > 0 ? "+" + days + "天" : "");
            case RULE_NEXT_MONTH_DAY:
                return trigger + "·次月" + (dueDayOfMonth != null ? dueDayOfMonth + "日" : "月末")
                        + (days > 0 ? "+" + days + "天" : "");
            case RULE_NEXT_2_MONTH_DAY:
                return trigger + "·次次月" + (dueDayOfMonth != null ? dueDayOfMonth + "日" : "月末")
                        + (days > 0 ? "+" + days + "天" : "");
            case RULE_AFTER_DAYS:
            default:
                return trigger + (days > 0 ? "+" + days + "天" : "");
        }
    }

    /** 加 N 个工作日(跳过周六/周日;供收租核销后推算销项专票截止日)。 */
    public static LocalDate addBusinessDays(LocalDate start, int businessDays) {
        if (start == null) {
            return null;
        }
        if (businessDays <= 0) {
            return start;
        }
        LocalDate cur = start;
        int added = 0;
        while (added < businessDays) {
            cur = cur.plusDays(1);
            DayOfWeek dow = cur.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return cur;
    }

    private static LocalDate monthDay(YearMonth ym, Integer dayOfMonth) {
        if (dayOfMonth == null || dayOfMonth <= 0) {
            return ym.atEndOfMonth();
        }
        int clamped = Math.min(dayOfMonth, ym.lengthOfMonth());
        return ym.atDay(clamped);
    }
}
