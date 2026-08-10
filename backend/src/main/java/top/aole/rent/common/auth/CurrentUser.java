package top.aole.rent.common.auth;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户(占位期由 X-User-* 头解析,就绪后由真 SSO 注入)。
 * 业务代码只依赖本抽象,不直接读头 —— 平台切真 SSO 时一处替换(ADR-001)。
 */
@Data
@NoArgsConstructor
public class CurrentUser {

    /** 用户主键(占位期由 X-User-Name 映射到真实 user 主键,作隔离键;缺省 0=未登录) */
    private Long userId;

    /** 显示名(X-User-Name) */
    private String userName;

    /** 角色:老板/财务/供应链/业务/GP/LP 等(X-User-Role) */
    private String role;

    /** 数据隔离:项目ID(多项目独立核算);占位期可空 */
    private Long projectId;

    /** 请求指纹:来源IP(网关注入·审计抗抵赖 M5-06 P1-16) */
    private String clientIp;

    /** 请求指纹:请求URI(审计留痕) */
    private String requestUri;

    /** 请求指纹:请求号(链路追踪) */
    private String requestId;

    public CurrentUser(Long userId, String userName, String role, Long projectId) {
        this.userId = userId;
        this.userName = userName;
        this.role = role;
        this.projectId = projectId;
    }
}
