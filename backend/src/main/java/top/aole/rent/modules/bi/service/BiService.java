package top.aole.rent.modules.bi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.bi.dto.BiDtos;
import top.aole.rent.modules.bi.mapper.BiQueryMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BI 多维矩阵服务(M5-04 · 业务流§8.4)。<b>只读聚合不重算</b>:读各模块规范列即时算,
 * 口径与源模块一致。四指标可按 品类×客户×供应商 下钻 + 回款近 N 月趋势。
 *
 * <ul>
 *   <li>在租率 = 在租设备 /(总设备 − 报废),按 品类/供应商 下钻;红绿灯阈值走 rule_config(PDCA 复用)</li>
 *   <li>加权回报 = Σ(目标IRR × 月租) / Σ月租(生效合同),按 客户/性质 下钻</li>
 *   <li>应收账龄 = 未回款(应收−已收>0 的正常单)按到期日账龄分桶,按 客户 下钻</li>
 *   <li>资产周转(投放率) =(在租+已转让)/(总 − 报废),按 品类 下钻</li>
 *   <li>回款率 = Σ已收 / Σ应收(正常单),供 PDCA 指标复用</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BiService {

    private final BiQueryMapper mapper;

    private static final String ST_SCRAP = "报废";
    private static final String ST_RENTED = "在租";
    private static final String ST_TRANSFERRED = "已转让";

    // ============================== 在租率 ==============================

    /** 在租率矩阵。dimension: category(品类·默认) / supplier(供应商) */
    public BiDtos.MatrixResp occupancy(String dimension) {
        String dim = normDim(dimension, "category", "supplier");
        List<BiQueryMapper.AssetRow> assets = mapper.assets();

        BiDtos.MatrixResp resp = new BiDtos.MatrixResp();
        resp.setMetric("occupancy_rate");
        resp.setMetricLabel("在租率");
        resp.setDimension(dim);
        resp.setUnit("%");
        resp.setSource("资产台账(yc_rent_asset):在租率 = 在租 /(总 − 报废),报废不计入分母;只读聚合不重算");

        long[] tot = {0, 0}; // [rented, denom]
        Map<String, long[]> byDim = new LinkedHashMap<>();
        for (BiQueryMapper.AssetRow a : assets) {
            if (ST_SCRAP.equals(a.getStatus())) {
                continue; // 报废不计入分母
            }
            String key = "supplier".equals(dim) ? blank(a.getSupplierName(), "未指定供应商") : blank(a.getCategory(), "未分类");
            long[] cell = byDim.computeIfAbsent(key, k -> new long[]{0, 0});
            boolean rented = ST_RENTED.equals(a.getStatus());
            cell[1]++;
            tot[1]++;
            if (rented) {
                cell[0]++;
                tot[0]++;
            }
        }
        resp.setOverall(rate(tot[0], tot[1]));
        resp.setOverallDesc(tot[0] + " 台在租 / " + tot[1] + " 台可投放(不含报废)");
        for (Map.Entry<String, long[]> e : byDim.entrySet()) {
            BiDtos.MatrixRow row = new BiDtos.MatrixRow(e.getKey());
            row.setNumerator(BigDecimal.valueOf(e.getValue()[0]));
            row.setDenominator(BigDecimal.valueOf(e.getValue()[1]));
            row.setValue(rate(e.getValue()[0], e.getValue()[1]));
            resp.getRows().add(row);
        }
        return resp;
    }

    /** 在租率标量(PDCA 复用) */
    public BigDecimal occupancyRate() {
        return occupancy("category").getOverall();
    }

    // ============================== 加权回报 ==============================

    /** 加权回报矩阵。dimension: customer(客户·默认) / nature(性质) */
    public BiDtos.MatrixResp weightedReturn(String dimension) {
        String dim = normDim(dimension, "customer", "nature");
        List<BiQueryMapper.ContractRow> contracts = mapper.contracts();

        BiDtos.MatrixResp resp = new BiDtos.MatrixResp();
        resp.setMetric("weighted_return");
        resp.setMetricLabel("加权回报");
        resp.setDimension(dim);
        resp.setUnit("%");
        resp.setSource("合同(yc_rent_contract):加权回报 = Σ(目标IRR×月租)/Σ月租,仅生效合同;只读聚合不重算");

        BigDecimal wsum = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        Map<String, BigDecimal[]> byDim = new LinkedHashMap<>(); // [wsum, weight]
        for (BiQueryMapper.ContractRow c : contracts) {
            if (!"生效".equals(c.getStatus())) {
                continue;
            }
            BigDecimal irr = nz(c.getTargetIrr());
            BigDecimal w = nz(c.getMonthRent());
            if (w.signum() <= 0) {
                continue;
            }
            String key = "nature".equals(dim) ? blank(c.getNature(), "未标性质") : blank(c.getCustomerName(), "未知客户");
            BigDecimal[] cell = byDim.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            cell[0] = cell[0].add(irr.multiply(w));
            cell[1] = cell[1].add(w);
            wsum = wsum.add(irr.multiply(w));
            weight = weight.add(w);
        }
        resp.setOverall(pct(weightedAvg(wsum, weight)));
        resp.setOverallDesc("按月租加权 · 权重合计月租 " + weight.stripTrailingZeros().toPlainString() + " 元");
        for (Map.Entry<String, BigDecimal[]> e : byDim.entrySet()) {
            BiDtos.MatrixRow row = new BiDtos.MatrixRow(e.getKey());
            row.setValue(pct(weightedAvg(e.getValue()[0], e.getValue()[1])));
            row.setWeight(e.getValue()[1]);
            resp.getRows().add(row);
        }
        return resp;
    }

    /** 加权回报标量比率(PDCA 复用·0-1) */
    public BigDecimal weightedReturnRate() {
        List<BiQueryMapper.ContractRow> contracts = mapper.contracts();
        BigDecimal wsum = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        for (BiQueryMapper.ContractRow c : contracts) {
            if (!"生效".equals(c.getStatus())) {
                continue;
            }
            BigDecimal w = nz(c.getMonthRent());
            if (w.signum() <= 0) {
                continue;
            }
            wsum = wsum.add(nz(c.getTargetIrr()).multiply(w));
            weight = weight.add(w);
        }
        return weightedAvg(wsum, weight);
    }

    // ============================== 应收账龄 ==============================

    /** 应收账龄分桶 + 按客户下钻 */
    public BiDtos.AgingResp receivableAging() {
        List<BiQueryMapper.BillRow> bills = mapper.bills();
        LocalDate today = LocalDate.now();

        BiDtos.AgingResp resp = new BiDtos.AgingResp();
        resp.setDimension("customer");
        resp.setSource("收租单(yc_rent_rent_bill):未回款(应收−已收>0 的正常单)按到期日账龄分桶;只读聚合不重算");
        String[] labels = {"0-30", "31-60", "61-90", "90+"};
        for (String l : labels) {
            resp.getBuckets().add(new BiDtos.AgingBucket(l));
        }
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byCust = new LinkedHashMap<>();
        for (BiQueryMapper.BillRow b : bills) {
            if (!"正常".equals(b.getBillKind())) {
                continue; // 只看正常应收(红冲/退款/罚息不进账龄)
            }
            BigDecimal outstanding = nz(b.getAmount()).subtract(nz(b.getReceivedAmount()));
            if (outstanding.signum() <= 0 || b.getDueDate() == null) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(b.getDueDate(), today);
            int idx = days <= 30 ? 0 : days <= 60 ? 1 : days <= 90 ? 2 : 3;
            BiDtos.AgingBucket bk = resp.getBuckets().get(idx);
            bk.setAmount(bk.getAmount().add(outstanding));
            bk.setCount(bk.getCount() + 1);
            total = total.add(outstanding);
            String cust = blank(b.getCustomerName(), "未知客户");
            byCust.merge(cust, outstanding, BigDecimal::add);
        }
        resp.setTotalOverdue(total);
        for (Map.Entry<String, BigDecimal> e : byCust.entrySet()) {
            BiDtos.MatrixRow row = new BiDtos.MatrixRow(e.getKey());
            row.setValue(e.getValue());
            resp.getRows().add(row);
        }
        return resp;
    }

    /** 加权平均账龄天数(PDCA 复用) */
    public BigDecimal receivableAgingDays() {
        List<BiQueryMapper.BillRow> bills = mapper.bills();
        LocalDate today = LocalDate.now();
        BigDecimal weightedDays = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        for (BiQueryMapper.BillRow b : bills) {
            if (!"正常".equals(b.getBillKind())) {
                continue;
            }
            BigDecimal outstanding = nz(b.getAmount()).subtract(nz(b.getReceivedAmount()));
            if (outstanding.signum() <= 0 || b.getDueDate() == null) {
                continue;
            }
            long days = Math.max(0, ChronoUnit.DAYS.between(b.getDueDate(), today));
            weightedDays = weightedDays.add(outstanding.multiply(BigDecimal.valueOf(days)));
            weight = weight.add(outstanding);
        }
        return weight.signum() == 0 ? BigDecimal.ZERO
                : weightedDays.divide(weight, 2, RoundingMode.HALF_UP);
    }

    // ============================== 资产周转 ==============================

    /** 资产周转(投放率)矩阵。dimension: category(品类·默认) */
    public BiDtos.MatrixResp assetTurnover(String dimension) {
        String dim = normDim(dimension, "category", "category");
        List<BiQueryMapper.AssetRow> assets = mapper.assets();

        BiDtos.MatrixResp resp = new BiDtos.MatrixResp();
        resp.setMetric("asset_turnover");
        resp.setMetricLabel("资产周转(投放率)");
        resp.setDimension(dim);
        resp.setUnit("%");
        resp.setSource("资产台账(yc_rent_asset):投放率 =(在租+已转让)/(总 − 报废),衡量家底动起来的比例;只读聚合不重算");

        long[] tot = {0, 0};
        Map<String, long[]> byDim = new LinkedHashMap<>();
        for (BiQueryMapper.AssetRow a : assets) {
            if (ST_SCRAP.equals(a.getStatus())) {
                continue;
            }
            String key = blank(a.getCategory(), "未分类");
            long[] cell = byDim.computeIfAbsent(key, k -> new long[]{0, 0});
            boolean deployed = ST_RENTED.equals(a.getStatus()) || ST_TRANSFERRED.equals(a.getStatus());
            cell[1]++;
            tot[1]++;
            if (deployed) {
                cell[0]++;
                tot[0]++;
            }
        }
        resp.setOverall(rate(tot[0], tot[1]));
        resp.setOverallDesc(tot[0] + " 台已投放(在租+转让) / " + tot[1] + " 台家底(不含报废)");
        for (Map.Entry<String, long[]> e : byDim.entrySet()) {
            BiDtos.MatrixRow row = new BiDtos.MatrixRow(e.getKey());
            row.setNumerator(BigDecimal.valueOf(e.getValue()[0]));
            row.setDenominator(BigDecimal.valueOf(e.getValue()[1]));
            row.setValue(rate(e.getValue()[0], e.getValue()[1]));
            resp.getRows().add(row);
        }
        return resp;
    }

    public BigDecimal assetTurnoverRate() {
        return assetTurnover("category").getOverall().movePointLeft(2);
    }

    // ============================== 回款率(PDCA 复用) ==============================

    public BigDecimal collectRate() {
        List<BiQueryMapper.BillRow> bills = mapper.bills();
        BigDecimal recv = BigDecimal.ZERO;
        BigDecimal due = BigDecimal.ZERO;
        for (BiQueryMapper.BillRow b : bills) {
            if (!"正常".equals(b.getBillKind())) {
                continue;
            }
            due = due.add(nz(b.getAmount()));
            recv = recv.add(nz(b.getReceivedAmount()));
        }
        return due.signum() == 0 ? BigDecimal.ZERO : recv.divide(due, 8, RoundingMode.HALF_UP);
    }

    // ============================== 趋势 ==============================

    /** 近 N 月趋势。metric: collection(回款额·默认) / receivable(当月应收) */
    public BiDtos.TrendResp trend(String metric, Integer months) {
        String m = (metric == null || metric.isEmpty()) ? "collection" : metric;
        int n = (months == null || months <= 0 || months > 24) ? 6 : months;
        List<BiQueryMapper.BillRow> bills = mapper.bills();

        BiDtos.TrendResp resp = new BiDtos.TrendResp();
        resp.setMetric(m);
        resp.setUnit("元");

        // 初始化近 N 月桶(含 0 月)
        Map<String, BigDecimal> buckets = new LinkedHashMap<>();
        YearMonth cur = YearMonth.now();
        for (int i = n - 1; i >= 0; i--) {
            buckets.put(cur.minusMonths(i).toString(), BigDecimal.ZERO);
        }

        boolean isCollection = !"receivable".equals(m);
        resp.setMetricLabel(isCollection ? "回款额趋势" : "应收额趋势");
        resp.setSource("收租单(yc_rent_rent_bill):" + (isCollection
                ? "按核销月(matched_at)汇总已收金额" : "按账期(account_period)汇总应收金额") + ";只读聚合不重算");
        for (BiQueryMapper.BillRow b : bills) {
            if (!"正常".equals(b.getBillKind())) {
                continue;
            }
            String period;
            BigDecimal val;
            if (isCollection) {
                if (b.getMatchedAt() == null) {
                    continue;
                }
                period = YearMonth.from(b.getMatchedAt()).toString();
                val = nz(b.getReceivedAmount());
            } else {
                period = b.getAccountPeriod();
                val = nz(b.getAmount());
            }
            if (period != null && buckets.containsKey(period)) {
                buckets.merge(period, val, BigDecimal::add);
            }
        }
        List<BiDtos.TrendPoint> pts = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : buckets.entrySet()) {
            pts.add(new BiDtos.TrendPoint(e.getKey(), e.getValue()));
        }
        resp.setPoints(pts);
        return resp;
    }

    // ============================== 工具 ==============================

    private String normDim(String dim, String def, String allowed) {
        if (dim == null || dim.isEmpty()) {
            return def;
        }
        if (!dim.equals(def) && !dim.equals(allowed)) {
            throw new BizException(400, "不支持的下钻维度: " + dim);
        }
        return dim;
    }

    private static BigDecimal rate(long num, long den) {
        return den == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(num).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(den), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal weightedAvg(BigDecimal wsum, BigDecimal weight) {
        return weight.signum() == 0 ? BigDecimal.ZERO : wsum.divide(weight, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blank(String v, String def) {
        return v == null || v.trim().isEmpty() ? def : v;
    }
}
