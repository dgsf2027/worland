package top.aole.rent.modules.distribution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出资人名册(M3-04)。GP/LP · 出资额 · 比例;仅供分配计算,无员工门户。
 *
 * <p>§4.24 单一真相源:{@code ratio}=出资比例快照,结账分配按本表 ratio 投影到每人份额。
 * P0-E 角色可见:{@code user_id} 关联登录用户,LP 只见自己那份分配。
 */
@Data
@TableName("yc_rent_investor")
public class Investor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** GP 普通合伙人 / LP 有限合伙人 */
    private String role;

    /** 出资额(元) */
    private BigDecimal amount;

    /** 出资比例(=amount/合计) */
    private BigDecimal ratio;

    /** 关联登录用户(LP 仅见自己那份) */
    private Long userId;

    private Integer active;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
