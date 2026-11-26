package top.aole.rent.modules.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.dto.AssetSaveRequest;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.dto.ContractBuyoutDtos;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractChangeMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.DepositLedgerMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.contract.service.ContractBuyoutService;
import top.aole.rent.modules.contract.service.PaymentDueDateCalculator;
import top.aole.rent.modules.contract.service.ProjectPanoramaService;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.project.domain.Project;
import top.aole.rent.modules.project.dto.ProjectDtos;
import top.aole.rent.modules.project.mapper.ProjectMapper;
import top.aole.rent.modules.project.service.ProjectService;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V131 货架采购与出租三步闭环核心单测：
 * 1. 项目一键建「工程套装」资产 + 现场过磅/三方验收自动级联采购合同月结节点 & 5 年质保 & 磅差校验；
 * 2. 租中提前买断试算（含违约金、押金抵扣、老板 vs LP 成本脱敏）与正式执行（截断未来计划 + 过户）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAndBuyoutTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private PurchaseContractMapper pcMapper;
    @Mock private PurchaseContractTermMapper pcTermMapper;
    @Mock private PurchaseInMapper purchaseInMapper;
    @Mock private AssetMapper assetMapper;
    @Mock private AssetService assetService;
    @Mock private ProjectPanoramaService panoramaService;
    @Mock private AuditLogService auditLogService;

    @Mock private RentScheduleMapper scheduleMapper;
    @Mock private RentBillMapper billMapper;
    @Mock private DepositLedgerMapper depositMapper;
    @Mock private ContractAssetMapper contractAssetMapper;
    @Mock private ContractChangeMapper changeMapper;

    private ProjectService projectService;
    private ContractBuyoutService buyoutService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectMapper, customerMapper, supplierMapper, contractMapper,
                pcMapper, pcTermMapper, purchaseInMapper, assetMapper,
                assetService, panoramaService, auditLogService);
        buyoutService = new ContractBuyoutService(
                contractMapper, customerMapper, scheduleMapper, billMapper,
                depositMapper, contractAssetMapper, changeMapper,
                assetService, panoramaService, auditLogService);
        UserContext.set(new CurrentUser(1L, "老板", "老板", 1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void deliveryAcceptanceAutoCascadesMonthlyTermsWarrantyAndAsset() {
        Project p = new Project();
        p.setId(10L);
        p.setProjectNo("PRJ-2026-001");
        p.setName("惠州A仓2279货位重型货架");
        p.setAssetMode("工程套装");
        p.setPurchaseContractId(20L);
        p.setPrimaryAssetId(30L);
        p.setPlannedTons(new BigDecimal("45.00"));
        p.setWarrantyYears(5);
        p.setIsDeleted(0);

        PurchaseContract pc = new PurchaseContract();
        pc.setId(20L);
        pc.setContractNo("PC-2026-001");
        pc.setStatus("生效");
        pc.setSignedDate(LocalDate.of(2025, 8, 28));

        PurchaseContractTerm t1 = new PurchaseContractTerm();
        t1.setId(201L);
        t1.setPcId(20L);
        t1.setStageName("二期月结30%");
        t1.setTriggerPoint(PaymentDueDateCalculator.TRIGGER_AUDIT);
        t1.setDueRule(PaymentDueDateCalculator.RULE_NEXT_MONTH_DAY);
        t1.setDueDayOfMonth(20);

        PurchaseContractTerm t2 = new PurchaseContractTerm();
        t2.setId(202L);
        t2.setPcId(20L);
        t2.setStageName("三期尾款30%");
        t2.setTriggerPoint(PaymentDueDateCalculator.TRIGGER_AUDIT);
        t2.setDueRule(PaymentDueDateCalculator.RULE_NEXT_2_MONTH_DAY);
        t2.setDueDayOfMonth(20);

        Asset kit = new Asset();
        kit.setId(30L);
        kit.setSerialNo("HJ-2026-001");
        kit.setIsDeleted(0);

        when(projectMapper.selectById(10L)).thenReturn(p);
        when(pcMapper.selectById(20L)).thenReturn(pc);
        when(pcTermMapper.selectList(any())).thenReturn(Arrays.asList(t1, t2));
        when(purchaseInMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(assetMapper.selectById(30L)).thenReturn(kit);

        ProjectDtos.DeliveryRequest req = new ProjectDtos.DeliveryRequest();
        req.setShippedDate(LocalDate.of(2025, 9, 25));
        req.setInstallDoneDate(LocalDate.of(2025, 10, 12));
        req.setLoadTestDate(LocalDate.of(2025, 10, 14));
        req.setActualWeighTons(new BigDecimal("45.20"));
        req.setAcceptanceDate(LocalDate.of(2025, 10, 15));
        req.setAuditPassDate(LocalDate.of(2025, 10, 20));
        req.setPalletSlots(2279);
        req.setWarehouseZone("B栋二楼东区");

        ProjectDtos.Detail detail = projectService.updateDelivery(10L, req);

        // 1. 质保期自动推算 5 年 + 过磅偏差 +0.44% 在 ±3% 允差内
        assertEquals(LocalDate.of(2030, 10, 15), detail.getWarrantyEndDate());
        assertEquals(Boolean.TRUE, detail.getWeighWithinTolerance());
        assertEquals(0, new BigDecimal("0.44").compareTo(detail.getWeighDiffPct()));

        // 2. 采购合同两期月结账期自动按审核通过日(10/20)推算次月20日(11/20)与次次月20日(12/20)
        assertEquals(LocalDate.of(2025, 11, 20), t1.getDueDate());
        assertEquals(LocalDate.of(2025, 12, 20), t2.getDueDate());
        verify(pcTermMapper, times(2)).updateById(any(PurchaseContractTerm.class));

        // 3. 工程套装资产自动回填 5 年质保、过磅吨数、2279 货位、库区
        assertEquals(LocalDate.of(2030, 10, 15), kit.getWarrantyEndDate());
        assertEquals(2279, kit.getPalletSlots());
        assertEquals("B栋二楼东区", kit.getWarehouseZone());
        assertEquals(0, new BigDecimal("45.20").compareTo(kit.getWeighTons()));
        verify(assetMapper).updateById(kit);
    }

    @Test
    void createKitAssetCreatesSingleEngineeringKit() {
        Project p = new Project();
        p.setId(11L);
        p.setProjectNo("PRJ-2026-002");
        p.setName("东莞重型货架整套");
        p.setCustomerId(5L);
        p.setSupplierId(6L);
        p.setQuotePrincipal(new BigDecimal("210000"));
        p.setPalletSlots(2279);
        p.setPlannedTons(new BigDecimal("45.00"));
        p.setWarehouseZone("A栋1层");
        p.setWarrantyYears(5);
        p.setIsDeleted(0);

        when(projectMapper.selectById(11L)).thenReturn(p);
        when(assetService.create(any(AssetSaveRequest.class))).thenReturn(99L);

        Long id = projectService.createKitAsset(11L, null);
        assertEquals(99L, id);

        ArgumentCaptor<AssetSaveRequest> captor = ArgumentCaptor.forClass(AssetSaveRequest.class);
        verify(assetService).create(captor.capture());
        AssetSaveRequest created = captor.getValue();
        assertEquals("工程套装", created.getAssetMode());
        assertEquals("货架", created.getCategory());
        assertEquals(2279, created.getPalletSlots());
        assertEquals("A栋1层", created.getWarehouseZone());
        assertEquals(0, new BigDecimal("210000").compareTo(created.getPurchasePrice()));
        assertEquals(99L, p.getPrimaryAssetId());
    }

    @Test
    void earlyBuyoutPreviewAndExecuteTruncatesSchedulesAndMasksLp() {
        Contract c = new Contract();
        c.setId(100L);
        c.setNo("CT-2026-001");
        c.setCustomerId(8L);
        c.setStatus("生效");
        c.setTermMonths(60);
        c.setEndTransferPrice(new BigDecimal("1.00"));
        c.setIsDeleted(0);

        Customer cust = new Customer();
        cust.setId(8L);
        cust.setName("顺兴云仓");

        ProjectPanoramaDtos.Panorama pano = new ProjectPanoramaDtos.Panorama();
        pano.setTotalPeriods(60);
        pano.setSettledPeriods(24);
        pano.setRemainingPrincipal(new BigDecimal("140000.00"));
        pano.setOverdueAmount(new BigDecimal("2000.00"));
        pano.setDepositHeld(new BigDecimal("10000.00"));
        pano.setReceivedRent(new BigDecimal("114000.00"));
        pano.setPurchaseTotal(new BigDecimal("210000.00"));
        pano.setExpectedGrossProfit(new BigDecimal("74201.00"));

        when(contractMapper.selectById(100L)).thenReturn(c);
        when(customerMapper.selectById(8L)).thenReturn(cust);
        when(panoramaService.panorama(100L)).thenReturn(pano);

        // 1. 老板试算：140000 + 2000 + 140000*0.03(4200) + 1 - 10000 = 136201.00
        ContractBuyoutDtos.Preview bossPv = buyoutService.preview(100L, LocalDate.of(2027, 11, 1), null);
        assertEquals(0, new BigDecimal("4200.00").compareTo(bossPv.getPenaltyAmount()));
        assertEquals(0, new BigDecimal("136201.00").compareTo(bossPv.getNetBuyoutPayable()));
        assertEquals(0, new BigDecimal("50201.00").compareTo(bossPv.getBuyoutGrossProfit()));

        // 2. LP 视角必须脱敏采购成本与毛利
        UserContext.set(new CurrentUser(2L, "投资人LP", "LP", 1L));
        ContractBuyoutDtos.Preview lpPv = buyoutService.preview(100L, LocalDate.of(2027, 11, 1), null);
        assertNull(lpPv.getPurchaseCost());
        assertNull(lpPv.getBuyoutGrossProfit());

        // 3. 老板执行买断：截断 2 期未来计划、押金期末抵、过户 1 套设备、合同状态转「到期转让」
        UserContext.set(new CurrentUser(1L, "老板", "老板", 1L));
        RentSchedule s1 = new RentSchedule(); s1.setId(501L);
        RentSchedule s2 = new RentSchedule(); s2.setId(502L);
        when(scheduleMapper.selectList(any())).thenReturn(Arrays.asList(s1, s2));

        ContractAsset ca = new ContractAsset(); ca.setAssetId(30L);
        when(contractAssetMapper.selectList(any())).thenReturn(Collections.singletonList(ca));

        ContractBuyoutDtos.ExecuteRequest execReq = new ContractBuyoutDtos.ExecuteRequest();
        execReq.setBuyoutDate(LocalDate.of(2027, 11, 1));
        execReq.setNegotiatedAmount(new BigDecimal("135000.00"));
        execReq.setRemark("友好协商提前买断");

        ContractBuyoutDtos.ExecuteResult res = buyoutService.execute(100L, execReq);
        assertEquals("到期转让", res.getContractStatus());
        assertEquals(2, res.getTruncatedPeriods());
        assertEquals(1, res.getTransferredAssets());
        assertEquals(0, new BigDecimal("135000.00").compareTo(res.getFinalBuyoutAmount()));
        verify(scheduleMapper, times(2)).hardDeleteById(anyLong());
        verify(depositMapper).insert(any());
        verify(assetService).changeStatus(eq(30L), any());
        verify(changeMapper).insert(any());
    }
}
