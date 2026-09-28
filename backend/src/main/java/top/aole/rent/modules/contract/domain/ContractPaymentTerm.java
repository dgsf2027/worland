package top.aole.rent.modules.contract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合同付款方式的一段(V118)。
 *
 * <p><b>唯一真相源</b>:一份设备租赁合同定义一套多段付款方式,该合同下的台账设备与采购单全部继承。
 * 设备的预计付款金额 = 该设备合同价 × 本段比例(算出来不存);{@code yc_rent_asset_payment_term}
 * 自 V118 起停止写入,只为映射不到合同段的历史应付保留读取。
 */
@Data
@TableName("yc_rent_contract_payment_term")
public class ContractPaymentTerm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;

    /** 段序号(1 起) */
    private Integer seq;

    /** 阶段名:首付/验收/尾款…(同合同内不重复,「退款红字」为系统保留) */
    private String stageName;

    /** 付款比例(0-1),同合同各段合计=1 */
    private BigDecimal ratio;

    /** 触发时点:下单/入库 */
    private String triggerPoint;

    /** 到期天数(触发日 + N 天) */
    private Integer dueDays;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
