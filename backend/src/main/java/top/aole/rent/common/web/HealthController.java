package top.aole.rent.common.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.result.R;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 + 身份自省。context-path=/api,完整路径 GET /api/v1/health。
 */
@Api(tags = "系统")
@RestController
@RequestMapping("/v1")
public class HealthController {

    @ApiOperation("健康检查")
    @GetMapping("/health")
    public R<String> health() {
        return R.ok("worland-rent backend alive");
    }

    @ApiOperation("身份自省(验证占位头 UserContext 适配层 S0-03)")
    @GetMapping("/whoami")
    public R<Map<String, Object>> whoami() {
        // 需登录:缺 X-User-Name 头 → 401(由 UserContext.require 抛出)
        CurrentUser u = UserContext.require();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.getUserId());
        m.put("userName", u.getUserName());
        m.put("role", u.getRole());
        m.put("projectId", u.getProjectId());
        return R.ok(m);
    }
}
