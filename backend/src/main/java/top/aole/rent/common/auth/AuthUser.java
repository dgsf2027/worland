package top.aole.rent.common.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 账号表 yc_rent_auth_user（2026-08-19 邀请码注册） */
@Data
@TableName("yc_rent_auth_user")
public class AuthUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    /** 门户 portal_uid（澳乐门户 SSO 长期身份键，V100 迁移加列；NULL=本地注册账号） */
    private String portalUid;
}
