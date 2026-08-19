package top.aole.rent.common.sso;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 门户 JWT 验签通过后抽出的身份快照（只取本系统需要的 claim）。 */
@Data
@AllArgsConstructor
public class PortalIdentity {
    /** 门户 yc_portal_member.id 字符串形态 —— 长期身份键（硬约束 8） */
    private String portalUid;
    /** 门户租户 tenant_id（VARCHAR 形态） */
    private String tenantId;
    private String name;
    private String phone;
    private String issuer;
}
