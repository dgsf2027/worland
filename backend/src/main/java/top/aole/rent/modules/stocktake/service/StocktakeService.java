package top.aole.rent.modules.stocktake.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.stocktake.domain.StockDiff;
import top.aole.rent.modules.stocktake.domain.Stocktake;
import top.aole.rent.modules.stocktake.dto.StocktakeDtos;
import top.aole.rent.modules.stocktake.mapper.StockDiffMapper;
import top.aole.rent.modules.stocktake.mapper.StocktakeMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点服务(M4-05)。扫码盘点账面带出只录差异 → 账实差异生成盘盈亏调整单闭合。
 *
 * <p><b>单一真相源(§4.24)</b>:盘点差异<b>不直改台账</b>,先落 {@link StockDiff} 差异单;闭合时逐差异
 * 生成盘盈亏调整单 —— 经 {@link AssetService#applyStocktakeAdjust} 走事件流对齐台账状态(留痕),
 * 差异行回填 adjust_event_id 闭合。盘点单本身不写 asset.status。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StocktakeService {

    private final StocktakeMapper stocktakeMapper;
    private final StockDiffMapper diffMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final AuditLogService auditLogService;

    /** 台账"应盘"状态:排除终态(已转让/报废) —— 这些不参与实物盘点。 */
    private boolean countable(Asset a) {
        return !"已转让".equals(a.getStatus()) && !"报废".equals(a.getStatus());
    }

    // ============ 建单(账面带出) ============

    @Transactional
    public Long create(StocktakeDtos.CreateRequest req) {
        String scope = req != null && req.getScope() != null && !req.getScope().trim().isEmpty()
                ? req.getScope().trim() : "全量";
        int bookCount = 0;
        List<Asset> all = assetMapper.selectList(new LambdaQueryWrapper<Asset>());
        for (Asset a : all) {
            if (!countable(a)) {
                continue;
            }
            if ("全量".equals(scope) || scope.equals(a.getCategory())) {
                bookCount++;
            }
        }
        Stocktake st = new Stocktake();
        st.setNo(genNo());
        st.setScope(scope);
        st.setStatus("进行中");
        st.setBookCount(bookCount);
        st.setScannedCount(0);
        st.setDiffCount(0);
        st.setBizTime(LocalDateTime.now());
        st.setOperatorId(currentUserId());
        st.setRemark(req != null ? req.getRemark() : null);
        stocktakeMapper.insert(st);
        log.info("[盘点] 建单 {} 范围{} 账面{}台", st.getNo(), scope, bookCount);
        return st.getId();
    }

    // ============ 扫码盘点(只录差异) ============

    /** 提交扫码差异:与账面状态比对,不一致的落差异行(幂等:同盘点+资产已录则更新)。 */
    @Transactional
    public StocktakeDtos.StocktakeDetail scan(Long id, StocktakeDtos.ScanRequest req) {
        Stocktake st = load(id);
        if (!"进行中".equals(st.getStatus())) {
            throw new BizException(400, "盘点单已" + st.getStatus() + ",不可继续录差异");
        }
        if (req == null || req.getDiffs() == null) {
            throw new BizException(400, "扫码差异 diffs 不能为空");
        }
        int scanned = st.getScannedCount() == null ? 0 : st.getScannedCount();
        for (StocktakeDtos.DiffInput di : req.getDiffs()) {
            if (di.getAssetId() == null) {
                continue;
            }
            Asset a = assetMapper.selectById(di.getAssetId());
            if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
                throw new BizException(404, "设备不存在: id=" + di.getAssetId());
            }
            scanned++;
            String bookStatus = a.getStatus();
            String actual = di.getActualStatus() == null ? "" : di.getActualStatus().trim();
            String diffType = di.getDiffType() != null && !di.getDiffType().trim().isEmpty()
                    ? di.getDiffType().trim() : inferDiffType(bookStatus, actual);
            if (diffType == null) {
                continue;   // 账实一致,不录
            }
            // 幂等:同盘点+资产已有差异行则更新
            StockDiff exist = diffMapper.selectOne(new LambdaQueryWrapper<StockDiff>()
                    .eq(StockDiff::getStocktakeId, id)
                    .eq(StockDiff::getAssetId, di.getAssetId()).last("limit 1"));
            if (exist != null) {
                exist.setActualStatus(actual);
                exist.setDiffType(diffType);
                exist.setRemark(di.getRemark());
                diffMapper.updateById(exist);
            } else {
                StockDiff d = new StockDiff();
                d.setStocktakeId(id);
                d.setAssetId(di.getAssetId());
                d.setBookStatus(bookStatus);
                d.setActualStatus(actual);
                d.setDiffType(diffType);
                d.setAdjusted(0);
                d.setRemark(di.getRemark());
                diffMapper.insert(d);
            }
        }
        long diffCount = diffMapper.selectCount(new LambdaQueryWrapper<StockDiff>().eq(StockDiff::getStocktakeId, id));
        st.setScannedCount(scanned);
        st.setDiffCount((int) diffCount);
        stocktakeMapper.updateById(st);
        return detail(id);
    }

    /** 差异类型推断:实盘丢失/盘亏→盘亏;账面终态而实物在→盘盈;状态不一致→状态不符;一致→null(不录)。 */
    private String inferDiffType(String bookStatus, String actual) {
        if ("丢失".equals(actual) || "盘亏".equals(actual)) {
            return "盘亏";
        }
        if (actual.isEmpty() || actual.equals(bookStatus)) {
            return null;
        }
        if (("已转让".equals(bookStatus) || "报废".equals(bookStatus))
                && !"已转让".equals(actual) && !"报废".equals(actual)) {
            return "盘盈";
        }
        return "状态不符";
    }

    // ============ 闭合(逐差异生成盘盈亏调整单) ============

    @Transactional
    public StocktakeDtos.CloseResult close(Long id) {
        Stocktake st = load(id);
        if ("已闭合".equals(st.getStatus())) {
            throw new BizException(400, "盘点单已闭合(幂等拒)");
        }
        List<StockDiff> diffs = diffMapper.selectList(new LambdaQueryWrapper<StockDiff>()
                .eq(StockDiff::getStocktakeId, id).orderByAsc(StockDiff::getId));
        List<String> impact = new ArrayList<>();
        int adjusted = 0, profit = 0, loss = 0, mismatch = 0;
        for (StockDiff d : diffs) {
            if (Integer.valueOf(1).equals(d.getAdjusted())) {
                continue;
            }
            // 盘亏 → 报废核销;盘盈/状态不符 → 对齐实盘状态
            String target = "盘亏".equals(d.getDiffType()) ? "报废" : d.getActualStatus();
            String remark = "盘点调整单·盘点单" + st.getNo() + "·" + d.getDiffType()
                    + "(账面" + d.getBookStatus() + "→实盘" + d.getActualStatus() + ")";
            Long eventId = assetService.applyStocktakeAdjust(d.getAssetId(), target, id, remark);
            d.setAdjusted(1);
            d.setAdjustEventId(eventId);
            diffMapper.updateById(d);
            adjusted++;
            if ("盘盈".equals(d.getDiffType())) profit++;
            else if ("盘亏".equals(d.getDiffType())) loss++;
            else mismatch++;
            impact.add(d.getDiffType() + ":资产#" + d.getAssetId() + " 账面" + d.getBookStatus()
                    + "→" + (target == null ? d.getActualStatus() : target) + " · 调整event#" + eventId);
        }
        st.setStatus("已闭合");
        st.setClosedAt(LocalDateTime.now());
        stocktakeMapper.updateById(st);
        auditLogService.record("盘点闭合", "stocktake", id, AuditLogService.EXECUTED,
                "盘点单 " + st.getNo() + " 闭合·调整" + adjusted + "台(盘盈" + profit + "/盘亏" + loss + "/状态不符" + mismatch + ")");
        log.info("[盘点] 闭合 {} 调整{}台(盘盈{}/盘亏{}/状态不符{})", st.getNo(), adjusted, profit, loss, mismatch);

        StocktakeDtos.CloseResult r = new StocktakeDtos.CloseResult();
        r.setStocktakeId(id);
        r.setStatus("已闭合");
        r.setAdjusted(adjusted);
        r.setProfitCount(profit);
        r.setLossCount(loss);
        r.setMismatchCount(mismatch);
        r.setImpact(impact);
        return r;
    }

    // ============ 列表 / 详情 ============

    public PageResult<StocktakeDtos.StocktakeItem> list(String status, int page, int size) {
        LambdaQueryWrapper<Stocktake> qw = new LambdaQueryWrapper<Stocktake>()
                .eq(status != null && !status.isEmpty(), Stocktake::getStatus, status)
                .orderByDesc(Stocktake::getId);
        List<Stocktake> all = stocktakeMapper.selectList(qw);
        List<StocktakeDtos.StocktakeItem> items = new ArrayList<>();
        for (Stocktake s : all) {
            items.add(toItem(s));
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<StocktakeDtos.StocktakeItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    public StocktakeDtos.StocktakeDetail detail(Long id) {
        Stocktake st = load(id);
        StocktakeDtos.StocktakeDetail d = new StocktakeDtos.StocktakeDetail();
        d.setStocktake(toItem(st));
        List<StockDiff> diffs = diffMapper.selectList(new LambdaQueryWrapper<StockDiff>()
                .eq(StockDiff::getStocktakeId, id).orderByAsc(StockDiff::getId));
        List<StocktakeDtos.DiffItem> items = new ArrayList<>();
        for (StockDiff sd : diffs) {
            Asset a = assetMapper.selectById(sd.getAssetId());
            StocktakeDtos.DiffItem it = new StocktakeDtos.DiffItem();
            it.setId(sd.getId());
            it.setAssetId(sd.getAssetId());
            it.setSerialNo(a != null ? a.getSerialNo() : null);
            it.setBookStatus(sd.getBookStatus());
            it.setActualStatus(sd.getActualStatus());
            it.setDiffType(sd.getDiffType());
            it.setAdjusted(Integer.valueOf(1).equals(sd.getAdjusted()));
            it.setAdjustEventId(sd.getAdjustEventId());
            it.setRemark(sd.getRemark());
            items.add(it);
        }
        d.setDiffs(items);
        return d;
    }

    // ============ 工具 ============

    private StocktakeDtos.StocktakeItem toItem(Stocktake s) {
        StocktakeDtos.StocktakeItem it = new StocktakeDtos.StocktakeItem();
        it.setId(s.getId());
        it.setNo(s.getNo());
        it.setScope(s.getScope());
        it.setStatus(s.getStatus());
        it.setBookCount(s.getBookCount());
        it.setScannedCount(s.getScannedCount());
        it.setDiffCount(s.getDiffCount());
        it.setBizTime(s.getBizTime());
        it.setClosedAt(s.getClosedAt());
        it.setRemark(s.getRemark());
        return it;
    }

    private String genNo() {
        String base = "ST-" + (System.currentTimeMillis() % 10000000);
        String no = base;
        int n = 1;
        while (stocktakeMapper.selectCount(new LambdaQueryWrapper<Stocktake>().eq(Stocktake::getNo, no)) > 0) {
            no = base + "-" + (++n);
        }
        return no;
    }

    private Stocktake load(Long id) {
        Stocktake s = stocktakeMapper.selectById(id);
        if (s == null || Integer.valueOf(1).equals(s.getIsDeleted())) {
            throw new BizException(404, "盘点单不存在: id=" + id);
        }
        return s;
    }

    private Long currentUserId() {
        return UserContext.get() != null ? UserContext.get().getUserId() : null;
    }
}
