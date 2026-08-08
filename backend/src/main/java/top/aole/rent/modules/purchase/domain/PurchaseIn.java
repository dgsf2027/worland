package top.aole.rent.modules.purchase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购入库单头(M1-12)。先签约后采购:必绑一份存续合同。
 *
 * <p><b>单一真相源(§4.24)</b>:入库逐件生成 {@code yc_rent_asset}(回填 item.asset_id / asset.purchase_in_id);
 * 应付计划走 {@code yc_rent_payable}(待付=层级②负债);退货红冲整单红字 + 设备报废释放,全程 audit/event 留痕。
 */
@Data
@TableName("yc_rent_purchase_in")
public class PurchaseIn {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    /** 绑定合同(先签约后采购·无合同不允许建单) */
    private Long contractId;

    private Long supplierId;

    /** 状态:已下单/已入库/已红冲 */
    private String status;

    private BigDecimal totalAmount;

    /** 首付比例(空=取 rule payable_stage_ratio[首付]) */
    private BigDecimal firstPayRatio;

    /** 尾款账期天数(空=取 rule payable_tail_days) */
    private Integer accountDays;

    private LocalDate orderDate;

    private LocalDate receiveDate;

    private Long projectId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
