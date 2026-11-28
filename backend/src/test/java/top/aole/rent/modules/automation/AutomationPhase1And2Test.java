package top.aole.rent.modules.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.automation.domain.AutomationRule;
import top.aole.rent.modules.automation.domain.BankStatement;
import top.aole.rent.modules.automation.domain.NotifyChannel;
import top.aole.rent.modules.automation.dto.AutomationDtos;
import top.aole.rent.modules.automation.mapper.AutomationLogMapper;
import top.aole.rent.modules.automation.mapper.AutomationRuleMapper;
import top.aole.rent.modules.automation.mapper.BankStatementMapper;
import top.aole.rent.modules.automation.mapper.NotifyChannelMapper;
import top.aole.rent.modules.automation.service.AutomationEngineService;
import top.aole.rent.modules.automation.service.AutomationEventService;
import top.aole.rent.modules.automation.service.BankReconcileService;
import top.aole.rent.modules.automation.service.WebhookNotifyService;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.billing.service.RentBillService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentAllocMapper;
import top.aole.rent.modules.project.mapper.ProjectMapper;
import top.aole.rent.modules.task.service.TaskService;
import top.aole.rent.modules.workbench.dto.WorkbenchWaterlineDtos;
import top.aole.rent.modules.workbench.service.WorkbenchCashWaterlineService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V132 系统自动化一期 + 二期核心单元测试：
 * 1. 多平台 Webhook（企微/钉钉/飞书）载荷构建与 LP 渠道阻断；
 * 2. 老板经营晨报 + 3% 销项票超期扫描 + 资金水位安全垫告警；
 * 3. 逾期阶梯自动算滞纳金与标准催款通知函生成；
 * 4. 银行流水智能匹配（合同号+金额99%、客户户名+金额95%、重复流水去重）与批量一键核销。
 */
@ExtendWith(MockitoExtension.class)
class AutomationPhase1And2Test {

    @Mock private AutomationRuleMapper ruleMapper;
    @Mock private NotifyChannelMapper channelMapper;
    @Mock private AutomationLogMapper logMapper;
    @Mock private BankStatementMapper bankStatementMapper;
    @Mock private RentBillMapper rentBillMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private PurchaseContractMapper pcMapper;
    @Mock private PurchaseContractTermMapper pcTermMapper;
    @Mock private SupplierPaymentAllocMapper spAllocMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private AssetMapper assetMapper;
    @Mock private OverdueCaseMapper overdueCaseMapper;
    @Mock private TaskService taskService;
    @Mock private WorkbenchCashWaterlineService waterlineService;
    @Mock private WebhookNotifyService webhookNotifyService;
    @Mock private AutomationEventService eventService;
    @Mock private AuditLogService auditLogService;
    @Mock private RentBillService rentBillService;

    private AutomationEngineService engineService;
    private BankReconcileService reconcileService;

