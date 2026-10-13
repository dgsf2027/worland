package top.aole.rent.modules.asset;

import org.junit.jupiter.api.Test;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetDepreciationLine;
import top.aole.rent.modules.asset.dto.AssetValueDtos;
import top.aole.rent.modules.asset.service.AssetValueService;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 总资产:含/不含折旧、缺集采价不入合计、无折旧行净值=原值、按状态固定顺序。 */
class AssetValueServiceTest {

    private static Asset asset(long id, String category, String status, String price) {
        Asset a = new Asset();
        a.setId(id);
        a.setCategory(category);
        a.setStatus(status);
        a.setPurchasePrice(price == null ? null : new BigDecimal(price));
        return a;
    }

    private static AssetDepreciationLine line(long assetId, String period, String amount) {
        AssetDepreciationLine l = new AssetDepreciationLine();
        l.setAssetId(assetId);
        l.setPeriod(period);
        l.setDeprAmount(new BigDecimal(amount));
        return l;
    }

    @Test
    void 含与不含折旧() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Arrays.asList(asset(1, "播种墙", "在租", "180000"), asset(2, "货架", "投放", "20000")),
                Arrays.asList(line(1, "2026-08", "4500"), line(1, "2026-09", "4500"), line(2, "2026-09", "300")));
        assertEquals(2, t.getAssetCount());
        assertEquals(new BigDecimal("200000.00"), t.getOriginalTotal());
        assertEquals(new BigDecimal("9300.00"), t.getAccumulatedDepreciation());
        assertEquals(new BigDecimal("190700.00"), t.getNetTotal());
        assertEquals("2026-09", t.getDepreciatedThrough());
        assertEquals(2, t.getByCategory().size());
        assertEquals("播种墙", t.getByCategory().get(0).getName());
        assertEquals(new BigDecimal("171000.00"), t.getByCategory().get(0).getNetTotal());
    }

    @Test
    void 缺集采价不进合计() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Arrays.asList(asset(1, "货架", "在租", null), asset(2, "货架", "在租", "1000")),
                Collections.emptyList());
        assertEquals(1, t.getAssetCount());
        assertEquals(1, t.getMissingPriceCount());
        assertEquals(new BigDecimal("1000.00"), t.getOriginalTotal());
    }

    @Test
    void 没有折旧行净值等于原值() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Collections.singletonList(asset(1, "阁楼", "采购", "5000")), Collections.emptyList());
        assertEquals(t.getOriginalTotal(), t.getNetTotal());
        assertNull(t.getDepreciatedThrough());
    }

    @Test
    void 按状态固定顺序且隐藏空组() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Arrays.asList(asset(1, "货架", "收回待处置", "1"), asset(2, "货架", "采购", "1"), asset(3, "货架", "在租", "1")),
                Collections.emptyList());
        assertEquals(3, t.getByStatus().size());
        assertEquals("采购", t.getByStatus().get(0).getName());
        assertEquals("在租", t.getByStatus().get(1).getName());
        assertEquals("收回待处置", t.getByStatus().get(2).getName());
    }
}
