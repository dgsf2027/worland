package top.aole.rent.modules.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseInvoiceMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentAllocMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentPayableMapper;
import top.aole.rent.modules.pcontract.service.PurchaseContractService;
import top.aole.rent.modules.project.domain.Project;
import top.aole.rent.modules.project.mapper.ProjectMapper;
import top.aole.rent.modules.project.service.ProjectService;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;
import top.aole.rent.modules.contract.service.ProjectPanoramaService;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.payproof.service.PayProofService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 5大核心模块安全删除守卫测试（项目 / 采购合同）。
 */
@ExtendWith(MockitoExtension.class)
class SafeDeleteTest {

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

    @Mock private SupplierPaymentMapper paymentMapper;
    @Mock private SupplierPaymentAllocMapper allocMapper;
    @Mock private PurchaseInvoiceMapper invoiceMapper;
    @Mock private PayProofService payProofService;
    @Mock private VoucherService voucherService;
    @Mock private PayableMapper payableMapper;
    @Mock private SupplierPaymentPayableMapper sppMapper;

    private ProjectService projectService;
    private PurchaseContractService pcService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectMapper, customerMapper, supplierMapper, contractMapper,
                pcMapper, pcTermMapper, purchaseInMapper, assetMapper,
                assetService, panoramaService, auditLogService);
        pcService = new PurchaseContractService(
                pcMapper, pcTermMapper, paymentMapper, allocMapper, invoiceMapper,
                supplierMapper, payProofService, voucherService, auditLogService,
                purchaseInMapper, payableMapper, sppMapper, contractMapper, customerMapper);
        UserContext.set(new CurrentUser(1L, "老板", "老板", 1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void deleteProjectSoftDeletesAndAudits() {
        Project p = new Project();
        p.setId(5L);
        p.setProjectNo("PRJ-TEST-1");
        p.setName("测试项目");
        p.setIsDeleted(0);
        when(projectMapper.selectById(5L)).thenReturn(p);

        projectService.delete(5L);
        verify(projectMapper).deleteById(5L);
        verify(auditLogService).record(eq("删除租赁项目"), eq("project"), eq(5L), any(), contains("PRJ-TEST-1"));
    }

    @Test
    void deletePurchaseContractBlocksWhenPaidAndSucceedsWhenClean() {
        PurchaseContract pc = new PurchaseContract();
        pc.setId(9L);
        pc.setContractNo("GZJTN20260820LGY01");
        pc.setStatus("执行中");
        when(pcMapper.selectById(9L)).thenReturn(pc);

        // 有有效付款时拦截
        when(paymentMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class, () -> pcService.delete(9L));
        assertTrue(ex.getMessage().contains("有效付款"));

        // 无有效付款无发票时顺利删除并解绑
        when(paymentMapper.selectCount(any())).thenReturn(0L);
        when(invoiceMapper.selectCount(any())).thenReturn(0L);
        pcService.delete(9L);
        verify(pcTermMapper).delete(any());
        verify(pcMapper).deleteById(9L);
    }
}
