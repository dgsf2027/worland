package top.aole.rent.modules.roster.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 花名册/角色权限扩展(M5-03)。板块内数据权限 + 字段级保密(cost_visible)+ 行级隔离(owner_scoped)+ 项目归属。
 *
 * <p><b>口径(§4.24)</b>:数据权限复用 {@link top.aole.rent.common.auth.DataScope} 静态口径;
 * LP(刘总)cost_visible=0 仅月报不见成本;业务(BD)owner_scoped=1 仅见名下+公海;
 * 改动限老板 + 入 audit(M5-06·P2)。
 */
@Data
@TableName("yc_rent_user_role_ext")
public class UserRoleExt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String userName;

    /** 老板/财务/供应链/业务/GP/LP */
    private String role;

    private String dataScope;

    /** 是否可见成本/账期/授信等敏感字段(LP=0) */
    private Integer costVisible;

    /** 是否仅见名下+公海(业务 BD=1) */
    private Integer ownerScoped;

    private Long projectId;

    private Integer active;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
