package top.aole.rent.modules.contract.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
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
import top.aole.rent.modules.contract.dto.ContractChangeRequest;
import top.aole.rent.modules.contract.dto.ContractDetailResponse;
import top.aole.rent.modules.contract.dto.ContractEditRequest;
import top.aole.rent.modules.contract.dto.ContractListItem;
import top.aole.rent.modules.contract.dto.ContractSignRequest;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.dto.BoqDtos;
import top.aole.rent.modules.contract.service.ContractBoqExcelService;
import top.aole.rent.modules.contract.service.ContractBoqService;
import top.aole.rent.modules.contract.service.ContractService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 合同 · 签约与租金计划(M1-10/11/17/18)。签约自动生成 N 期 + 详情(勾稽/回款/每期构成/单笔P&L) + 作废/变更/续租。
 */
@Api(tags = "合同·签约与租金计划")
@RestController
@RequestMapping("/rent/contracts")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final ContractBoqService boqService;
    private final ContractBoqExcelService boqExcelService;

    @ApiOperation("合同列表:按状态/客户/编号筛选 + 分页")
    @GetMapping
    public R<PageResult<ContractListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(contractService.list(status, customerId, keyword, page, size));
    }

    @ApiOperation("签约:录要素→挂N台设备→自动生成租金计划N期→设备转在租→押金台账")
    @PostMapping
    public R<Long> sign(@Validated @RequestBody ContractSignRequest req) {
        return R.ok(contractService.sign(req));
    }

    @ApiOperation("合同详情:要点+勾稽校验行+回款进度+租金计划逐期+每期租金构成+单笔P&L")
    @GetMapping("/{id}")
    public R<ContractDetailResponse> detail(@PathVariable Long id) {
        return R.ok(contractService.detail(id));
    }

    @ApiOperation("编辑合同要素(敏感·财务+老板·留痕):草稿直接改;生效合同保留已出单期次、其余期次重排、押金差额补收/退回")
    @RequireRole(value = {"财务", "老板"}, action = "合同编辑", targetType = "contract")
    @PutMapping("/{id}")
    public R<Void> edit(@PathVariable Long id, @Validated @RequestBody ContractEditRequest req) {
        contractService.edit(id, req);
        return R.ok();
    }

    @ApiOperation("作废(敏感·财务+老板·限未采购·整份红冲:计划删/押金退/设备释放)")
    @RequireRole(value = {"财务", "老板"}, action = "合同作废", targetType = "contract")
    @PostMapping("/{id}/void")
    public R<Void> voidContract(@PathVariable Long id, @RequestBody(required = false) ContractChangeRequest req) {
        contractService.voidContract(id, req);
        return R.ok();
    }

    @ApiOperation("续租:追加期数,重算租金计划")
    @PostMapping("/{id}/renew")
    public R<Void> renew(@PathVariable Long id, @RequestBody ContractChangeRequest req) {
        contractService.renew(id, req);
        return R.ok();
    }

    @ApiOperation("变更/提前结清:截断剩余未到期计划,合同关闭,押金期末抵")
    @PostMapping("/{id}/change")
    public R<Void> change(@PathVariable Long id, @RequestBody(required = false) ContractChangeRequest req) {
        contractService.change(id, req);
        return R.ok();
    }

    // ============ 合同清单(《工程量清单计价表》) ============

    @ApiOperation("合同清单:明细 + 含税合计(=设备总价)/不含税/税额")
    @GetMapping("/{id}/boq")
    public R<BoqDtos.Boq> boq(@PathVariable Long id) {
        return R.ok(boqService.boq(contractService.requireContract(id)));
    }

    @ApiOperation("保存合同清单(整表;合计回写合同设备总价)")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "合同清单保存", targetType = "contract")
    @PutMapping("/{id}/boq")
    public R<BoqDtos.Boq> saveBoq(@PathVariable Long id, @RequestBody BoqDtos.SaveRequest req) {
        return R.ok(boqService.save(contractService.requireContract(id), req.getLines()));
    }

    @ApiOperation("导出合同清单 Excel(《工程量清单计价表》版式;template=true 只导表头模板)")
    @GetMapping("/{id}/boq/export")
    public ResponseEntity<ByteArrayResource> exportBoq(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "false") boolean template) {
        Contract c = contractService.requireContract(id);
        byte[] data = boqExcelService.export(c, boqService.boq(c), template);
        String name = "工程量清单计价表-" + c.getNo()
                + (template ? "-模板" : "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)) + ".xlsx";
        ContentDisposition cd = ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new ByteArrayResource(data));
    }

    @ApiOperation("导入合同清单 Excel(整表替换,合计回写设备总价)")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "合同清单导入", targetType = "contract")
    @PostMapping("/{id}/boq/import")
    public R<BoqDtos.ImportResult> importBoq(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return R.ok(boqExcelService.importFile(contractService.requireContract(id), file));
    }

    @ApiOperation("按清单行数量一键生成设备(填了品类的行;生成的设备挂本合同,状态=采购)")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "按合同清单生成设备", targetType = "contract")
    @PostMapping("/{id}/boq/generate-assets")
    public R<BoqDtos.GenerateResult> generateAssets(@PathVariable Long id,
                                                    @RequestBody(required = false) BoqDtos.GenerateRequest req) {
        return R.ok(boqService.generateAssets(contractService.requireContract(id),
                req == null ? null : req.getLineIds()));
    }
}
