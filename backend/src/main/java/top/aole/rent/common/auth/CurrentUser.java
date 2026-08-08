package top.aole.rent.common.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前登录用户(占位期由 X-User-* 头解析,就绪后由真 SSO 注入)。
 * 业务代码只依赖本抽象,不直接读头 —— 平台切真 SSO 时一处替换(ADR-001)。
 */
@Data
@AllArgsConstructor
public class CurrentUser {

    /** 用户主键(占位期由 X-User-Name 映射到真实 user 主键,作隔离键;缺省 0=未登录) */
    private Long userId;

    /** 显示名(X-User-Name) */
    private String userName;

    /** 角色:老板/财务/供应链/业务/GP/LP 等(X-User-Role) */
    private String role;

    /** 数据隔离:项目ID(多项目独立核算);占位期可空 */
    private Long projectId;
}
