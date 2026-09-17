package top.aole.rent.modules.supplier.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商考察(对齐「厂家考察汇总表」)。合格 → 在供应商池建档/关联(阶段=入库);不合格 → 不列入、解除关联。
 *
 * <p>结果可改判(页面或 Excel 导入):合格→不合格 解除关联并将该供应商置「淘汰」留痕;不合格→合格 重新列入并关联。
 * 考察记录压缩包存对象存储 biz_type=supplier_inspection。
 */
@Data
@TableName("yc_rent_supplier_inspection")
public class SupplierInspection {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 序号(列表排序,升序;空排最后) */
    private Integer sortNo;

    private String companyName;

    private String legalPerson;

    /** 注册资本(万元·按原文存,如「200」「60*6」) */
    private String registeredCapitalWan;

    /** 成立时间 */
    private LocalDate establishedDate;

    /** 业务类型(逗号分隔):货架/阁楼/播种墙(表格旧版列名「业务范围」) */
    private String businessScope;

    /** 公司业务范围(公司简介) */
    private String companyProfile;

    /** 业绩/万元(原文) */
    private String performanceWan;

    /** 社保员工(原文) */
    private String socialStaff;

    /** 产品图片说明(无图时的文字) */
    private String productImageNote;

    /** 考察观后感 */
    private String impression;

    /** 公司地址 */
    private String address;

    private String contact;

    private String phone;

    /** 待考察/合格/不合格 */
    private String result;

    private String conclusion;

    private Long decidedBy;

    private String decidedByName;

    private LocalDateTime decidedAt;

    /** 合格时关联的供应商;不合格/待考察为 null */
    private Long supplierId;

    private Long projectId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
