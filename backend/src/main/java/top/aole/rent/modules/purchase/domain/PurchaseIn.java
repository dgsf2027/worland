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

    /** 绑定租赁合同(允许先采后签,可空) */
    private Long contractId;

    /** 意向承接客户(先采后签时记录) */
    private Long intendedCustomerId;

    private Long supplierId;

    /** 关联的采购合同(V125,可空):已关联的单,付款只能经采购合同页登记 */
    private Long pcId;

    /** 状态:已下单/已入库/已红冲 */
    private String status;

    private BigDecimal totalAmount;

    /** 首付比例(空=取 rule payable_stage_ratio[首付]) */
    private BigDecimal firstPayRatio;

    /** 尾款账期天数(空=取 rule payable_tail_days) */
    private Integer accountDays;

    private LocalDate orderDate;

    /** 预计入库日(未入库时按此推算「入库」阶段应付的到期日) */
    private LocalDate expectReceiveDate;

    private LocalDate receiveDate;

    /** 验收合格日 */
    private LocalDate acceptanceDate;

    /** 结算资料审核通过日 */
    private LocalDate auditPassDate;

    /** 质保年限(年) */
    private Integer warrantyYears;

    /** 质保到期日 */
    private LocalDate warrantyEndDate;

    /** 交付/安装地址 */
    private String deliveryAddress;

    /** 交付联系人及电话 */
    private String deliveryContact;

    /** 过磅重量(吨) */
    private BigDecimal weighTons;

    private Long projectId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
