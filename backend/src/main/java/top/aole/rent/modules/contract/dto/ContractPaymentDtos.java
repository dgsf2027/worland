package top.aole.rent.modules.contract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 合同付款方式视图(V118)。左边是这份合同的多段付款方式,右边是它覆盖的台账设备与各段合计要付多少。
 *
 * <p>金额属成本口径,对 GP/LP 打码({@code sensitiveMasked=true} 时各金额字段为 null,比例与段数仍可见)。
 */
public final class ContractPaymentDtos {

    private ContractPaymentDtos() {
    }

    @Data
    public static class PaymentTermView {
        private Long contractId;
        private String contractNo;
        private String customerName;
        /** 付款方式摘要:首付30%(下单) / 验收60%(入库) / 尾款10%(入库+90天) */
        private String describe;
        private List<TermRow> terms = new ArrayList<>();
        /** 本合同覆盖的台账设备 */
        private List<AssetRow> assets = new ArrayList<>();
        /** 设备合同价合计(敏感) */
        private BigDecimal equipmentTotal;
        /** 未设置付款方式时的默认模板(首付/验收/尾款) */
        private List<top.aole.rent.modules.asset.dto.PaymentTermDtos.TermInput> defaultTemplate = new ArrayList<>();
        private Boolean sensitiveMasked;
        /** 说明(如尚未挂设备、存在已付阶段锁定) */
        private String note;
    }

    @Data
    public static class TermRow {
        private Long id;
        private Integer seq;
        private String stageName;
        private BigDecimal ratio;
        private String triggerPoint;
        private Integer dueDays;
        /** 该段在本合同下的预计付款合计 = Σ(各设备合同价 × 本段比例)(敏感) */
        private BigDecimal expectedTotal;
        /** 该段已生成应付合计 / 其中已付 / 其中待付(敏感) */
        private BigDecimal payableTotal;
        private BigDecimal paidTotal;
        private BigDecimal pendingTotal;
        /** 该段是否已有付款(已付则比例不可改、段不可删) */
        private Boolean locked;
    }

    @Data
    public static class AssetRow {
        private Long assetId;
        /** 品类 · 型号(型号为空退回序列号) */
        private String label;
        private String serialNo;
        private String status;
        /** 合同价(敏感) */
        private BigDecimal purchasePrice;
        /** 本设备待付合计(敏感) */
        private BigDecimal pendingTotal;
    }
}
