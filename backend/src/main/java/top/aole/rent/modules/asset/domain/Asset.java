package top.aole.rent.modules.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备主表(逐件建档 · M1-06)。一台一条,序列号唯一。
 *
 * <p><b>单一真相源(§4.24/§4.17)</b>:
 * <ul>
 *   <li>{@code status} 状态机由 {@code asset_event} 事件流驱动,禁绕过事件直写。</li>
 *   <li>{@code current_holder_customer_id}/{@code contract_id} 派生,@owner=合同签约/作废事件。</li>
 *   <li>book_value(经营口径)/residual_value(残值)/self_purchase_payback(自购回本期) 均<b>不落列</b>,即时算。</li>
 * </ul>
 */
@Data
@TableName("yc_rent_asset")
public class Asset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String serialNo;

    /** 品类:播种墙/货架/阁楼/配件 */
    private String category;

    private String model;

    /** 市场价(元);残值=市场价×品类转让率 */
    private BigDecimal marketPrice;

    /** 集采价(元·成本口径);book_value 折旧基数 */
    private BigDecimal purchasePrice;

    private Long supplierId;

    /** 状态机(asset_event 驱动):采购/投放/在租/待转让/已转让/收回待处置/报废 */
    private String status;

    private Long projectId;

    /** 月替代人工价值(元·价值定价核心输入) */
    private BigDecimal monthlyLaborValue;

    private BigDecimal replaceHeadcount;

    /** 【派生】当前承租客户(@owner=合同事件) */
    private Long currentHolderCustomerId;

    /** 【派生】当前在租合同(@owner=签约/作废事件) */
    private Long contractId;

    /** 采购入库单(M1-12 采购模块回填) */
    private Long purchaseInId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
