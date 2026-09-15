package top.aole.rent.modules.inventory.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.inventory.dto.InvDtos;
import top.aole.rent.modules.inventory.service.InvService;

/**
 * 资产二维码免登查询(UserContextFilter 放行 /rent/inv-public/)。只返回企业信息与名称规格,不含数量、客户等经营信息;
 * 令牌为 32 位随机串,不可枚举。
 */
@Api(tags = "资产管理·扫码免登")
@RestController
@RequestMapping("/rent/inv-public")
@RequiredArgsConstructor
public class InvPublicController {

    private final InvService invService;

    @ApiOperation("扫码免登:企业信息 + 资产名称规格")
    @GetMapping("/{token}")
    public R<InvDtos.PublicScanView> scan(@PathVariable String token) {
        return R.ok(invService.publicScan(token));
    }
}
