package top.aole.rent.modules.contract.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.dto.StatusChangeRequest;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.ContractChange;
import top.aole.rent.modules.contract.domain.DepositLedger;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.dto.ContractBuyoutDtos;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractChangeMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.DepositLedgerMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 租中提前买断试算与办理服务（Step 3 租后闭环 · V131）。
 * <p>核心公式：{@code 应补买断净额 = 剩余未还本金 + 逾期未收租金 + 提前买断违约金(剩余本金×违约金率) + 约定留购价 − 在手押金}。
 */
@Service
@RequiredArgsConstructor
public class ContractBuyoutService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_PENALTY_RATE = new BigDecimal("0.03");

    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final RentScheduleMapper scheduleMapper;
    private final RentBillMapper billMapper;
    private final DepositLedgerMapper depositMapper;
    private final ContractAssetMapper contractAssetMapper;
    private final ContractChangeMapper changeMapper;
    private final AssetService assetService;
    private final ProjectPanoramaService panoramaService;
    private final AuditLogService auditLogService;

    /** 试算提前买断金额及与“持有到期”的毛利对比。 */
    public ContractBuyoutDtos.Preview preview(Long contractId, LocalDate buyoutDate, BigDecimal overridePenaltyRate) {
        Contract c = loadActive(contractId);
        LocalDate asOf = buyoutDate != null ? buyoutDate : LocalDate.now();
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());

        ProjectPanoramaDtos.Panorama pano = panoramaService.panorama(contractId);
        Customer cust = c.getCustomerId() == null ? null : customerMapper.selectById(c.getCustomerId());

        BigDecimal remainingPrincipal = nz(pano.getRemainingPrincipal());
        BigDecimal overdueUnpaid = nz(pano.getOverdueAmount());
        BigDecimal rate = overridePenaltyRate != null
                ? overridePenaltyRate.max(ZERO)
                : (c.getEarlyBuyoutPenaltyRate() != null ? c.getEarlyBuyoutPenaltyRate() : DEFAULT_PENALTY_RATE);
        BigDecimal penalty = scale2(remainingPrincipal.multiply(rate));
        BigDecimal endPrice = scale2(nz(c.getEndTransferPrice()));
        BigDecimal depositHeld = scale2(nz(pano.getDepositHeld()));
        BigDecimal netPayable = scale2(remainingPrincipal.add(overdueUnpaid).add(penalty).add(endPrice)
                .subtract(depositHeld).max(ZERO));

        BigDecimal collectedRent = scale2(nz(pano.getReceivedRent()));
        BigDecimal totalRecovery = scale2(collectedRent.add(depositHeld).add(netPayable));

        ContractBuyoutDtos.Preview out = new ContractBuyoutDtos.Preview();
        out.setContractId(c.getId());
        out.setContractNo(c.getNo());
        out.setCustomerName(cust != null ? cust.getName() : null);
        out.setBuyoutDate(asOf);
        out.setTotalPeriods(pano.getTotalPeriods());
        out.setSettledPeriods(pano.getSettledPeriods());
        out.setRemainingPeriods(Math.max(0, pano.getTotalPeriods() - pano.getSettledPeriods()));
        out.setRemainingPrincipal(scale2(remainingPrincipal));
        out.setOverdueUnpaidRent(scale2(overdueUnpaid));
        out.setPenaltyRate(rate);
        out.setPenaltyAmount(penalty);
        out.setEndTransferPrice(endPrice);
        out.setDepositHeld(depositHeld);
        out.setNetBuyoutPayable(netPayable);
        out.setCollectedRent(collectedRent);
        out.setTotalRecoveryIfBuyout(totalRecovery);

        if (seeCost && pano.getPurchaseTotal() != null) {
            out.setPurchaseCost(scale2(pano.getPurchaseTotal()));
            out.setBuyoutGrossProfit(scale2(totalRecovery.subtract(pano.getPurchaseTotal())));
            out.setFullTermGrossProfit(scale2(nz(pano.getExpectedGrossProfit())));
        }
        return out;
    }

    /** 正式办理提前买断：截断未到期租金计划、押金期末抵、挂载设备转「已转让」、合同转「到期转让」并写变更留痕。 */
    @Transactional
    public ContractBuyoutDtos.ExecuteResult execute(Long contractId, ContractBuyoutDtos.ExecuteRequest req) {
        Contract c = loadActive(contractId);
        LocalDate asOf = req != null && req.getBuyoutDate() != null ? req.getBuyoutDate() : LocalDate.now();
        ContractBuyoutDtos.Preview pv = preview(contractId, asOf, req == null ? null : req.getPenaltyRate());
        BigDecimal finalAmount = req != null && req.getNegotiatedAmount() != null
                ? scale2(req.getNegotiatedAmount().max(ZERO))
                : pv.getNetBuyoutPayable();

        // 截断剩余未生成单的未来计划行
        List<RentSchedule> future = scheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, contractId)
                .eq(RentSchedule::getPlanStatus, "未到期"));
        int truncated = 0;
        for (RentSchedule s : future) {
            scheduleMapper.hardDeleteById(s.getId());
            truncated++;
        }

        // 押金期末抵
        if (pv.getDepositHeld().signum() > 0) {
            DepositLedger dl = new DepositLedger();
            dl.setContractId(contractId);
            dl.setDirection("期末抵");
            dl.setAmount(pv.getDepositHeld());
            dl.setBizTime(LocalDateTime.now());
            dl.setOperatorId(UserContext.getUserId());
            dl.setRemark("提前买断押金抵扣");
            depositMapper.insert(dl);
        }

        // 挂载设备转「已转让」
        List<ContractAsset> links = contractAssetMapper.selectList(new LambdaQueryWrapper<ContractAsset>()
                .eq(ContractAsset::getContractId, contractId)
                .eq(ContractAsset::getIsDeleted, 0));
        int transferred = 0;
        for (ContractAsset ca : links) {
            StatusChangeRequest sc = new StatusChangeRequest();
            sc.setTargetStatus("已转让");
            sc.setRemark("合同 " + c.getNo() + " 提前买断过户(买断额 ¥" + finalAmount + ")");
            try {
                assetService.changeStatus(ca.getAssetId(), sc);
                transferred++;
            } catch (Exception ignored) {
                // 设备已处于终态时不阻断
            }
        }

        String before = "status=" + c.getStatus() + ",term=" + c.getTermMonths();
        c.setStatus("到期转让");
        c.setEarlyBuyoutAmount(finalAmount);
        if (req != null && req.getPenaltyRate() != null) {
            c.setEarlyBuyoutPenaltyRate(req.getPenaltyRate());
        }
        contractMapper.updateById(c);

        ContractChange ch = new ContractChange();
        ch.setContractId(contractId);
        ch.setChangeType("提前买断");
        ch.setIsReverse(0);
        ch.setBeforeJson(before);
        ch.setAfterJson("status=到期转让,buyoutAmount=" + finalAmount);
        ch.setDetail("提前买断结清 · 剩余本金 ¥" + pv.getRemainingPrincipal()
                + " + 违约金 ¥" + pv.getPenaltyAmount()
                + " − 押金 ¥" + pv.getDepositHeld()
                + " = 实付买断额 ¥" + finalAmount
                + (req != null && req.getRemark() != null ? " · " + req.getRemark() : ""));
        ch.setBizTime(LocalDateTime.now());
        ch.setOperatorId(UserContext.getUserId());
        changeMapper.insert(ch);

        auditLogService.record("合同提前买断", "contract", contractId, AuditLogService.EXECUTED,
                c.getNo() + " 买断额 ¥" + finalAmount + " · 截断 " + truncated + " 期 · 过户 " + transferred + " 套/台");

        ContractBuyoutDtos.ExecuteResult res = new ContractBuyoutDtos.ExecuteResult();
        res.setContractId(c.getId());
        res.setContractNo(c.getNo());
        res.setFinalBuyoutAmount(finalAmount);
        res.setTruncatedPeriods(truncated);
        res.setTransferredAssets(transferred);
        res.setContractStatus(c.getStatus());
        return res;
    }

    private Contract loadActive(Long id) {
        Contract c = contractMapper.selectById(id);
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "合同不存在: id=" + id);
        }
        if (!"生效".equals(c.getStatus())) {
            throw new BizException(400, "仅「生效」合同可办理提前买断，当前状态：" + c.getStatus());
        }
        return c;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
