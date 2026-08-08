package top.aole.rent.modules.contract.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.ContractChange;
import top.aole.rent.modules.contract.domain.DepositLedger;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.dto.ContractChangeRequest;
import top.aole.rent.modules.contract.dto.ContractDetailResponse;
import top.aole.rent.modules.contract.dto.ContractListItem;
import top.aole.rent.modules.contract.dto.ContractSignRequest;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractChangeMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.DepositLedgerMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 合同服务(M1-10/11/17/18)。签约(自动生成租金计划 N 期)/详情(勾稽/回款/每期构成/单笔P&L)/作废/变更/续租。
 *
 * <p><b>单一真相源(§4.24)</b>:
 * <ul>
 *   <li>{@code rent_schedule.plan_status} 只计划态;收款态归 M2 {@code rent_bill}。</li>
 *   <li>{@code contract_asset.alloc_rent} 单台分摊单一真值,Σ=contract.month_rent。</li>
 *   <li>设备状态机由 {@link AssetService} 拥有(签约转在租/作废释放),本服务不直写 asset.status。</li>
 * </ul>
 * <p>性质固定"分期收款销售",禁"融资租赁"(校验)。变更/作废走 {@code contract_change} 红冲留痕。
 * 单笔 P&L / 每期构成 敏感财务字段对 GP/LP 打码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private final ContractMapper contractMapper;
    private final ContractAssetMapper contractAssetMapper;
    private final RentScheduleMapper rentScheduleMapper;
    private final DepositLedgerMapper depositLedgerMapper;
    private final ContractChangeMapper contractChangeMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final CustomerMapper customerMapper;
    private final RuleConfigService rules;

    private static final String NATURE = "分期收款销售";
    private static final String FORBIDDEN = "融资租赁";

    // ============ 列表 ============

    public PageResult<ContractListItem> list(String status, Long customerId, String keyword, int page, int size) {
        LambdaQueryWrapper<Contract> qw = new LambdaQueryWrapper<Contract>()
                .eq(status != null && !status.isEmpty(), Contract::getStatus, status)
                .eq(customerId != null, Contract::getCustomerId, customerId)
                .and(keyword != null && !keyword.trim().isEmpty(), w -> w.like(Contract::getNo, keyword.trim()))
                .orderByDesc(Contract::getId);
        List<Contract> all = contractMapper.selectList(qw);

        List<ContractListItem> items = new ArrayList<>();
        for (Contract c : all) {
            ContractListItem it = new ContractListItem();
            it.setId(c.getId());
            it.setNo(c.getNo());
            it.setCustomerId(c.getCustomerId());
            it.setCustomerName(customerName(c.getCustomerId()));
            it.setStatus(c.getStatus());
            it.setTermMonths(c.getTermMonths());
            it.setMonthRent(c.getMonthRent());
            it.setEndTransferPrice(c.getEndTransferPrice());
            it.setAssetCount(assetLinks(c.getId()).size());
            it.setStartDate(c.getStartDate());
            it.setElapsedPeriods(elapsedPeriods(c.getId()));
            items.add(it);
        }

        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<ContractListItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    // ============ 签约(自动生成租金计划 N 期) ============

    @Transactional
    public Long sign(ContractSignRequest req) {
        validateNature(req.getNature(), req.getRemark());
        if (req.getAssets() == null || req.getAssets().isEmpty()) {
            throw new BizException(400, "至少挂 1 台设备");
        }
        Contract dup = contractMapper.selectOne(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getNo, req.getNo().trim()));
        if (dup != null) {
            throw new BizException(400, "合同编号已存在: " + req.getNo());
        }
        // 客户存在
        Customer cust = customerMapper.selectById(req.getCustomerId());
        if (cust == null) {
            throw new BizException(404, "客户不存在: id=" + req.getCustomerId());
        }
        // 设备须已存在(先签约后采购挂已建档设备)
        List<Asset> assets = new ArrayList<>();
        for (ContractSignRequest.AssetLink link : req.getAssets()) {
            Asset a = assetMapper.selectById(link.getAssetId());
            if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
                throw new BizException(404, "设备不存在: id=" + link.getAssetId());
            }
            assets.add(a);
        }

        // 租期:优先入参 → 首台品类 term_months → 36
        int term = req.getTermMonths() != null && req.getTermMonths() > 0
                ? req.getTermMonths() : lifeMonths(assets.get(0).getCategory());

        // 月租合计与单台分摊
        BigDecimal monthRent = resolveMonthRent(req);
        List<BigDecimal> allocs = resolveAllocs(req, monthRent);

        boolean activate = req.getActivate() == null || req.getActivate();
        LocalDate signDate = req.getSignDate() != null ? req.getSignDate() : LocalDate.now();
        LocalDate startDate = req.getStartDate() != null ? req.getStartDate() : signDate;

        // 押金:入参 → 月租×押金月数
        BigDecimal deposit = req.getDeposit() != null ? req.getDeposit()
                : monthRent.multiply(depositMonths()).setScale(2, RoundingMode.HALF_UP);

        Contract c = new Contract();
        c.setNo(req.getNo().trim());
        c.setCustomerId(req.getCustomerId());
        c.setTermMonths(term);
        c.setMonthRent(monthRent);
        c.setDeposit(deposit);
        c.setEndTransferPrice(req.getEndTransferPrice() != null ? req.getEndTransferPrice() : BigDecimal.ZERO);
        c.setTargetIrr(req.getTargetIrr());
        c.setNature(NATURE);
        c.setStatus(activate ? "生效" : "草稿");
        c.setSignDate(signDate);
        c.setStartDate(startDate);
        c.setRemark(req.getRemark());
        contractMapper.insert(c);

        // 挂设备(单台分摊)
        for (int i = 0; i < assets.size(); i++) {
            ContractAsset ca = new ContractAsset();
            ca.setContractId(c.getId());
            ca.setAssetId(assets.get(i).getId());
            ca.setAllocRent(allocs.get(i));
            contractAssetMapper.insert(ca);
        }

        if (activate) {
            // 自动生成租金计划 N 期
            generateSchedule(c.getId(), term, startDate, monthRent);
            // 设备转在租(状态机 owner=AssetService)
            for (Asset a : assets) {
                assetService.markRented(a.getId(), req.getCustomerId(), c.getId());
            }
            // 押金台账:收
            if (deposit.signum() > 0) {
                DepositLedger dl = new DepositLedger();
                dl.setContractId(c.getId());
                dl.setDirection("收");
                dl.setAmount(deposit);
                dl.setBizTime(LocalDateTime.now());
                dl.setOperatorId(UserContext.get() != null ? UserContext.get().getUserId() : null);
                dl.setRemark("签约收押金(月租×" + depositMonths().stripTrailingZeros().toPlainString() + "月)");
                depositLedgerMapper.insert(dl);
            }
        }
        log.info("签约: no={}, id={}, term={}, monthRent={}, assets={}, activate={}",
                c.getNo(), c.getId(), term, monthRent, assets.size(), activate);
        return c.getId();
    }

    /** 生成租金计划 N 期(period 1..N,due=起租日+period 月,均为月租)。 */
    private void generateSchedule(Long contractId, int term, LocalDate startDate, BigDecimal monthRent) {
        for (int p = 1; p <= term; p++) {
            RentSchedule rs = new RentSchedule();
            rs.setContractId(contractId);
            rs.setPeriodNo(p);
            rs.setDueDate(startDate.plusMonths(p));
            rs.setAmount(monthRent);
            rs.setPlanStatus("未到期");
            rentScheduleMapper.insert(rs);
        }
    }

    // ============ 详情 ============

    public ContractDetailResponse detail(Long id) {
        Contract c = load(id);
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());

        ContractDetailResponse r = new ContractDetailResponse();
        r.setId(c.getId());
        r.setNo(c.getNo());
        r.setCustomerId(c.getCustomerId());
        r.setCustomerName(customerName(c.getCustomerId()));
        r.setStatus(c.getStatus());
        r.setNature(c.getNature());
        r.setTermMonths(c.getTermMonths());
        r.setMonthRent(c.getMonthRent());
        r.setDeposit(c.getDeposit());
        r.setEndTransferPrice(c.getEndTransferPrice());
        r.setTargetIrr(c.getTargetIrr());
        r.setSignDate(c.getSignDate());
        r.setStartDate(c.getStartDate());
        r.setRemark(c.getRemark());
        r.setSensitiveMasked(!seeCost);

        // 挂设备
        List<ContractAsset> links = assetLinks(id);
        List<ContractDetailResponse.AssetLine> assetLines = new ArrayList<>();
        BigDecimal purchaseCost = BigDecimal.ZERO;
        for (ContractAsset ca : links) {
            Asset a = assetMapper.selectById(ca.getAssetId());
            ContractDetailResponse.AssetLine al = new ContractDetailResponse.AssetLine();
            al.setAssetId(ca.getAssetId());
            al.setAllocRent(ca.getAllocRent());
            if (a != null) {
                al.setSerialNo(a.getSerialNo());
                al.setCategory(a.getCategory());
                al.setModel(a.getModel());
                al.setAssetStatus(a.getStatus());
                if (a.getPurchasePrice() != null) {
                    purchaseCost = purchaseCost.add(a.getPurchasePrice());
                }
            }
            assetLines.add(al);
        }
        r.setAssets(assetLines);

        // 勾稽校验行
        r.setReconciliation(reconciliation(c));
        // 回款进度
        r.setRepayment(repayment(c));
        // 租金计划逐期
        r.setSchedule(scheduleLines(id));
        // 每期租金构成(敏感)
        r.setRentComposition(seeCost ? rentComposition(c, purchaseCost) : null);
        // 单笔 P&L(敏感)
        r.setPnl(seeCost ? pnl(c, purchaseCost) : null);
        // 押金台账
        r.setDepositLedger(depositLines(id));
        // 变更留痕
        r.setChanges(changeLines(id));
        return r;
    }

    private ContractDetailResponse.Reconciliation reconciliation(Contract c) {
        ContractDetailResponse.Reconciliation rc = new ContractDetailResponse.Reconciliation();
        int periods = c.getTermMonths();
        BigDecimal rentTotal = c.getMonthRent().multiply(BigDecimal.valueOf(periods)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal scheduleSum = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, c.getId()))
                .stream().map(RentSchedule::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal transfer = c.getEndTransferPrice() != null ? c.getEndTransferPrice() : BigDecimal.ZERO;
        BigDecimal customerTotal = rentTotal.add(transfer).setScale(2, RoundingMode.HALF_UP);
        rc.setPeriods(periods);
        rc.setMonthRent(c.getMonthRent());
        rc.setRentTotal(rentTotal);
        rc.setScheduleSum(scheduleSum);
        rc.setEndTransferPrice(transfer);
        rc.setCustomerTotal(customerTotal);
        rc.setDeposit(c.getDeposit());
        rc.setScheduleBalanced(scheduleSum.compareTo(rentTotal) == 0);
        rc.setBalanced(rentTotal.add(transfer).compareTo(customerTotal) == 0);
        return rc;
    }

    private ContractDetailResponse.Repayment repayment(Contract c) {
        ContractDetailResponse.Repayment rp = new ContractDetailResponse.Repayment();
        List<RentSchedule> all = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, c.getId()));
        int totalPeriods = all.size();
        int elapsed = (int) all.stream().filter(s -> !s.getDueDate().isAfter(LocalDate.now())).count();
        int billed = (int) all.stream().filter(s -> "已生成单".equals(s.getPlanStatus())).count();
        rp.setTotalPeriods(totalPeriods);
        rp.setElapsedPeriods(elapsed);
        rp.setBilledPeriods(billed);
        rp.setProgressRatio(totalPeriods > 0
                ? BigDecimal.valueOf(elapsed).divide(BigDecimal.valueOf(totalPeriods), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        return rp;
    }

    private List<ContractDetailResponse.ScheduleLine> scheduleLines(Long contractId) {
        List<RentSchedule> all = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, contractId)
                .orderByAsc(RentSchedule::getPeriodNo));
        List<ContractDetailResponse.ScheduleLine> out = new ArrayList<>();
        for (RentSchedule s : all) {
            ContractDetailResponse.ScheduleLine sl = new ContractDetailResponse.ScheduleLine();
            sl.setPeriodNo(s.getPeriodNo());
            sl.setDueDate(s.getDueDate());
            sl.setAmount(s.getAmount());
            sl.setPlanStatus(s.getPlanStatus());
            sl.setRentBillId(s.getRentBillId());
            sl.setDueState(s.getDueDate().isAfter(LocalDate.now()) ? "未到期" : "已到期");
            out.add(sl);
        }
        return out;
    }

    /** 每期租金构成:本金摊 + 资金成本 + 残值预留 + 差价分摊(= 月租)。 */
    private ContractDetailResponse.RentComposition rentComposition(Contract c, BigDecimal purchaseCost) {
        ContractDetailResponse.RentComposition rcp = new ContractDetailResponse.RentComposition();
        int term = c.getTermMonths();
        BigDecimal monthRent = c.getMonthRent();
        BigDecimal termBd = BigDecimal.valueOf(term);
        BigDecimal principal = purchaseCost.divide(termBd, 2, RoundingMode.HALF_UP);
        BigDecimal financingCost = safeValue("financing_cost", "");
        BigDecimal capitalCost = financingCost != null
                ? purchaseCost.multiply(financingCost).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal residualTotal = residualTotal(c.getId());
        BigDecimal residualReserve = residualTotal.divide(termBd, 2, RoundingMode.HALF_UP);
        BigDecimal margin = monthRent.subtract(principal).subtract(capitalCost).subtract(residualReserve)
                .setScale(2, RoundingMode.HALF_UP);
        rcp.setMonthRent(monthRent);
        rcp.setPrincipal(principal);
        rcp.setCapitalCost(capitalCost);
        rcp.setResidualReserve(residualReserve);
        rcp.setMargin(margin);
        rcp.setNote("本金摊+资金成本+残值预留+差价分摊=月租(经营口径简化·M2/M3 精确化)");
        return rcp;
    }

    /** 单笔 P&L:收租总额+转让价-集采-资金成本-坏账拨备=税后净利(经营口径简化)。 */
    private ContractDetailResponse.Pnl pnl(Contract c, BigDecimal purchaseCost) {
        ContractDetailResponse.Pnl p = new ContractDetailResponse.Pnl();
        int term = c.getTermMonths();
        BigDecimal rentTotal = c.getMonthRent().multiply(BigDecimal.valueOf(term)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal transfer = c.getEndTransferPrice() != null ? c.getEndTransferPrice() : BigDecimal.ZERO;
        BigDecimal financingCost = safeValue("financing_cost", "");
        BigDecimal capitalCost = financingCost != null
                ? purchaseCost.multiply(financingCost).multiply(BigDecimal.valueOf(term))
                    .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal badDebtRate = safeValue("contract_bad_debt_rate", "");
        BigDecimal badDebt = badDebtRate != null
                ? rentTotal.multiply(badDebtRate).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal net = rentTotal.add(transfer).subtract(purchaseCost).subtract(capitalCost).subtract(badDebt)
                .setScale(2, RoundingMode.HALF_UP);
        p.setRentTotal(rentTotal);
        p.setTransferPrice(transfer);
        p.setPurchaseCost(purchaseCost.setScale(2, RoundingMode.HALF_UP));
        p.setCapitalCost(capitalCost);
        p.setBadDebtReserve(badDebt);
        p.setNetProfit(net);
        p.setNetMargin(rentTotal.signum() > 0
                ? net.divide(rentTotal, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        p.setNote("经营口径简化:未含税与运维/管理费分摊(M2 凭证/分配精确化)");
        return p;
    }

    // ============ 作废(限未采购·整份红冲) ============

    @Transactional
    public void voidContract(Long id, ContractChangeRequest req) {
        Contract c = load(id);
        if ("已作废".equals(c.getStatus())) {
            throw new BizException(400, "合同已作废");
        }
        if ("关闭".equals(c.getStatus()) || "到期转让".equals(c.getStatus())) {
            throw new BizException(400, "合同已" + c.getStatus() + ",不可作废");
        }
        validateNature(null, req == null ? null : req.getDetail());
        // 限未采购:任一挂载设备已回填 purchase_in_id → 拒绝(采购模块 M1-12 上线后生效)
        List<ContractAsset> links = assetLinks(id);
        for (ContractAsset ca : links) {
            Asset a = assetMapper.selectById(ca.getAssetId());
            if (a != null && a.getPurchaseInId() != null) {
                throw new BizException(400, "设备 " + a.getSerialNo() + " 已采购入库,不可作废(整份红冲仅限未采购)");
            }
        }
        String before = "status=" + c.getStatus() + ",term=" + c.getTermMonths() + ",monthRent=" + c.getMonthRent();
        // 整份红冲:计划逻辑删、押金红字退、设备释放
        for (RentSchedule s : rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, id))) {
            rentScheduleMapper.deleteById(s.getId());
        }
        BigDecimal collected = depositLines(id).stream()
                .map(d -> "收".equals(d.getDirection()) ? d.getAmount() : d.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (collected.signum() > 0) {
            DepositLedger refund = new DepositLedger();
            refund.setContractId(id);
            refund.setDirection("退");
            refund.setAmount(collected);
            refund.setBizTime(LocalDateTime.now());
            refund.setOperatorId(UserContext.get() != null ? UserContext.get().getUserId() : null);
            refund.setRemark("作废整份红冲退押金");
            depositLedgerMapper.insert(refund);
        }
        for (ContractAsset ca : links) {
            assetService.releaseOnVoid(ca.getAssetId(), id);
        }
        c.setStatus("已作废");
        contractMapper.updateById(c);
        recordChange(id, "作废", true, before, "status=已作废",
                req == null ? "整份红冲作废" : (req.getDetail() != null ? req.getDetail() : "整份红冲作废"));
        log.info("合同作废: id={}, by={}", id, UserContext.getUserId());
    }

    // ============ 续租(追加期数,重算计划) ============

    @Transactional
    public void renew(Long id, ContractChangeRequest req) {
        Contract c = requireActive(id);
        if (req == null || req.getRenewMonths() == null || req.getRenewMonths() <= 0) {
            throw new BizException(400, "续租期数(renewMonths)必填且 > 0");
        }
        int add = req.getRenewMonths();
        BigDecimal newRent = req.getNewMonthRent() != null ? req.getNewMonthRent() : c.getMonthRent();
        String before = "term=" + c.getTermMonths() + ",monthRent=" + c.getMonthRent();

        // 现有最大期次与到期日
        List<RentSchedule> existing = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, id).orderByAsc(RentSchedule::getPeriodNo));
        int maxPeriod = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getPeriodNo();
        LocalDate lastDue = existing.isEmpty()
                ? (c.getStartDate() != null ? c.getStartDate() : LocalDate.now())
                : existing.get(existing.size() - 1).getDueDate();

        for (int i = 1; i <= add; i++) {
            RentSchedule rs = new RentSchedule();
            rs.setContractId(id);
            rs.setPeriodNo(maxPeriod + i);
            rs.setDueDate(lastDue.plusMonths(i));
            rs.setAmount(newRent);
            rs.setPlanStatus("未到期");
            rentScheduleMapper.insert(rs);
        }
        c.setTermMonths(c.getTermMonths() + add);
        c.setMonthRent(newRent);
        contractMapper.updateById(c);
        recordChange(id, "续租", false, before,
                "term=" + c.getTermMonths() + ",monthRent=" + newRent,
                req.getDetail() != null ? req.getDetail() : ("续租 " + add + " 期"));
        log.info("合同续租: id={}, +{}期, newRent={}", id, add, newRent);
    }

    // ============ 变更/提前结清(重算剩余期) ============

    @Transactional
    public void change(Long id, ContractChangeRequest req) {
        Contract c = requireActive(id);
        validateNature(null, req == null ? null : req.getDetail());
        LocalDate settle = req != null && req.getSettleDate() != null ? req.getSettleDate() : LocalDate.now();
        String before = "term=" + c.getTermMonths() + ",status=" + c.getStatus();

        // 提前结清:截断结清日之后仍"未到期"的计划行(逻辑删),合同关闭
        List<RentSchedule> future = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, id)
                .gt(RentSchedule::getDueDate, settle)
                .eq(RentSchedule::getPlanStatus, "未到期"));
        int truncated = 0;
        for (RentSchedule s : future) {
            rentScheduleMapper.deleteById(s.getId());
            truncated++;
        }
        int remaining = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, id)).size();
        c.setTermMonths(remaining);
        c.setStatus("关闭");
        contractMapper.updateById(c);
        // 押金期末抵
        BigDecimal collected = depositLines(id).stream()
                .map(d -> "收".equals(d.getDirection()) ? d.getAmount()
                        : ("退".equals(d.getDirection()) ? d.getAmount().negate() : d.getAmount().negate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (collected.signum() > 0) {
            DepositLedger offset = new DepositLedger();
            offset.setContractId(id);
            offset.setDirection("期末抵");
            offset.setAmount(collected);
            offset.setBizTime(LocalDateTime.now());
            offset.setOperatorId(UserContext.get() != null ? UserContext.get().getUserId() : null);
            offset.setRemark("提前结清·押金期末抵");
            depositLedgerMapper.insert(offset);
        }
        recordChange(id, "提前结清", false, before,
                "term=" + remaining + ",status=关闭",
                (req != null && req.getDetail() != null ? req.getDetail() : "提前结清") + "·截断" + truncated + "期");
        log.info("合同提前结清: id={}, 截断{}期, 剩{}期", id, truncated, remaining);
    }

    // ============ 工具 ============

    private void validateNature(String nature, String freeText) {
        if (nature != null && nature.contains(FORBIDDEN)) {
            throw new BizException(400, "合同性质禁用「融资租赁」,本平台固定为「分期收款销售」");
        }
        if (freeText != null && freeText.contains(FORBIDDEN)) {
            throw new BizException(400, "文本禁出现「融资租赁」字样(合规:本平台为分期收款销售)");
        }
    }

    private BigDecimal resolveMonthRent(ContractSignRequest req) {
        if (req.getMonthRent() != null) {
            return req.getMonthRent().setScale(2, RoundingMode.HALF_UP);
        }
        // 缺月租 → Σ 单台分摊(要求每台都给了 allocRent)
        BigDecimal sum = BigDecimal.ZERO;
        for (ContractSignRequest.AssetLink link : req.getAssets()) {
            if (link.getAllocRent() == null) {
                throw new BizException(400, "未给合同月租时,每台设备须填单台分摊 allocRent");
            }
            sum = sum.add(link.getAllocRent());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** 单台分摊:优先入参;否则月租均摊(末台补差,Σ 精确等于月租)。 */
    private List<BigDecimal> resolveAllocs(ContractSignRequest req, BigDecimal monthRent) {
        int n = req.getAssets().size();
        boolean allGiven = req.getAssets().stream().allMatch(l -> l.getAllocRent() != null);
        List<BigDecimal> out = new ArrayList<>();
        if (allGiven) {
            BigDecimal sum = BigDecimal.ZERO;
            for (ContractSignRequest.AssetLink l : req.getAssets()) {
                BigDecimal v = l.getAllocRent().setScale(2, RoundingMode.HALF_UP);
                out.add(v);
                sum = sum.add(v);
            }
            if (sum.compareTo(monthRent) != 0) {
                throw new BizException(400, "单台分摊合计(" + sum + ")≠合同月租(" + monthRent + ")");
            }
            return out;
        }
        // 均摊
        BigDecimal each = monthRent.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        BigDecimal acc = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            if (i < n - 1) {
                out.add(each);
                acc = acc.add(each);
            } else {
                out.add(monthRent.subtract(acc).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return out;
    }

    private void recordChange(Long contractId, String type, boolean reverse,
                              String before, String after, String detail) {
        ContractChange ch = new ContractChange();
        ch.setContractId(contractId);
        ch.setChangeType(type);
        ch.setIsReverse(reverse ? 1 : 0);
        ch.setBeforeJson(before);
        ch.setAfterJson(after);
        ch.setDetail(detail);
        ch.setOperatorId(UserContext.get() != null ? UserContext.get().getUserId() : null);
        ch.setBizTime(LocalDateTime.now());
        contractChangeMapper.insert(ch);
    }

    private List<ContractAsset> assetLinks(Long contractId) {
        return contractAssetMapper.selectList(new LambdaQueryWrapper<ContractAsset>()
                .eq(ContractAsset::getContractId, contractId).orderByAsc(ContractAsset::getId));
    }

    private List<ContractDetailResponse.DepositLine> depositLines(Long contractId) {
        return depositLedgerMapper.selectList(new LambdaQueryWrapper<DepositLedger>()
                .eq(DepositLedger::getContractId, contractId).orderByAsc(DepositLedger::getId))
                .stream().map(d -> {
                    ContractDetailResponse.DepositLine dl = new ContractDetailResponse.DepositLine();
                    dl.setDirection(d.getDirection());
                    dl.setAmount(d.getAmount());
                    dl.setBizTime(d.getBizTime());
                    dl.setRemark(d.getRemark());
                    return dl;
                }).collect(Collectors.toList());
    }

    private List<ContractDetailResponse.ChangeLine> changeLines(Long contractId) {
        return contractChangeMapper.selectList(new LambdaQueryWrapper<ContractChange>()
                .eq(ContractChange::getContractId, contractId).orderByDesc(ContractChange::getId))
                .stream().map(ch -> {
                    ContractDetailResponse.ChangeLine cl = new ContractDetailResponse.ChangeLine();
                    cl.setChangeType(ch.getChangeType());
                    cl.setIsReverse(ch.getIsReverse() != null && ch.getIsReverse() == 1);
                    cl.setDetail(ch.getDetail());
                    cl.setBizTime(ch.getBizTime());
                    cl.setOperatorName(userName(ch.getOperatorId()));
                    return cl;
                }).collect(Collectors.toList());
    }

    /** Σ 挂载设备整机残值(市场价×品类转让率)。 */
    private BigDecimal residualTotal(Long contractId) {
        BigDecimal total = BigDecimal.ZERO;
        for (ContractAsset ca : assetLinks(contractId)) {
            Asset a = assetMapper.selectById(ca.getAssetId());
            if (a != null && a.getMarketPrice() != null) {
                BigDecimal rate = safeValue("transfer_rate", a.getCategory());
                if (rate != null) {
                    total = total.add(a.getMarketPrice().multiply(rate));
                }
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private int elapsedPeriods(Long contractId) {
        return (int) rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, contractId))
                .stream().filter(s -> !s.getDueDate().isAfter(LocalDate.now())).count();
    }

    private Contract load(Long id) {
        Contract c = contractMapper.selectById(id);
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "合同不存在: id=" + id);
        }
        return c;
    }

    private Contract requireActive(Long id) {
        Contract c = load(id);
        if (!"生效".equals(c.getStatus())) {
            throw new BizException(400, "合同状态为" + c.getStatus() + ",仅生效合同可变更/续租/结清");
        }
        return c;
    }

    private int lifeMonths(String category) {
        BigDecimal v = safeValue("term_months", category);
        return v != null && v.intValue() > 0 ? v.intValue() : 36;
    }

    private BigDecimal depositMonths() {
        BigDecimal v = safeValue("deposit_months", "");
        return v != null ? v : BigDecimal.valueOf(2);
    }

    private BigDecimal safeValue(String ruleKey, String scopeKey) {
        try {
            return rules.getValue(ruleKey, scopeKey, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }

    private String customerName(Long customerId) {
        if (customerId == null) {
            return null;
        }
        Customer c = customerMapper.selectById(customerId);
        return c != null ? c.getName() : ("客户#" + customerId);
    }

    private static final Map<Long, String> SEED_NAMES = new HashMap<>();
    static {
        SEED_NAMES.put(1001L, "老板");
        SEED_NAMES.put(1005L, "财务");
        SEED_NAMES.put(1006L, "供应链");
        SEED_NAMES.put(1007L, "业务");
        SEED_NAMES.put(1004L, "李工");
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        return SEED_NAMES.getOrDefault(userId, "用户#" + userId);
    }
}
