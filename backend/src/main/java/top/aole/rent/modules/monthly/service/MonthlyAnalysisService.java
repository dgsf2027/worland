package top.aole.rent.modules.monthly.service;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.ai.domain.AiScenes;
import top.aole.rent.modules.ai.service.LlmGateway;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.analytics.service.CoverageGapService;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.finance.service.TaxThresholdService;
import top.aole.rent.modules.monthly.dto.MonthlyDtos;
import top.aole.rent.modules.monthly.dto.MonthlyDtos.AnalysisReport;
import top.aole.rent.modules.monthly.dto.MonthlyDtos.AnalysisSection;
import top.aole.rent.modules.monthly.dto.MonthlyDtos.DataPoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 《月度经营分析报告》起草服务(M3-07 第七件,DESIGN_DOC §4.3 analysis_json + §十一)。
 *
 * 固定七节:经营概况 / 收租回款 / 利润 / 资产在租 / 钱账健康 / 异常与风险 / 下月改进建议。
 * 铁律(§4.7 #7):规则引擎出数字(每节 dataPoints 带溯源·引 package_json),LLM 只起草综述(mock 断路)。
 * 脱敏(§十一):喂入 LLM 的 prompt/inputDigest 仅含七节叙述(不含成本/分配/身份),网关侧再落脱敏库。
 * 幂等(§4.19):bizKey=period,当日复用不重烧钱;force=true 重新起草。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyAnalysisService {

    private final MonthlyReportService monthlyReportService;
    private final TaxThresholdService taxThresholdService;
    private final CoverageGapService coverageGapService;
    private final LlmGateway llmGateway;

    private static final String SRC_LEDGER = "收租台账";
    private static final String SRC_PROFIT = "利润表(ops)";
    private static final String SRC_CASH = "现金流水";
    private static final String SRC_DUE = "往来表";
    private static final String SRC_ASSET = "资产快照";
    private static final String SRC_TAX = "500万营收红线(税务账)";
    private static final String SRC_GAP = "账期兑付缺口扫描";

    public AnalysisReport analyze(String period, boolean force) {
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(period);
        String p = pkg.getPeriod();

        AnalysisReport report = new AnalysisReport();
        report.setPeriod(p);
        report.setPeriods(pkg.getPeriods());
        report.setLocked(pkg.isLocked());

        VoucherDtos.TaxThreshold tax = safeTax();
        CashflowDtos.CoverageGapReport gap = coverageGapService.coverageGap(7);

        List<AnalysisSection> sections = report.getSections();
        AnalysisSection overview = sectionOverview(pkg);
        sections.add(overview);
        sections.add(sectionCollection(pkg));
        sections.add(sectionProfit(pkg));
        sections.add(sectionAsset(pkg));
        sections.add(sectionMoney(pkg));
        sections.add(sectionRisk(pkg, tax, gap));
        sections.add(sectionImprovement(pkg, tax, gap));

        // ---- 一次 AI 起草综述(脱敏:仅喂七节叙述,不含成本/分配/身份)----
        String execDraft = overview.getNarrative();
        StringBuilder promptData = new StringBuilder();
        for (AnalysisSection s : sections) {
            promptData.append("【").append(s.getTitle()).append("】").append(s.getNarrative()).append("\n");
        }
        String inputDigest = JSONUtil.toJsonStr(sections); // 七节均无成本/分配/身份字段
        BigDecimal confidence = confidenceOf(pkg);

        LlmGateway.GatewayResult result = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.MONTHLY)
                .sceneLabel("月报起草:" + p)
                .bizKey(p)
                .prompt("你是设备租赁公司的财务分析助理。以下七节数字全部由规则引擎算好(一个都不许改),"
                        + "请据此写一段面向老板的《" + p + " 月度经营分析报告》综述:先给整体结论,"
                        + "再点出最该关注的 2-3 件事,最后落到下月动作,口吻务实不套话。\n"
                        + "草稿:" + execDraft + "\n七节数据(已脱敏·无成本/分配/身份):\n" + promptData)
                .reasoning("七节数字来自六件套:收租台账(应收/回款)、利润表 ops(收入/折旧/经营利润)、"
                        + "现金流水+往来(钱账)、资产快照(在租率/家底)、500万红线+兑付缺口(风险);"
                        + "LLM 仅起草综述文字,未参与任何计算(§4.7 #7)。喂入前已剔除成本/分配/身份(§十一 脱敏)。")
                .inputDigest(inputDigest)
                .confidence(confidence)
                .confidenceSource("computed")
                .promptFingerprint("rent-monthly-v1")
                .fallbackText(execDraft)
                .build(), force);

        report.setAiText(result.getCall().getOutputText());
        report.setLlmCallId(result.getCall().getId());
        report.setCacheHit(result.isCacheHit());
        report.setModel(result.getCall().getModel());
        report.setMock(result.getCall().getModel() != null && result.getCall().getModel().startsWith("mock"));
        return report;
    }

    // ============================== 七节 ==============================

    /** 一、经营概况 */
    private AnalysisSection sectionOverview(MonthlyDtos.PackageResp pkg) {
        BigDecimal rev = pkg.getProfit().getOperatingProfit();
        BigDecimal lease = plAmount(pkg, "leaseRevenue");
        BigDecimal received = pkg.getRentLedger().getTotalReceived();
        BigDecimal book = pkg.getAsset().getBookValueTotal();

        AnalysisSection s = section("overview", "一、经营概况");
        s.getDataPoints().add(new DataPoint("租赁收入", money(lease), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("本期回款", money(received), SRC_LEDGER));
        s.getDataPoints().add(new DataPoint("经营利润", money(rev), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("资产账面净值", money(book), SRC_ASSET));
        s.setNarrative(String.format("%s 租赁收入 %s 元,实际回款 %s 元,经营利润 %s 元;在册设备账面净值 %s 元。",
                pkg.getPeriod(), money(lease), money(received), money(rev), money(book)));
        return s;
    }

    /** 二、收租回款 */
    private AnalysisSection sectionCollection(MonthlyDtos.PackageResp pkg) {
        MonthlyDtos.RentLedger led = pkg.getRentLedger();
        AnalysisSection s = section("collection", "二、收租回款");
        s.getDataPoints().add(new DataPoint("本期应收", money(led.getTotalReceivable()), SRC_LEDGER));
        s.getDataPoints().add(new DataPoint("本期已收", money(led.getTotalReceived()), SRC_LEDGER));
        s.getDataPoints().add(new DataPoint("待收", money(led.getTotalOutstanding()), SRC_LEDGER));
        s.getDataPoints().add(new DataPoint("回款率", led.getCollectionRate() + "%", SRC_LEDGER));
        s.getDataPoints().add(new DataPoint("逾期单数", led.getOverdueCount() + " 单", SRC_LEDGER));
        s.setNarrative(String.format("本期共 %d 张收租单,应收 %s 元、已收 %s 元(回款率 %s%%),待收 %s 元;逾期 %d 单。%s",
                led.getBillCount(), money(led.getTotalReceivable()), money(led.getTotalReceived()),
                led.getCollectionRate(), money(led.getTotalOutstanding()), led.getOverdueCount(),
                led.getOverdueCount() > 0 ? "逾期单需按三步走(延期→罚息→锁机)跟进。" : "回款情况正常。"));
        return s;
    }

    /** 三、利润 */
    private AnalysisSection sectionProfit(MonthlyDtos.PackageResp pkg) {
        BigDecimal lease = plAmount(pkg, "leaseRevenue");
        BigDecimal depr = plAmount(pkg, "depreciation");
        BigDecimal op = pkg.getProfit().getOperatingProfit();
        AnalysisSection s = section("profit", "三、利润");
        s.getDataPoints().add(new DataPoint("租赁收入", money(lease), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("折旧费用", money(depr), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("经营利润", money(op), SRC_PROFIT));
        s.setNarrative(String.format("经营账(ops)口径:租赁收入 %s 元,计提折旧 %s 元,经营利润 %s 元"
                        + "(= 租赁收入 − 折旧费用 ± 残值损益)。%s",
                money(lease), money(depr.abs()), money(op),
                op.signum() < 0 ? "本期经营利润为负,需关注收入覆盖折旧的爬坡进度。" : "利润为正。"));
        return s;
    }

    /** 四、资产在租 */
    private AnalysisSection sectionAsset(MonthlyDtos.PackageResp pkg) {
        MonthlyDtos.AssetSnapshot a = pkg.getAsset();
        AnalysisSection s = section("asset", "四、资产在租");
        s.getDataPoints().add(new DataPoint("在册设备", a.getTotal() + " 台", SRC_ASSET));
        s.getDataPoints().add(new DataPoint("在租", a.getRentedCount() + " 台", SRC_ASSET));
        s.getDataPoints().add(new DataPoint("闲置", a.getIdleCount() + " 台", SRC_ASSET));
        s.getDataPoints().add(new DataPoint("待处置", a.getPendingDisposal() + " 台", SRC_ASSET));
        s.getDataPoints().add(new DataPoint("在租率", a.getRentedRatio() + "%", SRC_ASSET));
        s.setNarrative(String.format("在册设备 %d 台,在租 %d 台(在租率 %s%%),闲置 %d 台,待处置 %d 台;"
                        + "市场价家底 %s 元、账面净值 %s 元。%s",
                a.getTotal(), a.getRentedCount(), a.getRentedRatio(), a.getIdleCount(), a.getPendingDisposal(),
                money(a.getMarketPriceTotal()), money(a.getBookValueTotal()),
                a.getIdleCount() > 0 ? "闲置设备应尽快匹配客户投放,减少空置。" : "设备利用充分。"));
        return s;
    }

    /** 五、钱账健康 */
    private AnalysisSection sectionMoney(MonthlyDtos.PackageResp pkg) {
        MonthlyDtos.CashflowSheet cf = pkg.getCashflow();
        MonthlyDtos.DueSheet du = pkg.getDueSheet();
        AnalysisSection s = section("money", "五、钱账健康");
        s.getDataPoints().add(new DataPoint("本期现金净流入", money(cf.getNet()), SRC_CASH));
        s.getDataPoints().add(new DataPoint("应收未收合计", money(du.getReceivableTotal()), SRC_DUE));
        s.getDataPoints().add(new DataPoint("应付待付合计", money(du.getPayableTotal()), SRC_DUE));
        s.getDataPoints().add(new DataPoint("净头寸(应收−应付)", money(cf.getNetPosition()), SRC_CASH));
        s.getDataPoints().add(new DataPoint("供应商账期占用", money(cf.getSupplierCredit()), SRC_CASH));
        s.setNarrative(String.format("本期银行存款净流入 %s 元;应收未收 %s 元、应付待付 %s 元,净头寸 %s 元;"
                        + "供应商账期无息占用 %s 元。%s",
                money(cf.getNet()), money(du.getReceivableTotal()), money(du.getPayableTotal()),
                money(cf.getNetPosition()), money(cf.getSupplierCredit()),
                cf.getNetPosition().signum() < 0 ? "净头寸为负,需盯紧到期付款节奏。" : "钱账整体健康。"));
        return s;
    }

    /** 六、异常与风险(逾期/空置/500万/兑付缺口) */
    private AnalysisSection sectionRisk(MonthlyDtos.PackageResp pkg, VoucherDtos.TaxThreshold tax,
                                        CashflowDtos.CoverageGapReport gap) {
        List<String> flags = new ArrayList<>();
        int overdue = pkg.getRentLedger().getOverdueCount();
        if (overdue > 0) {
            flags.add(String.format("逾期收租 %d 单", overdue));
        }
        int idle = pkg.getAsset().getIdleCount();
        if (idle > 0) {
            flags.add(String.format("闲置(空置)设备 %d 台", idle));
        }
        if (tax != null && !"正常".equals(tax.getLevel())) {
            flags.add(String.format("500万营收红线:%s(已用 %s%%)", tax.getLevel(),
                    tax.getUsedRatio() == null ? "—" : pct(tax.getUsedRatio())));
        }
        if (gap != null && gap.isHasRedAlert()) {
            flags.add(String.format("账期兑付缺口红灯 %d 笔", gap.getRedCount()));
        }

        AnalysisSection s = section("risk", "六、异常与风险");
        s.getDataPoints().add(new DataPoint("逾期收租单", overdue + " 单", SRC_LEDGER));
        s.getDataPoints().add(new DataPoint("闲置设备", idle + " 台", SRC_ASSET));
        s.getDataPoints().add(new DataPoint("500万红线状态", tax == null ? "—" : tax.getLevel(), SRC_TAX));
        s.getDataPoints().add(new DataPoint("本年税务营收",
                tax == null ? "—" : money(tax.getCurrentRevenue()), SRC_TAX));
        s.getDataPoints().add(new DataPoint("兑付缺口红灯",
                (gap == null ? 0 : gap.getRedCount()) + " 笔", SRC_GAP));
        s.setNarrative(flags.isEmpty() ? "本月未发现重大风险红灯,各项指标在正常区间。"
                : "本月风险红灯:" + String.join(";", flags) + "。以上需逐项排查处置。");
        return s;
    }

    /** 七、下月改进建议 */
    private AnalysisSection sectionImprovement(MonthlyDtos.PackageResp pkg, VoucherDtos.TaxThreshold tax,
                                               CashflowDtos.CoverageGapReport gap) {
        List<String> tips = new ArrayList<>();
        if (pkg.getRentLedger().getOverdueCount() > 0) {
            tips.add(String.format("清逾期:本期 %d 单逾期收租,下月优先按三步走催收",
                    pkg.getRentLedger().getOverdueCount()));
        }
        if (pkg.getAsset().getIdleCount() > 0) {
            tips.add(String.format("去空置:%d 台设备闲置,下月对接客户加速投放", pkg.getAsset().getIdleCount()));
        }
        if (gap != null && gap.isHasRedAlert()) {
            tips.add(String.format("补缺口:%d 笔账期兑付缺口红灯,下月安排补款来源、盯紧到期付款",
                    gap.getRedCount()));
        }
        if (tax != null && !"正常".equals(tax.getLevel())) {
            tips.add("控红线:营收接近 500 万税务红线,下月复核开票节奏与主体分流");
        }
        if (pkg.getProfit().getOperatingProfit().signum() < 0) {
            tips.add("提利润:经营利润为负,下月抓收入爬坡、控折旧口径");
        }
        if (tips.isEmpty()) {
            tips.add("本月各项指标平稳,下月保持当前节奏,继续按 SOP 收租、盘点与投放");
        }

        AnalysisSection s = section("improvement", "七、下月改进建议");
        int i = 1;
        for (String t : tips) {
            s.getDataPoints().add(new DataPoint("建议" + i, t, "规则据本月逾期/空置/缺口/红线数据起草"));
            i++;
        }
        s.setNarrative("下月改进方向:" + String.join(";", tips) + "。");
        return s;
    }

    // ============================== 工具 ==============================

    private VoucherDtos.TaxThreshold safeTax() {
        try {
            return taxThresholdService.threshold(null);
        } catch (Exception e) {
            log.debug("[月报] 500万红线取数失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    private AnalysisSection section(String key, String title) {
        AnalysisSection s = new AnalysisSection();
        s.setKey(key);
        s.setTitle(title);
        return s;
    }

    private BigDecimal plAmount(MonthlyDtos.PackageResp pkg, String key) {
        return pkg.getProfit().getRows().stream()
                .filter(r -> key.equals(r.getKey()))
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .findFirst().orElse(BigDecimal.ZERO);
    }

    /** 置信分:有收入且有回款 → 高;空月 → 低(真算不写死)。 */
    private BigDecimal confidenceOf(MonthlyDtos.PackageResp pkg) {
        boolean hasRev = pkg.getProfit().getOperatingProfit().signum() != 0
                || plAmount(pkg, "leaseRevenue").signum() != 0;
        return hasRev ? new BigDecimal("0.90") : new BigDecimal("0.40");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal ratio) {
        return nz(ratio).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