    @BeforeEach
    void setUp() {
        engineService = new AutomationEngineService(
                ruleMapper, channelMapper, logMapper, bankStatementMapper,
                rentBillMapper, contractMapper, customerMapper, pcMapper,
                pcTermMapper, spAllocMapper, projectMapper, assetMapper,
                overdueCaseMapper, taskService, waterlineService,
                webhookNotifyService, eventService, auditLogService);
        reconcileService = new BankReconcileService(
                bankStatementMapper, rentBillMapper, contractMapper,
                customerMapper, rentBillService, auditLogService);
        UserContext.set(new CurrentUser(1L, "老板", "老板", 1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void webhookPayloadFormatsWecomDingtalkFeishuAndBlocksLpChannel() throws Exception {
        WebhookNotifyService realWebhook = new WebhookNotifyService(channelMapper, new ObjectMapper());
        NotifyChannel wecom = new NotifyChannel();
        wecom.setChannelType("wecom");
        wecom.setTargetRole("老板");
        String p1 = realWebhook.buildPayload(wecom, "晨报", "- 实收 ¥10,000");
        assertTrue(p1.contains("\"msgtype\":\"markdown\""));
        assertTrue(p1.contains("晨报"));

        NotifyChannel ding = new NotifyChannel();
        ding.setChannelType("dingtalk");
        String p2 = realWebhook.buildPayload(ding, "告警", "水位不足");
        assertTrue(p2.contains("\"title\":\"告警\""));

        NotifyChannel feishu = new NotifyChannel();
        feishu.setChannelType("feishu");
        feishu.setSecret("my-secret");
        String p3 = realWebhook.buildPayload(feishu, "通知", "测试");
        assertTrue(p3.contains("\"msg_type\":\"text\""));
        assertTrue(p3.contains("\"sign\""));

        // 禁止创建 LP 角色外推通道
        AutomationDtos.ChannelSaveRequest lpReq = new AutomationDtos.ChannelSaveRequest();
        lpReq.setName("LP群");
        lpReq.setChannelType("wecom");
        lpReq.setWebhookUrl("https://example.com/hook");
        lpReq.setTargetRole("LP");
        assertThrows(Exception.class, () -> engineService.saveChannel(lpReq));

        // 校验企微/飞书业务返回码：errcode!=0 必须报错（杜绝 HTTP 200 假绿灯）
        assertThrows(IllegalStateException.class, () ->
                realWebhook.validatePlatformResponse("wecom", "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}"));
        assertThrows(IllegalStateException.class, () ->
                realWebhook.validatePlatformResponse("feishu", "{\"code\":19021,\"msg\":\"sign match fail\"}"));
        assertDoesNotThrow(() ->
                realWebhook.validatePlatformResponse("wecom", "{\"errcode\":0,\"errmsg\":\"ok\"}"));

        // 企微自建应用（直达个人微信）：解析 spec 并生成个人微信兼容的 text 载荷（去除 markdown 星号）
        String[] spec = realWebhook.parseWecomAppSpec("wecom-app://ww123456/1000002/LaobanId");
        assertEquals("ww123456", spec[0]);
        assertEquals("1000002", spec[1]);
        assertEquals("LaobanId", spec[2]);
        String appPayload = realWebhook.buildWecomAppPayload(1000002, "LaobanId", "晨报", "- 昨日实收：**¥100.00**");
        assertTrue(appPayload.contains("\"msgtype\":\"text\""));
        assertTrue(appPayload.contains("\"agentid\":1000002"));
        assertTrue(appPayload.contains("\"touser\":\"LaobanId\""));
        assertFalse(appPayload.contains("**"));
    }

    @Test
    void cronRunInjectsValidSystemBossContextWithoutAuthError() {
        UserContext.clear();
        AutomationRule rule = new AutomationRule();
        rule.setId(6L);
        rule.setRuleKey("cash_waterline_alert");
        rule.setRuleName("银行资金水位安全垫预警");
        rule.setEnabled(1);
        rule.setThresholdAmount(new BigDecimal("50000.00"));
        rule.setTargetRole("老板");

        WorkbenchWaterlineDtos.CashWaterline wl = new WorkbenchWaterlineDtos.CashWaterline();
        wl.setSavedCashBalance(new BigDecimal("100000.00"));
        wl.setMinBalance(new BigDecimal("80000.00"));
        wl.setMinBalanceMonth("2026-12");

        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(waterlineService.cashWaterline(6, null)).thenAnswer(inv -> {
            assertEquals("老板", UserContext.getRole());
            return wl;
        });

        AutomationDtos.RunResult res = engineService.runRule("cash_waterline_alert", "CRON", false);
        assertEquals("SUCCESS", res.getStatus());
        assertNull(UserContext.get());
    }

    @Test
    void cashWaterlineAlertFiresWhenBelowSafetyFloor() {
        AutomationRule rule = new AutomationRule();
        rule.setId(6L);
        rule.setRuleKey("cash_waterline_alert");
        rule.setRuleName("银行资金水位安全垫预警");
        rule.setEnabled(1);
        rule.setThresholdAmount(new BigDecimal("50000.00"));
        rule.setTargetRole("老板");

        WorkbenchWaterlineDtos.CashWaterline wl = new WorkbenchWaterlineDtos.CashWaterline();
        wl.setSavedCashBalance(new BigDecimal("60000.00"));
        wl.setMinBalance(new BigDecimal("18500.00"));
        wl.setMinBalanceMonth("2026-12");

        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(waterlineService.cashWaterline(6, null)).thenReturn(wl);
        when(webhookNotifyService.pushToRole(eq("老板"), anyString(), anyString())).thenReturn(1);

        AutomationDtos.RunResult res = engineService.runRule("cash_waterline_alert", "MANUAL", true);
        assertEquals("SUCCESS", res.getStatus());
        assertEquals(1, res.getAffectedCount());
        assertEquals(1, res.getPushedCount());
        assertTrue(res.getSummary().contains("18500.00"));
        verify(logMapper).insert(any());
    }

    @Test
    void collectionNoticesComputePenaltyAndEscalationTier() {
        RentBill b = new RentBill();
        b.setId(101L);
        b.setBillNo("RB-2026-001");
        b.setContractId(10L);
        b.setPeriodNo(12);
        b.setDueDate(LocalDate.now().minusDays(10));
        b.setAmount(new BigDecimal("10000.00"));
        b.setReceivedAmount(BigDecimal.ZERO);
        b.setStatus("逾期");
        b.setBillKind("正常");

        Contract c = new Contract();
        c.setId(10L);
        c.setNo("CT-2026-001");
        c.setCustomerId(5L);
        c.setStatus("生效");
        c.setOverdueDailyRate(new BigDecimal("0.0005"));

        Customer cust = new Customer();
        cust.setId(5L);
        cust.setName("顺兴云仓");

        when(rentBillMapper.selectList(any())).thenReturn(Collections.singletonList(b));
        when(contractMapper.selectById(10L)).thenReturn(c);
        when(customerMapper.selectById(5L)).thenReturn(cust);

        List<AutomationDtos.CollectionNotice> notices = engineService.listCollectionNotices(3);
        assertEquals(1, notices.size());
        AutomationDtos.CollectionNotice n = notices.get(0);
        assertEquals("T+7罚息升级", n.getLevel());
        assertEquals(10L, n.getOverdueDays());
        // 10000 * 0.0005 * 10 = 50.00
        assertEquals(0, new BigDecimal("50.00").compareTo(n.getEstimatedPenalty()));
        assertTrue(n.getNoticeText().contains("顺兴云仓"));
        assertTrue(n.getNoticeText().contains("CT-2026-001"));
    }

    @Test
    void bankStatementSmartMatchAndBatchReconcile() {
        RentBill b = new RentBill();
        b.setId(201L);
        b.setBillNo("RB-2026-009");
        b.setContractId(10L);
        b.setPeriodNo(1);
        b.setDueDate(LocalDate.of(2026, 11, 15));
        b.setAmount(new BigDecimal("4736.69"));
        b.setReceivedAmount(BigDecimal.ZERO);
        b.setStatus("待收");
        b.setBillKind("正常");

        Contract c = new Contract();
        c.setId(10L);
        c.setNo("CT-HJ-2026");
        c.setCustomerId(5L);

        Customer cust = new Customer();
        cust.setId(5L);
        cust.setName("顺兴物流有限公司");

        when(rentBillMapper.selectList(any())).thenReturn(Collections.singletonList(b));
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(c));
        when(customerMapper.selectList(any())).thenReturn(Collections.singletonList(cust));
        when(bankStatementMapper.selectCount(any())).thenReturn(0L);

        AutomationDtos.ManualStatementInput in1 = new AutomationDtos.ManualStatementInput();
        in1.setTxDate(LocalDate.of(2026, 11, 15));
        in1.setCounterpartyName("顺兴物流有限公司");
        in1.setAmount(new BigDecimal("4736.69"));
        in1.setDirection("IN");
        in1.setBankSerial("BANK-20261115-001");
        in1.setMemo("支付货架租金 CT-HJ-2026");

        AutomationDtos.ImportResult ir = reconcileService.ingestAndMatch(Collections.singletonList(in1));
        assertEquals(1, ir.getImportedRows());
        assertEquals(1, ir.getMatchedRows());

        ArgumentCaptor<BankStatement> cap = ArgumentCaptor.forClass(BankStatement.class);
        verify(bankStatementMapper).insert(cap.capture());
        BankStatement saved = cap.getValue();
        assertEquals("已匹配", saved.getMatchStatus());
        assertEquals(99, saved.getConfidence());
        assertEquals(201L, saved.getMatchedBizId());

        // 一键批量核销
        saved.setId(88L);
        when(bankStatementMapper.selectById(88L)).thenReturn(saved);
        AutomationDtos.ReconcileResult rr = reconcileService.confirmBatch(Arrays.asList(88L));
        assertEquals(1, rr.getSucceeded());
        assertEquals(0, rr.getFailed());
        assertEquals("已核销", saved.getMatchStatus());
        verify(rentBillService).matchFromBankStatement(eq(201L), eq(new BigDecimal("4736.69")),
                eq("BANK-20261115-001"), anyString());
    }
}
