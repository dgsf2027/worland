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
 *
 * <p>明细直接勾选该合同下「设备 · 租赁台账」里的设备:供应商名称、付款条件、预计付款金额
 * 都从设备台账带出(预计付款金额 = 设备合同价 × 各段比例),不再在采购单里手填这些字段。
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

    /** 一条明细 = 一台设备(从该合同下的设备租赁台账里勾选;不再手填序列号/品类/型号/价格) */
    @Data
    public static class Item {
        @NotNull(message = "请选择设备(设备租赁台账)")
        private Long assetId;

        private String remark;

        /** 本件付款条件;空=沿用设备上已有的条件,再空=整单付款条件 */
        @Valid
        private List<top.aole.rent.modules.asset.dto.PaymentTermDtos.TermInput> paymentTerms;
    }
}
