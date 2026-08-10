package top.aole.rent.modules.maintenance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 维保工单(M4-04)。报修/预防/巡检 → 派工 → 处理 → 回写(故障回写 asset_bom.fault_count)。
 *
 * <p><b>质保/责任方</b>:质保内(in_warranty=1)转供应商,费用不计我方(responsible_party=供应商·cost 记录但不入我方成本)。
 */
@Data
@TableName("yc_rent_maintenance")
public class Maintenance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    private Long assetId;

    /** 故障配件(回写 fault_count) */
    private Long bomId;

    /** 类型:报修/预防/巡检 */
    private String type;

    /** 状态:待派工/处理中/已完成/已关闭 */
    private String status;

    private String faultDesc;

    /** 是否质保内:质保内转供应商·费用不计我方 */
    private Integer inWarranty;

    /** 责任方:我方/供应商 */
    private String responsibleParty;

    private Long supplierId;

    private BigDecimal cost;

    private Long assigneeId;

    private String handleNote;

    private LocalDateTime reportedAt;

    private LocalDateTime assignedAt;

    private LocalDateTime finishedAt;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
