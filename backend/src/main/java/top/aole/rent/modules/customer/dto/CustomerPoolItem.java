package top.aole.rent.modules.customer.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 客户池列表行。评级即时算;敏感财务字段按角色投影;下次跟进到期状态亮灯。
 */
@Data
public class CustomerPoolItem {

    private Long id;
    private String name;
    private String phase;
    private String ownerName;
    private Long ownerUser;
    private String valueTier;

    /** 评级 A/B/C(即时算);无评分=null → 前端显"—" */
    private String rating;
    /** 是否潜客预估评级(未成交阶段) */
    private Boolean ratingPredicted;

    /** 在租敞口/商机额(元);敏感,按角色投影 */
    private BigDecimal exposureOrOppAmount;
    /** 逾期应收(元);敏感,按角色投影 */
    private BigDecimal receivableOverdue;

    private LocalDate nextFollowDate;
    /** 跟进到期状态:逾期/明天/正常/无 */
    private String followStatus;

    /** 是否公海(owner_user 为空) */
    private Boolean inPublicPool;
    /** 敏感字段是否已打码 */
    private Boolean sensitiveMasked;
}
