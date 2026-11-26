package top.aole.rent.modules.project.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租赁项目主表（一单到底唯一主轴 · V131）。
 * 把报价、租赁合同、采购合同、工程套装资产、现场过磅安装验收、收租开票、质保与结项串在同一个项目下。
 */
@Data
@TableName("yc_rent_project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectNo;
    private String name;
    /** 工程套装 / 单机设备 */
    private String assetMode;

    private Long customerId;
    private Long supplierId;
    private Long rentContractId;
    private Long purchaseContractId;
    private Long primaryAssetId;

    /** 报价测算 / 合同落定 / 采购排产 / 交付验收 / 收租运营 / 质保维保 / 已结项 */
    private String stage;

    private Integer palletSlots;
    private BigDecimal plannedTons;
    private BigDecimal actualWeighTons;
    private String warehouseZone;
    private String deliveryAddress;
    private String deliveryContact;

    private LocalDate productionStartDate;
    private LocalDate shippedDate;
    private LocalDate installDoneDate;
    private LocalDate loadTestDate;
    private LocalDate acceptanceDate;
    private LocalDate auditPassDate;
    private Integer warrantyYears;
    private LocalDate warrantyEndDate;

    private BigDecimal quotePrincipal;
    private BigDecimal quoteAnnualRate;
    private Integer quoteTermMonths;
    private BigDecimal quoteMonthRent;
    private BigDecimal quoteBuyoutPrice;

    private String remark;
    private Long ownerUserId;
    private String createByName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
