package top.aole.rent.modules.supplier.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商考察。判定合格 → 自动在供应商池建档(阶段=入库)并回填 supplierId;不合格 → 不建档。
 *
 * <p>判定为终态(不可改判);考察记录压缩包存对象存储 biz_type=supplier_inspection。
 */
@Data
@TableName("yc_rent_supplier_inspection")
public class SupplierInspection {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String companyName;

    private String legalPerson;

    /** 注册资本(元) */
    private BigDecimal registeredCapital;

    /** 业务范围(逗号分隔):货架/阁楼/播种墙 */
    private String businessScope;

    private String contact;

    private String phone;

    /** 待考察/合格/不合格 */
    private String result;

    private String conclusion;

    private Long decidedBy;

    private String decidedByName;

    private LocalDateTime decidedAt;

    /** 合格后关联的供应商;不合格为 null */
    private Long supplierId;

    private Long projectId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
