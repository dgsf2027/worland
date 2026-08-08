package top.aole.rent.modules.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 客户主表(CRM · M1-03/04/05)。全生命周期 + 信用画像输入 + 准入决策 + 行级隔离。
 *
 * <p>评级 rating/加权信用分/集中度 为<b>派生</b>,不落列,即时算(§4.17)。
 * credit_limit/deposit_months/target_irr 由<b>风控准入接口</b>唯一写入(单一写手)。
 */
@Data
@TableName("yc_rent_customer")
public class Customer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String contact;

    private String phone;

    private String industry;

    /** 阶段:线索/跟进/商机/成交/在租/流失 */
    private String phase;

    /** 价值分层:战略/普通/观察 */
    private String valueTier;

    // ---- 信用画像五维(0-100 录入输入) ----
    private Integer scoreProfit;
    private Integer scoreCashflow;
    private Integer scoreStability;
    private Integer scoreHistory;
    private Integer scoreIndustry;

    // ---- 风控准入决策(@owner=准入接口) ----
    private BigDecimal creditLimit;
    private BigDecimal depositMonths;
    private BigDecimal targetIrr;
    private String admissionNote;

    /** 负责业务(user 主键);NULL=公海(P0-E 行级隔离键) */
    private Long ownerUser;

    private Long projectId;

    // ---- LTV/敞口 快照(@owner=合同/收租模块回写) ----
    private Integer contractCount;
    private BigDecimal cumulativeRent;
    private BigDecimal cumulativeProfit;
    private BigDecimal renewRate;
    private BigDecimal exposureAmount;
    private BigDecimal receivableOverdue;

    /** 下次跟进日(followup 写手同步) */
    private LocalDate nextFollowDate;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
