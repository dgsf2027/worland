package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 设备状态流转入参(走事件流 + 状态机校验)。
 */
@Data
public class StatusChangeRequest {

    /** 目标状态:采购/投放/在租/待转让/已转让/收回待处置/报废 */
    @NotBlank(message = "目标状态必填")
    private String targetStatus;

    private String remark;
    private String refDocType;
    private Long refDocId;
}
