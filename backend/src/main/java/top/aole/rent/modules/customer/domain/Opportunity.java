package top.aole.rent.modules.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商机(M1-09)。预估额×成交概率 → 销售管道加权预测。
 */
@Data
@TableName("yc_rent_opportunity")
public class Opportunity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    private String category;

    private BigDecimal estAmount;

    private BigDecimal winProb;

    private String estCloseMonth;

    /** open/won/lost */
    private String status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
