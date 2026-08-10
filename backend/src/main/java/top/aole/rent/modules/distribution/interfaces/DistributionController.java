package top.aole.rent.modules.distribution.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.distribution.dto.DistributionDtos;
import top.aole.rent.modules.distribution.service.DistributionService;

import java.util.List;

/**
 * 结账分配中心(M3-03/04)。每月5号:净利→管理费阶梯→可分配→50现金/50滚存→按出资比例分到出资人 + 留存校验。
 *
 * <p>P0-D RBAC:分配运行/冲销 = 财务 + 老板;分配表按角色可见(P0-E,LP 仅见自己那份,由 service 投影)。
 */
@Api(tags = "结账分配·管理费阶梯/50-50/留存校验")
@RestController
@RequestMapping("/rent")
@RequiredArgsConstructor
public class DistributionController {

    private final DistributionService distributionService;

    @ApiOperation("结账分配运行(敏感·财务+老板·period 幂等·净利→管理费阶梯→可分配→50现金/50滚存→留存校验)")
    @RequireRole(value = {"财务", "老板"}, action = "结账分配", targetType = "distribution")
    @PostMapping("/distribution/run")
    public R<DistributionDtos.DistributionDetail> run(@RequestBody DistributionDtos.RunRequest req) {
        return R.ok(distributionService.run(req));
    }

    @ApiOperation("分配冲销(敏感·财务+老板·置 reversed+负额镜像·reverses_id 幂等·期释放可重算)")
    @RequireRole(value = {"财务", "老板"}, action = "分配冲销", targetType = "distribution")
    @PostMapping("/distribution/{id}/reverse")
    public R<DistributionDtos.ReverseImpact> reverse(@PathVariable Long id,
                                                     @RequestBody(required = false) DistributionDtos.ReverseRequest req) {
        return R.ok(distributionService.reverse(id, req));
    }

    @ApiOperation("分配单列表(按期/仅生效筛选)")
    @GetMapping("/distribution")
    public R<List<DistributionDtos.DistributionItem>> list(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Boolean activeOnly) {
        return R.ok(distributionService.list(period, activeOnly));
    }

    @ApiOperation("分配单详情:头 + 每人份额(P0-E 角色投影·LP 仅见自己那份) + 计算链路")
    @GetMapping("/distribution/{id}")
    public R<DistributionDtos.DistributionDetail> detail(@PathVariable Long id) {
        return R.ok(distributionService.detail(id));
    }

    @ApiOperation("出资人名册(GP/LP·出资额·比例·标注本人那份)")
    @GetMapping("/investors")
    public R<List<DistributionDtos.InvestorItem>> investors() {
        return R.ok(distributionService.investors());
    }
}
