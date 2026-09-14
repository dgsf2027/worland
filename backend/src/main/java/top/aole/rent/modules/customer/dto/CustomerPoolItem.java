package top.aole.rent.modules.customer.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 客户池列表行。评级即时算;敏感财务字段按角色投影;下次跟进到期状态亮灯。
 */
@Data
public class CustomerPoolItem {

    private Long id;
    /** 公司名称 */
    private String name;
    private String legalPerson;
    /** 注册资本(元) */
    private BigDecimal registeredCapital;
    private List<String> businessScope;
    private String contact;
    private String phone;
    private String industry;

    /** 可在租合同数(状态=生效) */
    private Integer activeContractCount;
    /** 合同总数(不含已作废) */
    private Integer contractTotal;
    /** 在租设备台数(生效合同挂的设备) */
    private Integer activeAssetCount;

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
