package top.aole.rent.modules.inventory.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.inventory.dto.InvDtos;
import top.aole.rent.modules.inventory.service.InvService;

import java.util.List;

/**
 * 资产管理(仓库实物,一批同规格按数量管理):资产 / 二维码 / 状态 / 出租 / 出入库 / 损坏缺件 / 提醒报表。
 * 查看:登录即可;出租与出入库登记:老板/供应链/业务;赔偿处理与价目:老板/财务/供应链。
 */
@Api(tags = "资产管理")
@RestController
@RequestMapping("/rent/inventory")
@RequiredArgsConstructor
public class InvController {

    private final InvService invService;

    // ---------- 资产 ----------

    @ApiOperation("资产列表:编号/名称/规格/位置关键词 + 类别")
    @GetMapping("/items")
    public R<PageResult<InvDtos.ItemView>> items(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return R.ok(invService.listItems(keyword, category, page, size));
    }

    @ApiOperation("资产详情:状态数量 + 出租单 + 出入库记录")
    @GetMapping("/items/{id}")
    public R<InvDtos.ItemDetail> item(@PathVariable Long id) {
        return R.ok(invService.itemDetail(id));
    }

    @ApiOperation("新建资产(编号空则自动生成,初始数量记入库存)")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产建档", targetType = "inv_item")
    @PostMapping("/items")
    public R<Long> createItem(@RequestBody InvDtos.ItemSave req) {
        return R.ok(invService.createItem(req));
    }

    @ApiOperation("编辑资产(不改数量)")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产编辑", targetType = "inv_item")
    @PutMapping("/items/{id}")
    public R<Void> updateItem(@PathVariable Long id, @RequestBody InvDtos.ItemSave req) {
        invService.updateItem(id, req);
        return R.ok();
    }

    @ApiOperation("删除资产(无预订/出租中数量)")
    @RequireRole(value = {"老板", "供应链"}, action = "资产删除", targetType = "inv_item")
    @DeleteMapping("/items/{id}")
    public R<Void> deleteItem(@PathVariable Long id) {
        invService.deleteItem(id);
        return R.ok();
    }

    @ApiOperation("状态调整:入库/送修/修好/报废")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产状态调整", targetType = "inv_item")
    @PostMapping("/items/{id}/adjust")
    public R<Long> adjust(@PathVariable Long id, @RequestBody InvDtos.AdjustRequest req) {
        return R.ok(invService.adjust(id, req));
    }

    // ---------- 出租 ----------

    @ApiOperation("出租单列表")
    @GetMapping("/rentals")
    public R<PageResult<InvDtos.RentalView>> rentals(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long itemId,
                                                    @RequestParam(required = false) Long customerId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return R.ok(invService.listRentals(keyword, status, itemId, customerId, page, size));
    }

    @ApiOperation("新建出租(预订:库存 → 已预订)")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产出租预订", targetType = "inv_item")
    @PostMapping("/rentals")
    public R<Long> createRental(@RequestBody InvDtos.RentalSave req) {
        return R.ok(invService.createRental(req));
    }

    @ApiOperation("编辑出租单(客户/地址/时间/数量)")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产出租编辑", targetType = "inv_item")
    @PutMapping("/rentals/{id}")
    public R<Void> updateRental(@PathVariable Long id, @RequestBody InvDtos.RentalSave req) {
        invService.updateRental(id, req);
        return R.ok();
    }

    @ApiOperation("取消出租 / 释放未出库的预订数量")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产出租取消", targetType = "inv_item")
    @PostMapping("/rentals/{id}/cancel")
    public R<Void> cancelRental(@PathVariable Long id) {
        invService.cancelRental(id);
        return R.ok();
    }

    @ApiOperation("出库登记(已预订 → 出租中):数量/配件/状况;现场照片随后上传 bizType=inv_movement")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产出库", targetType = "inv_item")
    @PostMapping("/rentals/{id}/out")
    public R<InvDtos.MovementResult> outbound(@PathVariable Long id, @RequestBody InvDtos.OutRequest req) {
        return R.ok(invService.outbound(id, req));
    }

    @ApiOperation("归还登记(出租中 → 库存/维修中/已报废):检查损坏缺件并自动计算赔偿")
    @RequireRole(value = {"老板", "供应链", "业务"}, action = "资产归还", targetType = "inv_item")
    @PostMapping("/rentals/{id}/return")
    public R<InvDtos.MovementResult> returnBack(@PathVariable Long id, @RequestBody InvDtos.ReturnRequest req) {
        return R.ok(invService.returnBack(id, req));
    }

    // ---------- 出入库 / 损坏缺件 ----------

    @ApiOperation("出入库记录")
    @GetMapping("/movements")
    public R<PageResult<InvDtos.MovementView>> movements(@RequestParam(required = false) Long itemId,
                                                        @RequestParam(required = false) Long rentalId,
                                                        @RequestParam(required = false) String type,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(invService.listMovements(itemId, rentalId, type, page, size));
    }

    @ApiOperation("损坏缺件与赔偿")
    @GetMapping("/damages")
    public R<PageResult<InvDtos.DamageView>> damages(@RequestParam(required = false) String settleStatus,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return R.ok(invService.listDamages(settleStatus, keyword, page, size));
    }

    @ApiOperation("赔偿处理:待收取/已收取/已减免")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "赔偿处理", targetType = "inv_item")
    @PutMapping("/damages/{id}/settle")
    public R<Void> settle(@PathVariable Long id, @RequestBody InvDtos.SettleRequest req) {
        invService.settleDamage(id, req);
        return R.ok();
    }

    @ApiOperation("赔偿价目")
    @GetMapping("/comp-prices")
    public R<List<InvDtos.PriceRow>> prices() {
        return R.ok(invService.listPrices());
    }

    @ApiOperation("保存赔偿价目(整表)")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "赔偿价目保存", targetType = "inv_comp_price")
    @PutMapping("/comp-prices")
    public R<Void> savePrices(@RequestBody List<InvDtos.PriceRow> rows) {
        invService.savePrices(rows);
        return R.ok();
    }

    @ApiOperation("标签企业信息")
    @GetMapping("/company")
    public R<InvDtos.CompanyInfo> company() {
        return R.ok(invService.company());
    }

    @ApiOperation("保存标签企业信息")
    @RequireRole(value = {"老板", "供应链"}, action = "资产标签企业信息保存", targetType = "inv_company")
    @PutMapping("/company")
    public R<Void> saveCompany(@RequestBody InvDtos.CompanyInfo req) {
        invService.saveCompany(req);
        return R.ok();
    }

    // ---------- 提醒报表 / 扫码 ----------

    @ApiOperation("提醒与报表:库存/出租/闲置数量 + 合同到期/设备逾期/需要维修提醒")
    @GetMapping("/overview")
    public R<InvDtos.Overview> overview() {
        return R.ok(invService.overview());
    }

    @ApiOperation("扫码(登录后):库存与出租信息,可出库/归还")
    @GetMapping("/scan/{token}")
    public R<InvDtos.ScanView> scan(@PathVariable String token) {
        return R.ok(invService.scan(token));
    }
}
