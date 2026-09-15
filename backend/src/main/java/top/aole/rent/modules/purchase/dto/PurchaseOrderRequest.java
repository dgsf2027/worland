package top.aole.rent.modules.purchase.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购下单请求(M1-12)。先签约后采购:{@code contractId} 必填且合同须存续。
 */
@Data
public class PurchaseOrderRequest {

    @NotBlank(message = "采购单号必填")
    private String no;

    @NotNull(message = "合同必填(先签约后采购)")
    private Long contractId;

    private Long supplierId;

    /** 首付比例(空=取 rule 默认 30%) */
    private BigDecimal firstPayRatio;

    /** 尾款账期天数(空=取 rule 默认 90 天) */
    private Integer accountDays;

    private LocalDate orderDate;

    private String remark;

    /** 整单默认付款条件(自定义多段,合计 100%);空=默认 首付/验收/尾款 */
    @Valid
    private List<top.aole.rent.modules.asset.dto.PaymentTermDtos.TermInput> paymentTerms;

    @NotEmpty(message = "至少 1 件采购明细")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotBlank(message = "序列号必填")
        private String serialNo;

        @NotBlank(message = "品类必填")
        private String category;

        private String model;

        private BigDecimal marketPrice;

        private BigDecimal purchasePrice;

        private Long supplierId;

        private BigDecimal monthlyLaborValue;

        private BigDecimal replaceHeadcount;

        private String remark;

        /** 本件付款条件;空=沿用整单付款条件 */
        @Valid
        private List<top.aole.rent.modules.asset.dto.PaymentTermDtos.TermInput> paymentTerms;
    }
}
