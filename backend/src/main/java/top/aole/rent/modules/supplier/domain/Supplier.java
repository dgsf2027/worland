package top.aole.rent.modules.supplier.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商主表(M1-01)。上游关系生命周期:接触/试样/入库/主供/备供/淘汰。
 */
@Data
@TableName("yc_rent_supplier")
public class Supplier {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String contact;

    private String phone;

    /** 主营品类:播种墙/货架/阁楼/配件 */
    private String mainCategory;

    /** 关系阶段:接触/试样/入库/主供/备供/淘汰 */
    private String status;

    private String retireReason;

    private Long retiredBy;

    private LocalDateTime retiredAt;

    /** 项目隔离键(P1-4) */
    private Long projectId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
