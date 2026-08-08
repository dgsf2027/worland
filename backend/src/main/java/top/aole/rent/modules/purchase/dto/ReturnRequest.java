package top.aole.rent.modules.purchase.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 采购退货红冲请求(M1-13)。整单红冲:purchase 红冲 + 应付红字 + 对应 asset 报废释放。
 */
@Data
public class ReturnRequest {

    @NotBlank(message = "退货原因/裁决必填(留痕)")
    private String reason;
}
