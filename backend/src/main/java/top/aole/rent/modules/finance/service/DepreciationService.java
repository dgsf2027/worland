package top.aole.rent.modules.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetDepreciationLine;
import top.aole.rent.modules.asset.domain.AssetEvent;
import top.aole.rent.modules.asset.mapper.AssetDepreciationLineMapper;
import top.aole.rent.modules.asset.mapper.AssetEventMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 折旧计提服务(M3-02 · ADR-004 P0-B)。月度按经营口径 ops 逐月生成折旧行 + 折旧凭证,
 * {@code asset.book_value} 由 {@link AssetDepreciationLine} 即时算(唯一写手)。
 *
 * <p><b>可折旧口径</b>:已投放/在租/待转让/收回待处置(自有租赁资产·经营口径);采购(未投放)不计提,
 * 已转让/报废(终态)停止计提。直线法:月折旧 =(集采价 − 残值)/ 折旧月数[品类];末期结平尾差。
 * <p><b>幂等</b>:unique(asset_id,book,period_no) —— 同期次已计提则跳过,cron 可重复安全触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepreciationService {

    private final AssetMapper assetMapper;
    private final AssetEventMapper assetEventMapper;
    private final AssetDepreciationLineMapper depreciationLineMapper;
    private final VoucherService voucherService;
    private final RuleConfigService rules;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String BOOK = "ops";
    /** 可折旧状态(自有在役资产·经营口径)。 */
    private static final List<String> DEPRECIABLE = Arrays.asList("投放", "在租", "待转让", "收回待处置");

    /**
     * 月度折旧计提(cron 执行体):按当月为记账期,对每台可折旧设备生成下一期折旧行 + ops 折旧凭证。
     * 单事务:全部计提同生共死(任一异常整体回滚,不留半套账)。
     */
    @Transactional
    public VoucherDtos.DepreciationRunResult runMonthlyDepreciation(LocalDate bizDate) {
        String period = bizDate.format(YM);
        VoucherDtos.DepreciationRunResult r = new VoucherDtos.DepreciationRunResult();
        r.setPeriod(period);
        List<String> details = new ArrayList<>();
        int scanned = 0, generated = 0, skipped = 0, vouchers = 0;
        BigDecimal totalDepr = BigDecimal.ZERO;

        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .in(Asset::getStatus, DEPRECIABLE)
                .orderByAsc(Asset::getId));
        for (Asset a : assets) {
            scanned++;
            // 幂等:本期(YYYY-MM)已计提则跳过
            boolean hasThisPeriod = depreciationLineMapper.selectCount(new LambdaQueryWrapper<AssetDepreciationLine>()
                    .eq(AssetDepreciationLine::getAssetId, a.getId())
                    .eq(AssetDepreciationLine::getBook, BOOK)
                    .eq(AssetDepreciationLine::getPeriod, period)) > 0;
            if (hasThisPeriod) {
                skipped++;
                continue;
            }
            if (a.getPurchasePrice() == null || a.getPurchasePrice().signum() <= 0) {
                skipped++;
                continue;
            }
            // 未投放/在租(无投放事件)不计提
            if (firstDeployTime(a.getId()) == null) {
                skipped++;
                continue;
            }
            int lifeMonths = lifeMonths(a.getCategory());
            BigDecimal residual = residualValue(a);
            BigDecimal base = residual != null ? a.getPurchasePrice().subtract(residual) : a.getPurchasePrice();
            if (base.signum() <= 0) {
                skipped++;
                continue;
            }

            List<AssetDepreciationLine> existing = depreciationLineMapper.selectList(new LambdaQueryWrapper<AssetDepreciationLine>()
                    .eq(AssetDepreciationLine::getAssetId, a.getId())
                    .eq(AssetDepreciationLine::getBook, BOOK)
                    .orderByAsc(AssetDepreciationLine::getPeriodNo));
            int prevPeriodNo = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getPeriodNo();
            if (prevPeriodNo >= lifeMonths) {
                skipped++;   // 已折旧完毕
                continue;
            }
            BigDecimal prevAccum = BigDecimal.ZERO;
            for (AssetDepreciationLine l : existing) {
                prevAccum = prevAccum.add(l.getDeprAmount());
            }
            int newPeriodNo = prevPeriodNo + 1;
            BigDecimal monthly = base.divide(BigDecimal.valueOf(lifeMonths), 2, RoundingMode.HALF_UP);
            BigDecimal thisDepr = newPeriodNo >= lifeMonths
                    ? base.subtract(prevAccum)   // 末期结平尾差
                    : monthly;
            if (thisDepr.signum() <= 0) {
                skipped++;
                continue;
            }
            BigDecimal accum = prevAccum.add(thisDepr);
            BigDecimal bookValueAfter = a.getPurchasePrice().subtract(accum).setScale(2, RoundingMode.HALF_UP);

            // ops 折旧凭证
            Long voucherId = voucherService.postDepreciation(a.getId(), a.getSerialNo(), newPeriodNo, thisDepr, bizDate);
            vouchers++;

            AssetDepreciationLine line = new AssetDepreciationLine();
            line.setAssetId(a.getId());
            line.setBook(BOOK);
            line.setPeriodNo(newPeriodNo);
            line.setPeriod(period);
            line.setDeprAmount(thisDepr);
            line.setBookValueAfter(bookValueAfter);
            line.setVoucherId(voucherId);
            line.setBizDate(bizDate);
            line.setRemark("直线折旧 第" + newPeriodNo + "/" + lifeMonths + "期");
            depreciationLineMapper.insert(line);
            generated++;
            totalDepr = totalDepr.add(thisDepr);
            details.add(a.getSerialNo() + " 第" + newPeriodNo + "期 折旧 " + thisDepr + " → 净值 " + bookValueAfter);
        }

        r.setAssetsScanned(scanned);
        r.setLinesGenerated(generated);
        r.setSkipped(skipped);
        r.setVouchersPosted(vouchers);
        r.setTotalDepr(totalDepr.setScale(2, RoundingMode.HALF_UP));
        r.setDetails(details);
        if (generated > 0) {
            log.info("[折旧计提] 期 {} 计提 {} 台 折旧总额 {} · ops凭证 {}", period, generated, totalDepr, vouchers);
        }
        return r;
    }

    /** 某设备折旧行(经营口径·按期次)。 */
    public List<VoucherDtos.DepreciationLineItem> linesOf(Long assetId) {
        List<AssetDepreciationLine> lines = depreciationLineMapper.selectList(new LambdaQueryWrapper<AssetDepreciationLine>()
                .eq(AssetDepreciationLine::getAssetId, assetId)
                .eq(AssetDepreciationLine::getBook, BOOK)
                .orderByAsc(AssetDepreciationLine::getPeriodNo));
        Asset a = assetMapper.selectById(assetId);
        String serial = a != null ? a.getSerialNo() : null;
        List<VoucherDtos.DepreciationLineItem> out = new ArrayList<>();
        for (AssetDepreciationLine l : lines) {
            VoucherDtos.DepreciationLineItem it = new VoucherDtos.DepreciationLineItem();
            it.setId(l.getId());
            it.setAssetId(l.getAssetId());
            it.setSerialNo(serial);
            it.setPeriodNo(l.getPeriodNo());
            it.setPeriod(l.getPeriod());
            it.setDeprAmount(l.getDeprAmount());
            it.setBookValueAfter(l.getBookValueAfter());
            it.setVoucherId(l.getVoucherId());
            it.setBizDate(l.getBizDate());
            out.add(it);
        }
        return out;
    }

    // ---------- 工具 ----------

    private BigDecimal residualValue(Asset a) {
        if (a.getMarketPrice() == null) {
            return null;
        }
        BigDecimal rate = safeValue("transfer_rate", a.getCategory());
        return rate != null ? a.getMarketPrice().multiply(rate).setScale(2, RoundingMode.HALF_UP) : null;
    }

    private int lifeMonths(String category) {
        BigDecimal v = safeValue("term_months", category);
        return v != null && v.intValue() > 0 ? v.intValue() : 36;
    }

    private LocalDate firstDeployTime(Long assetId) {
        List<AssetEvent> es = assetEventMapper.selectList(new LambdaQueryWrapper<AssetEvent>()
                .eq(AssetEvent::getAssetId, assetId)
                .in(AssetEvent::getEventType, Arrays.asList("投放", "在租", "再投放"))
                .orderByAsc(AssetEvent::getBizTime));
        return es.isEmpty() ? null : (es.get(0).getBizTime() != null ? es.get(0).getBizTime().toLocalDate() : null);
    }

    private BigDecimal safeValue(String ruleKey, String scopeKey) {
        try {
            return rules.getValue(ruleKey, scopeKey, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }
}
