package top.aole.rent.modules.automation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.automation.domain.AutomationLog;
import top.aole.rent.modules.automation.domain.AutomationRule;
import top.aole.rent.modules.automation.mapper.AutomationLogMapper;
import top.aole.rent.modules.automation.mapper.AutomationRuleMapper;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.project.domain.Project;
import top.aole.rent.modules.project.mapper.ProjectMapper;
import top.aole.rent.modules.transfer.dto.TransferDtos;
import top.aole.rent.modules.transfer.service.TransferService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 二期核心：业务事件全自动级联服务（V132）。
 * <ul>
 *   <li>签约生效 → 自动在「项目中心」建项目卡并回写关联</li>
 *   <li>项目三方验收通过 → 若绑定了「采购/投放」态的工程套装资产且已绑合同，自动推进为「在租」</li>
 *   <li>收租单全额核销 → 若合同全部期次租金已结清且约定留购价 ≤ 阈值（默认 100 元），自动触发 1 元留购过户并推进项目结项</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationEventService {

    public static final String RULE_CASCADE = "event_lifecycle_cascade";
    public static final String RULE_AUTO_BUYOUT = "auto_nominal_buyout";

    private final AutomationRuleMapper ruleMapper;
    private final AutomationLogMapper logMapper;
    private final ProjectMapper projectMapper;
    private final CustomerMapper customerMapper;
    private final PurchaseContractMapper pcMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final RentBillMapper rentBillMapper;
    private final ContractMapper contractMapper;

    @Autowired(required = false)
    @Lazy
    private TransferService transferService;

    /** 事件 1：租赁合同签约生效 → 自动在项目中心创建/对齐项目卡片。 */
    public void onContractSigned(Contract c) {
        if (c == null || c.getId() == null || !isRuleEnabled(RULE_CASCADE)) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            long exists = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                    .eq(Project::getRentContractId, c.getId())
                    .eq(Project::getIsDeleted, 0));
            if (exists > 0) {
                return;
            }
            Customer cust = c.getCustomerId() == null ? null : customerMapper.selectById(c.getCustomerId());
            PurchaseContract pc = pcMapper.selectOne(new LambdaQueryWrapper<PurchaseContract>()
                    .eq(PurchaseContract::getRentContractId, c.getId())
                    .ne(PurchaseContract::getStatus, "作废")
                    .last("limit 1"));
            Project p = new Project();
            p.setProjectNo("PRJ-" + c.getNo());
            p.setName((cust != null ? cust.getName() + " · " : "") + c.getNo());
            p.setAssetMode("工程套装");
            p.setStage("合同落定");
            p.setCustomerId(c.getCustomerId());
            p.setRentContractId(c.getId());
            if (pc != null) {
                p.setPurchaseContractId(pc.getId());
                p.setSupplierId(pc.getSupplierId());
                p.setWarrantyYears(pc.getWarrantyYears() != null ? pc.getWarrantyYears() : 5);
                p.setDeliveryAddress(pc.getDeliveryAddress());
                p.setDeliveryContact(pc.getDeliveryContact());
            } else {
                p.setWarrantyYears(5);
            }
            p.setQuotePrincipal(c.getFinalDealPrice() != null ? c.getFinalDealPrice() : c.getEquipmentTotal());
            p.setQuoteAnnualRate(c.getLeaseAnnualRate());
            p.setQuoteTermMonths(c.getTermMonths());
            p.setQuoteMonthRent(c.getMonthRent());
            p.setQuoteBuyoutPrice(c.getEndTransferPrice());
            p.setCreateByName("系统自动联动");
            p.setIsDeleted(0);
            projectMapper.insert(p);

            recordEventLog(RULE_CASCADE, "签约自动建项", "SUCCESS", 1,
                    "合同 " + c.getNo() + " 签约生效 → 自动生成项目 " + p.getProjectNo(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[自动事件·签约建项] 失败 contractId={}: {}", c.getId(), e.getMessage());
        }
    }

    /** 事件 2：项目现场三方验收合格 → 若工程套装仍为采购/投放态且已绑定租赁合同，自动推进为「在租」。 */
    public void onProjectAccepted(Project p) {
        if (p == null || p.getAcceptanceDate() == null || !isRuleEnabled(RULE_CASCADE)) {
            return;
        }
        if (p.getPrimaryAssetId() == null || p.getRentContractId() == null || p.getCustomerId() == null) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            Asset a = assetMapper.selectById(p.getPrimaryAssetId());
            if (a != null && ("采购".equals(a.getStatus()) || "投放".equals(a.getStatus()))) {
                assetService.markRented(a.getId(), p.getCustomerId(), p.getRentContractId());
                recordEventLog(RULE_CASCADE, "验收自动转在租", "SUCCESS", 1,
                        "项目 " + p.getProjectNo() + " 三方验收通过 → 资产 " + a.getSerialNo() + " 自动转为「在租」",
                        System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            log.warn("[自动事件·验收转在租] 失败 projectId={}: {}", p.getId(), e.getMessage());
        }
    }

    /** 事件 3：收租单全额核销 → 检测合同是否已满期且约定 1 元留购，满足则自动完成名义留购过户并推进项目结项。 */
    public void onRentBillFullyPaid(RentBill b) {
        if (b == null || b.getContractId() == null || !"已核销".equals(b.getStatus())) {
            return;
        }
        AutomationRule rule = loadRule(RULE_AUTO_BUYOUT);
        if (rule == null || !Integer.valueOf(1).equals(rule.getEnabled())) {
            return;
        }
        BigDecimal maxBuyout = rule.getThresholdAmount() != null ? rule.getThresholdAmount() : new BigDecimal("100");
        tryAutoNominalBuyout(b.getContractId(), maxBuyout, "EVENT");
    }

    /** 检查单份合同是否满足满期 + 约定名义留购条件，满足则自动触发免审过户并更新项目阶段。 */
    public boolean tryAutoNominalBuyout(Long contractId, BigDecimal maxBuyoutPrice, String triggerSource) {
        if (contractId == null || transferService == null) {
            return false;
        }
        long start = System.currentTimeMillis();
        Contract c = contractMapper.selectById(contractId);
        if (c == null || !"生效".equals(c.getStatus()) || c.getTermMonths() == null || c.getTermMonths() <= 0) {
            return false;
        }
        BigDecimal endPrice = c.getEndTransferPrice();
        if (endPrice == null || endPrice.signum() < 0 || endPrice.compareTo(maxBuyoutPrice) > 0) {
            return false;
        }
        List<RentBill> normalBills = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getContractId, contractId)
                .eq(RentBill::getBillKind, "正常")
                .eq(RentBill::getIsDeleted, 0));
        long settledCount = normalBills.stream()
                .filter(x -> "已核销".equals(x.getStatus()))
                .count();
        if (settledCount < c.getTermMonths()) {
            return false;
        }
        try {
            TransferDtos.ExpiryTransferRequest req = new TransferDtos.ExpiryTransferRequest();
            req.setContractId(contractId);
            req.setRemark("系统自动检测到全部 " + c.getTermMonths() + " 期租金已结清，按约定 ¥" + endPrice + " 留购价自动过户结项");
            TransferDtos.TransferResult tr = transferService.createExpiryTransfer(req);
            // 同步推进关联项目到「留购结项」
            List<Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                    .eq(Project::getRentContractId, contractId)
                    .eq(Project::getIsDeleted, 0));
            for (Project p : projects) {
                p.setStage("留购结项");
                projectMapper.updateById(p);
            }
            recordEventLog(RULE_AUTO_BUYOUT, "满期1元自动留购", "SUCCESS", 1,
                    "合同 " + c.getNo() + " 全部 " + c.getTermMonths() + " 期收齐 → 自动完成留购过户(单号 " + tr.getNo() + ")",
                    System.currentTimeMillis() - start, triggerSource);
            return true;
        } catch (Exception e) {
            log.warn("[自动事件·1元留购] 跳过 contractId={}: {}", contractId, e.getMessage());
            return false;
        }
    }

    public boolean isRuleEnabled(String ruleKey) {
        AutomationRule r = loadRule(ruleKey);
        return r != null && Integer.valueOf(1).equals(r.getEnabled());
    }

    private AutomationRule loadRule(String ruleKey) {
        return ruleMapper.selectOne(new LambdaQueryWrapper<AutomationRule>()
                .eq(AutomationRule::getRuleKey, ruleKey)
                .eq(AutomationRule::getIsDeleted, 0)
                .last("limit 1"));
    }

    private void recordEventLog(String ruleKey, String ruleName, String status, int affected, String summary, long ms) {
        recordEventLog(ruleKey, ruleName, status, affected, summary, ms, "EVENT");
    }

    private void recordEventLog(String ruleKey, String ruleName, String status, int affected,
                                String summary, long ms, String source) {
        AutomationLog al = new AutomationLog();
        al.setRuleKey(ruleKey);
        al.setRuleName(ruleName);
        al.setTriggerSource(source);
        al.setStatus(status);
        al.setAffectedCount(affected);
        al.setPushedCount(0);
        al.setSummary(summary);
        al.setDurationMs(ms);
        al.setExecutedAt(LocalDateTime.now());
        logMapper.insert(al);
    }
}
