package top.aole.rent.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 总资产(10-07 纪要):不含折旧 = 集采价合计;含折旧 = 集采价 − 经营口径累计折旧。 */
public class AssetValueDtos {

    @Data
    public static class Group {
        private String name;
        private int count;
        private BigDecimal originalTotal = BigDecimal.ZERO;
        private BigDecimal accumulatedDepreciation = BigDecimal.ZERO;
        private BigDecimal netTotal = BigDecimal.ZERO;
    }

    @Data
    public static class TotalValue {
        /** 折旧计提到哪个月(YYYY-MM);从未计提为 null */
        private String depreciatedThrough;
        private int assetCount;
        private BigDecimal originalTotal = BigDecimal.ZERO;
        private BigDecimal accumulatedDepreciation = BigDecimal.ZERO;
        private BigDecimal netTotal = BigDecimal.ZERO;
        /** 缺集采价、未计入合计的台数 */
        private int missingPriceCount;
        private List<Group> byCategory = new ArrayList<>();
        private List<Group> byStatus = new ArrayList<>();
    }
}
