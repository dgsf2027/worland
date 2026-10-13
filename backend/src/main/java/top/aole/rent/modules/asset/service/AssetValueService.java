package top.aole.rent.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetDepreciationLine;
import top.aole.rent.modules.asset.dto.AssetValueDtos;
import top.aole.rent.modules.asset.mapper.AssetDepreciationLineMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 总资产(10-07 纪要):设备台账在账设备的原值(集采价)与净值(扣经营口径累计折旧)。
 * 已转让/报废不计;缺集采价的设备不混进合计,单独计数。仓库实物(资产管理)无单价,不在此统计。
 */
@Service
@RequiredArgsConstructor
public class AssetValueService {

    /** 计入总资产的状态,也是「按状态」分组的展示顺序 */
    public static final List<String> COUNTED = Arrays.asList("采购", "投放", "在租", "待转让", "收回待处置");
    private static final String BOOK = "ops";

    private final AssetMapper assetMapper;
    private final AssetDepreciationLineMapper lineMapper;

    public AssetValueDtos.TotalValue totalValue() {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>().in(Asset::getStatus, COUNTED));
        List<AssetDepreciationLine> lines = assets.isEmpty() ? Collections.emptyList()
                : lineMapper.selectList(new LambdaQueryWrapper<AssetDepreciationLine>()
                .eq(AssetDepreciationLine::getBook, BOOK)
                .in(AssetDepreciationLine::getAssetId, assets.stream().map(Asset::getId).collect(Collectors.toList())));
        return compute(assets, lines);
    }

    public static AssetValueDtos.TotalValue compute(List<Asset> assets, List<AssetDepreciationLine> lines) {
        Map<Long, BigDecimal> accum = new HashMap<>();
        String through = null;
        for (AssetDepreciationLine l : lines) {
            accum.merge(l.getAssetId(), nz(l.getDeprAmount()), BigDecimal::add);
            if (l.getPeriod() != null && (through == null || l.getPeriod().compareTo(through) > 0)) {
                through = l.getPeriod();
            }
        }
        AssetValueDtos.TotalValue t = new AssetValueDtos.TotalValue();
        t.setDepreciatedThrough(through);
        Map<String, AssetValueDtos.Group> byCat = new LinkedHashMap<>();
        Map<String, AssetValueDtos.Group> byStatus = new LinkedHashMap<>();
        for (String s : COUNTED) {
            byStatus.put(s, group(s));
        }
        for (Asset a : assets) {
            if (a.getPurchasePrice() == null) {
                t.setMissingPriceCount(t.getMissingPriceCount() + 1);
                continue;
            }
            BigDecimal orig = a.getPurchasePrice();
            BigDecimal dep = accum.getOrDefault(a.getId(), BigDecimal.ZERO);
            t.setAssetCount(t.getAssetCount() + 1);
            t.setOriginalTotal(t.getOriginalTotal().add(orig));
            t.setAccumulatedDepreciation(t.getAccumulatedDepreciation().add(dep));
            String cat = a.getCategory() == null || a.getCategory().isEmpty() ? "未分类" : a.getCategory();
            add(byCat.computeIfAbsent(cat, AssetValueService::group), orig, dep);
            add(byStatus.computeIfAbsent(a.getStatus(), AssetValueService::group), orig, dep);
        }
        t.setOriginalTotal(scale(t.getOriginalTotal()));
        t.setAccumulatedDepreciation(scale(t.getAccumulatedDepreciation()));
        t.setNetTotal(scale(t.getOriginalTotal().subtract(t.getAccumulatedDepreciation())));
        t.setByCategory(finish(byCat.values()));
        t.setByStatus(finish(byStatus.values()).stream().filter(g -> g.getCount() > 0).collect(Collectors.toList()));
        return t;
    }

    public byte[] exportExcel() {
        AssetValueDtos.TotalValue t = totalValue();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            sheet(wb, "按品类", "品类", t.getByCategory(), t);
            sheet(wb, "按状态", "状态", t.getByStatus(), t);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "总资产导出失败:" + e.getMessage());
        }
    }

    private static void sheet(Workbook wb, String title, String firstHead, List<AssetValueDtos.Group> groups,
                              AssetValueDtos.TotalValue t) {
        Sheet sh = wb.createSheet(title);
        String[] heads = {firstHead, "台数", "原值(不含折旧)", "累计折旧", "净值(含折旧)"};
        Row hr = sh.createRow(0);
        for (int i = 0; i < heads.length; i++) {
            hr.createCell(i).setCellValue(heads[i]);
            // 固定列宽(不用 autoSizeColumn:服务器镜像缺字体会抛异常,与客户导出同口径)
            sh.setColumnWidth(i, (i == 0 ? 14 : 18) * 256);
        }
        int r = 1;
        for (AssetValueDtos.Group g : groups) {
            r = row(sh, r, g.getName(), g.getCount(), g.getOriginalTotal(), g.getAccumulatedDepreciation(), g.getNetTotal());
        }
        r = row(sh, r, "合计", t.getAssetCount(), t.getOriginalTotal(), t.getAccumulatedDepreciation(), t.getNetTotal());
        Row note = sh.createRow(r + 1);
        note.createCell(0).setCellValue("折旧计提至 " + (t.getDepreciatedThrough() == null ? "(尚未计提)" : t.getDepreciatedThrough())
                + ";缺集采价未计入 " + t.getMissingPriceCount() + " 台;已转让/报废不计");
    }

    private static int row(Sheet sh, int r, String name, int count, BigDecimal orig, BigDecimal dep, BigDecimal net) {
        Row row = sh.createRow(r);
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(count);
        row.createCell(2).setCellValue(orig.doubleValue());
        row.createCell(3).setCellValue(dep.doubleValue());
        row.createCell(4).setCellValue(net.doubleValue());
        return r + 1;
    }

    private static AssetValueDtos.Group group(String name) {
        AssetValueDtos.Group g = new AssetValueDtos.Group();
        g.setName(name);
        return g;
    }

    private static void add(AssetValueDtos.Group g, BigDecimal orig, BigDecimal dep) {
        g.setCount(g.getCount() + 1);
        g.setOriginalTotal(g.getOriginalTotal().add(orig));
        g.setAccumulatedDepreciation(g.getAccumulatedDepreciation().add(dep));
    }

    private static List<AssetValueDtos.Group> finish(Collection<AssetValueDtos.Group> gs) {
        List<AssetValueDtos.Group> out = new ArrayList<>();
        for (AssetValueDtos.Group g : gs) {
            g.setOriginalTotal(scale(g.getOriginalTotal()));
            g.setAccumulatedDepreciation(scale(g.getAccumulatedDepreciation()));
            g.setNetTotal(scale(g.getOriginalTotal().subtract(g.getAccumulatedDepreciation())));
            out.add(g);
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
