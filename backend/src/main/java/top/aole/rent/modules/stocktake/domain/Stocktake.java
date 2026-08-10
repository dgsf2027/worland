package top.aole.rent.modules.stocktake.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点单(头 · M4-05)。扫码盘点账面带出只录差异 → 账实差异生成盘盈亏调整单闭合(§4.24 走单据不直改台账)。
 */
@Data
@TableName("yc_rent_stocktake")
public class Stocktake {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    /** 盘点范围(全量/品类/项目) */
    private String scope;

    /** 状态:进行中/已闭合 */
    private String status;

    private Integer bookCount;

    private Integer scannedCount;

    private Integer diffCount;

    private Long operatorId;

    private LocalDateTime bizTime;

    private LocalDateTime closedAt;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
