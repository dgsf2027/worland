package top.aole.rent.modules.inventory.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产(一批同规格实物,按数量管理)。total = stock + reserved + rented + repair + scrapped。
 * 照片走对象存储 biz_type=inv_item。
 */
@Data
@TableName("yc_rent_inv_item")
public class InvItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String spec;

    /** 播种墙/货架/阁楼/其他 */
    private String category;

    private String unit;

    private Integer totalQty;

    /** 库存(在库未预订 = 闲置) */
    private Integer stockQty;

    private Integer reservedQty;

    private Integer rentedQty;

    private Integer repairQty;

    private Integer scrappedQty;

    private String location;

    /** 二维码令牌 */
    private String qrToken;

    private String remark;

    private String createByName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
