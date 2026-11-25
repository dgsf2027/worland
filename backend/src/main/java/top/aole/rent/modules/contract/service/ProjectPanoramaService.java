package top.aole.rent.modules.contract.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.DepositLedger;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.DepositLedgerMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.domain.PurchaseInvoice;
import top.aole.rent.modules.pcontract.domain.SupplierPayment;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseInvoiceMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 项目真实利润全景服务（聚合 采购合同/采购单/应付/进项票 ↔ 租赁合同/租金计划/收租单/销项票）。
 *
 * <p>遵循 {@link DataScope#canSeeCost(String)}：GP/LP 投资人只读角色不返回采购成本/利润/未付明细。
 */
@Service
@RequiredArgsConstructor
public class ProjectPanoramaService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal SIMPLE_VAT_THRESHOLD = new BigDecimal("0.05");

    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final RentScheduleMapper scheduleMapper;
    private final RentBillMapper billMapper;
    private final DepositLedgerMapper depositMapper;
    private final PurchaseContractMapper pcMapper;
    private final PurchaseContractTermMapper pcTermMapper;
    private final SupplierPaymentMapper spMapper;
    private final PurchaseInvoiceMapper piMapper;
    private final PurchaseInMapper purchaseInMapper;
    private final PayableMapper payableMapper;
    private final SupplierMapper supplierMapper;
    private final ContractAssetMapper contractAssetMapper;
    private final AssetMapper assetMapper;

    /** 单项目全景视图。 */
    public ProjectPanoramaDtos.Panorama panorama(Long contractId) {
        Contract c = contractMapper.selectById(contractId);
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "合同不存在: id=" + contractId);
        }
        Customer cust = c.getCustomerId() == null ? null : customerMapper.selectById(c.getCustomerId());
        String role = UserContext.getRole();
        if (DataScope.isOwnerScoped(role)) {
            Long uid = UserContext.getUserId();
            if (cust != null && cust.getOwnerUser() != null && !Objects.equals(cust.getOwnerUser(), uid)) {
                throw new BizException(403, "仅可查看本人负责或公海客户的项目全景");
            }
        }
        boolean seeCost = DataScope.canSeeCost(role);
        return buildPanorama(c, cust, seeCost, true);
    }

    /** 工作台项目真实利润总览列表（按合同汇总）。 */
    public ProjectPanoramaDtos.Overview overview() {
        String role = UserContext.getRole();
        boolean seeCost = DataScope.canSeeCost(role);
        boolean ownerScoped = DataScope.isOwnerScoped(role);
        Long uid = UserContext.getUserId();

        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0)
                .ne(Contract::getStatus, "已作废")
                .orderByDesc(Contract::getId));

        Map<Long, Customer> custMap = new HashMap<>();
        if (!contracts.isEmpty()) {
            Set<Long> custIds = new HashSet<>();
            for (Contract c : contracts) {
                if (c.getCustomerId() != null) {
                    custIds.add(c.getCustomerId());
                }
            }
            if (!custIds.isEmpty()) {
                for (Customer cu : customerMapper.selectBatchIds(custIds)) {
                    custMap.put(cu.getId(), cu);
                }
            }
        }

        ProjectPanoramaDtos.Overview ov = new ProjectPanoramaDtos.Overview();
        ov.setCostVisible(seeCost);

        BigDecimal totalPlanned = ZERO;
        BigDecimal totalReceived = ZERO;
        BigDecimal totalPurchase = ZERO;
        BigDecimal totalPaid = ZERO;
        BigDecimal totalUnpaid = ZERO;
        BigDecimal totalGross = ZERO;
        BigDecimal totalAfterTax = ZERO;
        BigDecimal totalNetCash = ZERO;

        for (Contract c : contracts) {
            Customer cust = c.getCustomerId() == null ? null : custMap.get(c.getCustomerId());
            if (ownerScoped && cust != null && cust.getOwnerUser() != null && !Objects.equals(cust.getOwnerUser(), uid)) {
                continue;
            }
            ProjectPanoramaDtos.Panorama p = buildPanorama(c, cust, seeCost, false);
            ProjectPanoramaDtos.Brief b = toBrief(p);
            ov.getItems().add(b);

            totalPlanned = totalPlanned.add(nz(p.getPlannedCustomerTotal()));
            totalReceived = totalReceived.add(nz(p.getReceivedRent()));
            if (seeCost) {
                totalPurchase = totalPurchase.add(nz(p.getPurchaseTotal()));
                totalPaid = totalPaid.add(nz(p.getPurchasePaid()));
                totalUnpaid = totalUnpaid.add(nz(p.getPurchaseUnpaid()));
                totalGross = totalGross.add(nz(p.getExpectedGrossProfit()));
                totalAfterTax = totalAfterTax.add(nz(p.getEstimatedAfterTaxProfit()));
                totalNetCash = totalNetCash.add(nz(p.getActualNetCashflow()));
            }
        }

        ov.setProjectCount(ov.getItems().size());
        ov.setTotalPlannedCustomer(scale2(totalPlanned));
        ov.setTotalReceivedRent(scale2(totalReceived));
        if (seeCost) {
            ov.setTotalPurchase(scale2(totalPurchase));
            ov.setTotalPurchasePaid(scale2(totalPaid));
            ov.setTotalPurchaseUnpaid(scale2(totalUnpaid));
            ov.setTotalExpectedGrossProfit(scale2(totalGross));
            ov.setTotalEstimatedAfterTaxProfit(scale2(totalAfterTax));
            ov.setTotalActualNetCashflow(scale2(totalNetCash));
        }
        return ov;
    }

    private ProjectPanoramaDtos.Panorama buildPanorama(Contract c, Customer cust, boolean seeCost, boolean includeMonthly) {
        ProjectPanoramaDtos.Panorama out = new ProjectPanoramaDtos.Panorama();
        out.setContractId(c.getId());
        out.setContractNo(c.getNo());
        out.setCustomerId(c.getCustomerId());
        out.setCustomerName(cust == null ? ("客户#" + c.getCustomerId()) : cust.getName());
        out.setNature(c.getNature());
        out.setStatus(c.getStatus());
        out.setSignDate(c.getSignDate());
        out.setStartDate(c.getStartDate());
        out.setTermMonths(c.getTermMonths());
        out.setMonthRent(scale2(nz(c.getMonthRent())));
        out.setLeaseAnnualRate(c.getLeaseAnnualRate());
        out.setEndTransferPrice(scale2(nz(c.getEndTransferPrice())));
        out.setDeposit(scale2(nz(c.getDeposit())));
        out.setInvoiceTaxRate(c.getInvoiceTaxRate());
        out.setInvoiceDeadlineDays(c.getInvoiceDeadlineDays());
        out.setCostVisible(seeCost);

        // ---- 1. 租金计划 & 收租单 ----
        List<RentSchedule> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getContractId, c.getId())
                .eq(RentSchedule::getIsDeleted, 0)
                .orderByAsc(RentSchedule::getPeriodNo));
        List<RentBill> bills = billMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getContractId, c.getId())
                .eq(RentBill::getIsDeleted, 0)
                .orderByAsc(RentBill::getPeriodNo).orderByAsc(RentBill::getId));

        BigDecimal plannedRent = ZERO;
        if (!schedules.isEmpty()) {
            for (RentSchedule s : schedules) {
                plannedRent = plannedRent.add(nz(s.getAmount()));
            }
            LocalDate lastDue = schedules.get(schedules.size() - 1).getDueDate();
            if (c.getStartDate() != null && c.getTermMonths() != null && c.getTermMonths() > 0) {
                out.setEndDate(c.getStartDate().plusMonths(c.getTermMonths()).minusDays(1));
            } else {
                out.setEndDate(lastDue);
            }
        } else {
            plannedRent = ContractReceipts.totalRent(c.getMonthRent(), c.getTermMonths());
            if (c.getStartDate() != null && c.getTermMonths() != null && c.getTermMonths() > 0) {
                out.setEndDate(c.getStartDate().plusMonths(c.getTermMonths()).minusDays(1));
            }
        }
        plannedRent = scale2(plannedRent);
        BigDecimal plannedCustomerTotal = scale2(plannedRent.add(nz(c.getEndTransferPrice())));
        out.setPlannedTotalRent(plannedRent);
        out.setPlannedCustomerTotal(plannedCustomerTotal);
        out.setTotalPeriods(!schedules.isEmpty() ? schedules.size() : (c.getTermMonths() == null ? 0 : c.getTermMonths()));

        Map<Long, BigDecimal> receiptMap = ContractReceipts.totals(bills);
        BigDecimal receivedRent = scale2(receiptMap.getOrDefault(c.getId(), ZERO));
        out.setReceivedRent(receivedRent);
        out.setReceivablePending(scale2(plannedRent.subtract(receivedRent).max(ZERO)));

        LocalDate today = LocalDate.now();
        BigDecimal overdue = ZERO;
        int settledPeriods = 0;
        int maxSettledPeriod = 0;
        int pendingInvoiceCount = 0;
        int overdueInvoiceCount = 0;
        BigDecimal invoicedRentAmount = ZERO;

        Map<Integer, RentBill> normalBillByPeriod = new HashMap<>();
        for (RentBill b : bills) {
            if (!"正常".equals(b.getBillKind()) || "红冲".equals(b.getStatus())) {
                continue;
            }
            if (b.getPeriodNo() != null) {
                normalBillByPeriod.put(b.getPeriodNo(), b);
            }
            if ("已核销".equals(b.getStatus())) {
                settledPeriods++;
                if (b.getPeriodNo() != null && b.getPeriodNo() > maxSettledPeriod) {
                    maxSettledPeriod = b.getPeriodNo();
                }
            } else if ("逾期".equals(b.getStatus())
                    || ("部分已收".equals(b.getStatus()) && b.getDueDate() != null && b.getDueDate().isBefore(today))) {
                BigDecimal rem = nz(b.getAmount()).subtract(nz(b.getReceivedAmount())).max(ZERO);
                overdue = overdue.add(rem);
            }
            if ("待开票".equals(b.getInvoiceStatus())) {
                pendingInvoiceCount++;
                if (b.getInvoiceDueDate() != null && b.getInvoiceDueDate().isBefore(today)) {
                    overdueInvoiceCount++;
                }
            } else if ("已开票".equals(b.getInvoiceStatus())) {
                invoicedRentAmount = invoicedRentAmount.add(nz(b.getInvoiceAmount() != null ? b.getInvoiceAmount() : b.getReceivedAmount()));
            }
        }
        out.setOverdueAmount(scale2(overdue));
        out.setSettledPeriods(settledPeriods);
        out.setPendingInvoiceCount(pendingInvoiceCount);
        out.setOverdueInvoiceCount(overdueInvoiceCount);
        out.setInvoicedRentAmount(scale2(invoicedRentAmount));

        // 押金余额
        BigDecimal depositHeld = ZERO;
        List<DepositLedger> ledgers = depositMapper.selectList(new LambdaQueryWrapper<DepositLedger>()
                .eq(DepositLedger::getContractId, c.getId())
                .eq(DepositLedger::getIsDeleted, 0));
        for (DepositLedger dl : ledgers) {
            if ("收".equals(dl.getDirection())) {
                depositHeld = depositHeld.add(nz(dl.getAmount()));
            } else if ("退".equals(dl.getDirection()) || "期末抵".equals(dl.getDirection())) {
                depositHeld = depositHeld.subtract(nz(dl.getAmount()));
            }
        }
        out.setDepositHeld(scale2(depositHeld.max(ZERO)));

        // ---- 2. 采购投入侧（汇总关联采购合同 + 未挂采购合同的独立采购单，不重复计数） ----
        List<PurchaseContract> pcs = pcMapper.selectList(new LambdaQueryWrapper<PurchaseContract>()
                .eq(PurchaseContract::getRentContractId, c.getId())
                .ne(PurchaseContract::getStatus, "作废")
                .orderByAsc(PurchaseContract::getId));
        Set<Long> pcIds = new HashSet<>();
        for (PurchaseContract pc : pcs) {
            pcIds.add(pc.getId());
        }

        List<PurchaseIn> linkedPos = purchaseInMapper.selectList(new LambdaQueryWrapper<PurchaseIn>()
                .eq(PurchaseIn::getIsDeleted, 0)
                .ne(PurchaseIn::getStatus, "已红冲")
                .and(w -> {
                    w.eq(PurchaseIn::getContractId, c.getId());
                    if (!pcIds.isEmpty()) {
                        w.or().in(PurchaseIn::getPcId, pcIds);
                    }
                })
                .orderByAsc(PurchaseIn::getId));

        // 供应商名称缓存
        Set<Long> supplierIds = new HashSet<>();
        pcs.forEach(pc -> {
            if (pc.getSupplierId() != null) {
                supplierIds.add(pc.getSupplierId());
            }
        });
        linkedPos.forEach(po -> {
            if (po.getSupplierId() != null) {
                supplierIds.add(po.getSupplierId());
            }
        });
        Map<Long, String> supplierNameMap = new HashMap<>();
        if (!supplierIds.isEmpty()) {
            for (Supplier s : supplierMapper.selectBatchIds(supplierIds)) {
                supplierNameMap.put(s.getId(), s.getName());
            }
        }

        BigDecimal purchaseTotal = ZERO;
        BigDecimal purchasePaid = ZERO;
        BigDecimal inputInvoiceTotal = ZERO;
        BigDecimal inputInvoiceTax = ZERO;
        BigDecimal estimatedInputVatFromPc = ZERO;
        LocalDate nextPayableDate = null;
        BigDecimal nextPayableAmount = null;
        String nextPayableStage = null;
        LocalDate latestWarrantyEnd = null;

        // 用于逐月现金流
        TreeMap<String, BigDecimal[]> monthFlow = new TreeMap<>();
        boolean hasExplicitPurchaseOutflow = false;

        for (PurchaseContract pc : pcs) {
            BigDecimal pcTotal = nz(pc.getTotalAmount());
            purchaseTotal = purchaseTotal.add(pcTotal);
            if (pc.getTaxRate() != null && pc.getTaxRate().signum() > 0 && pcTotal.signum() > 0) {
                BigDecimal vat = pcTotal.multiply(pc.getTaxRate())
                        .divide(BigDecimal.ONE.add(pc.getTaxRate()), 2, RoundingMode.HALF_UP);
                estimatedInputVatFromPc = estimatedInputVatFromPc.add(vat);
            }
            if (pc.getAcceptanceDate() != null && pc.getWarrantyYears() != null && pc.getWarrantyYears() > 0) {
                LocalDate wEnd = pc.getAcceptanceDate().plusYears(pc.getWarrantyYears());
                if (latestWarrantyEnd == null || wEnd.isAfter(latestWarrantyEnd)) {
                    latestWarrantyEnd = wEnd;
                }
            }

            List<SupplierPayment> sps = spMapper.selectList(new LambdaQueryWrapper<SupplierPayment>()
                    .eq(SupplierPayment::getPcId, pc.getId())
                    .eq(SupplierPayment::getStatus, "有效")
                    .orderByAsc(SupplierPayment::getPaidDate));
            BigDecimal pcPaid = ZERO;
            for (SupplierPayment sp : sps) {
                BigDecimal amt = nz(sp.getAmount());
                pcPaid = pcPaid.add(amt);
                if (amt.signum() > 0) {
                    LocalDate d = sp.getPaidDate() != null ? sp.getPaidDate() : pc.getSignedDate();
                    addOutflow(monthFlow, d, amt);
                    hasExplicitPurchaseOutflow = true;
                }
            }
            purchasePaid = purchasePaid.add(pcPaid);

            List<PurchaseInvoice> invs = piMapper.selectList(new LambdaQueryWrapper<PurchaseInvoice>()
                    .eq(PurchaseInvoice::getPcId, pc.getId())
                    .eq(PurchaseInvoice::getStatus, "有效"));
            for (PurchaseInvoice inv : invs) {
                inputInvoiceTotal = inputInvoiceTotal.add(nz(inv.getTotalAmount()));
                inputInvoiceTax = inputInvoiceTax.add(nz(inv.getTaxAmount()));
            }

            // 采购合同付款节点 → 推算剩余未付计划与下一笔应付
            List<PurchaseContractTerm> terms = pcTermMapper.selectList(new LambdaQueryWrapper<PurchaseContractTerm>()
                    .eq(PurchaseContractTerm::getPcId, pc.getId())
                    .orderByAsc(PurchaseContractTerm::getSortNo).orderByAsc(PurchaseContractTerm::getId));
            BigDecimal paidRemain = pcPaid;
            for (PurchaseContractTerm term : terms) {
                BigDecimal tAmt = nz(term.getAmount());
                if (tAmt.signum() <= 0 && term.getRatio() != null && pcTotal.signum() > 0) {
                    tAmt = pcTotal.multiply(term.getRatio()).setScale(2, RoundingMode.HALF_UP);
                }
                BigDecimal covered = paidRemain.min(tAmt);
                paidRemain = paidRemain.subtract(covered);
                BigDecimal unpaid = tAmt.subtract(covered);
                if (unpaid.signum() > 0) {
                    LocalDate d = term.getDueDate();
                    if (d == null) {
                        LocalDate base = pc.getAuditPassDate() != null ? pc.getAuditPassDate()
                                : (pc.getAcceptanceDate() != null ? pc.getAcceptanceDate()
                                : (pc.getSignedDate() != null ? pc.getSignedDate() : c.getStartDate()));
                        d = PaymentDueDateCalculator.computeDueDate(base, term.getDueRule(), term.getDueDays(), term.getDueDayOfMonth());
                    }
                    if (d != null && (nextPayableDate == null || d.isBefore(nextPayableDate))) {
                        nextPayableDate = d;
                        nextPayableAmount = unpaid;
                        nextPayableStage = pc.getContractNo() + " · " + term.getStageName();
                    }
                    addOutflow(monthFlow, d != null ? d : c.getStartDate(), unpaid);
                    hasExplicitPurchaseOutflow = true;
                }
            }
            // 若采购合同无节点且还有未付,挂在合同签约日/起租日
            if (terms.isEmpty() && pcTotal.subtract(pcPaid).signum() > 0) {
                addOutflow(monthFlow, pc.getSignedDate() != null ? pc.getSignedDate() : c.getStartDate(),
                        pcTotal.subtract(pcPaid));
                hasExplicitPurchaseOutflow = true;
            }

            if (seeCost) {
                ProjectPanoramaDtos.LinkedPurchase lp = new ProjectPanoramaDtos.LinkedPurchase();
                lp.setKind("采购合同");
                lp.setId(pc.getId());
                lp.setNo(pc.getContractNo());
                lp.setSupplierName(supplierNameMap.getOrDefault(pc.getSupplierId(), "供应商#" + pc.getSupplierId()));
                lp.setTotalAmount(scale2(pcTotal));
                lp.setPaidAmount(scale2(pcPaid));
                lp.setUnpaidAmount(scale2(pcTotal.subtract(pcPaid).max(ZERO)));
                lp.setStatus(pc.getStatus());
                lp.setAcceptanceDate(pc.getAcceptanceDate());
                if (pc.getAcceptanceDate() != null && pc.getWarrantyYears() != null && pc.getWarrantyYears() > 0) {
                    lp.setWarrantyEndDate(pc.getAcceptanceDate().plusYears(pc.getWarrantyYears()));
                }
                out.getLinkedPurchases().add(lp);
            }
        }

        // 采购单：质保到期日始终汇总；仅未挂采购合同的独立采购单计入总成本/应付（避免与采购合同重复统计）
        for (PurchaseIn po : linkedPos) {
            if (po.getWarrantyEndDate() != null && (latestWarrantyEnd == null || po.getWarrantyEndDate().isAfter(latestWarrantyEnd))) {
                latestWarrantyEnd = po.getWarrantyEndDate();
            } else if (po.getAcceptanceDate() != null && po.getWarrantyYears() != null && po.getWarrantyYears() > 0) {
                LocalDate wEnd = po.getAcceptanceDate().plusYears(po.getWarrantyYears());
                if (latestWarrantyEnd == null || wEnd.isAfter(latestWarrantyEnd)) {
                    latestWarrantyEnd = wEnd;
                }
            }

            boolean standalone = po.getPcId() == null || !pcIds.contains(po.getPcId());
            if (!standalone) {
                continue;
            }
            BigDecimal poTotal = nz(po.getTotalAmount());
            purchaseTotal = purchaseTotal.add(poTotal);

            List<Payable> pays = payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                    .eq(Payable::getPurchaseInId, po.getId())
                    .eq(Payable::getIsDeleted, 0)
                    .ne(Payable::getStatus, "红冲")
                    .orderByAsc(Payable::getDueDate).orderByAsc(Payable::getId));
            BigDecimal poPaid = ZERO;
            for (Payable pay : pays) {
                BigDecimal amt = nz(pay.getAmount());
                if ("已付".equals(pay.getStatus())) {
                    poPaid = poPaid.add(amt);
                    addOutflow(monthFlow, pay.getPaidDate() != null ? pay.getPaidDate() : pay.getDueDate(), amt);
                    hasExplicitPurchaseOutflow = true;
                } else if ("待付".equals(pay.getStatus()) && amt.signum() > 0) {
                    if (pay.getDueDate() != null && (nextPayableDate == null || pay.getDueDate().isBefore(nextPayableDate))) {
                        nextPayableDate = pay.getDueDate();
                        nextPayableAmount = amt;
                        nextPayableStage = po.getNo() + " · " + pay.getStage();
                    }
                    addOutflow(monthFlow, pay.getDueDate() != null ? pay.getDueDate() : po.getOrderDate(), amt);
                    hasExplicitPurchaseOutflow = true;
                }
            }
            purchasePaid = purchasePaid.add(poPaid);

            if (seeCost) {
                ProjectPanoramaDtos.LinkedPurchase lp = new ProjectPanoramaDtos.LinkedPurchase();
                lp.setKind("采购单");
                lp.setId(po.getId());
                lp.setNo(po.getNo());
                lp.setSupplierName(supplierNameMap.getOrDefault(po.getSupplierId(), "供应商#" + po.getSupplierId()));
                lp.setTotalAmount(scale2(poTotal));
                lp.setPaidAmount(scale2(poPaid));
                lp.setUnpaidAmount(scale2(poTotal.subtract(poPaid).max(ZERO)));
                lp.setStatus(po.getStatus());
                lp.setAcceptanceDate(po.getAcceptanceDate());
                lp.setWarrantyEndDate(po.getWarrantyEndDate());
                out.getLinkedPurchases().add(lp);
            }
        }

        // 若没有关联采购合同/采购单，退回取合同最终成交价 / 设备总价 / 挂靠设备集采价合计
        List<ContractAsset> cas = contractAssetMapper.selectList(new LambdaQueryWrapper<ContractAsset>()
                .eq(ContractAsset::getContractId, c.getId())
                .eq(ContractAsset::getIsDeleted, 0));
        if (!cas.isEmpty()) {
            Set<Long> assetIds = new HashSet<>();
            cas.forEach(ca -> assetIds.add(ca.getAssetId()));
            List<Asset> assets = assetMapper.selectBatchIds(assetIds);
            BigDecimal assetSum = ZERO;
            for (Asset a : assets) {
                BigDecimal cost = a.getDealPriceOverride() != null ? a.getDealPriceOverride() : nz(a.getPurchasePrice());
                assetSum = assetSum.add(cost);
                if (a.getWarrantyEndDate() != null && (latestWarrantyEnd == null || a.getWarrantyEndDate().isAfter(latestWarrantyEnd))) {
                    latestWarrantyEnd = a.getWarrantyEndDate();
                }
            }
            if (purchaseTotal.signum() <= 0) {
                purchaseTotal = assetSum;
            }
        }
        if (purchaseTotal.signum() <= 0) {
            if (c.getFinalDealPrice() != null && c.getFinalDealPrice().signum() > 0) {
                purchaseTotal = c.getFinalDealPrice();
            } else if (c.getEquipmentTotal() != null && c.getEquipmentTotal().signum() > 0) {
                purchaseTotal = c.getEquipmentTotal();
            }
        }
        purchaseTotal = scale2(purchaseTotal);
        purchasePaid = scale2(purchasePaid);
        BigDecimal purchaseUnpaid = scale2(purchaseTotal.subtract(purchasePaid).max(ZERO));

        if (!hasExplicitPurchaseOutflow && purchaseTotal.signum() > 0) {
            LocalDate baseDate = c.getSignDate() != null ? c.getSignDate()
                    : (c.getStartDate() != null ? c.getStartDate() : LocalDate.now());
            addOutflow(monthFlow, baseDate, purchaseTotal);
        }

        // ---- 3. 剩余本金 ----
        BigDecimal remainingPrincipal = null;
        if (!schedules.isEmpty()) {
            if (maxSettledPeriod > 0) {
                for (RentSchedule s : schedules) {
                    if (Objects.equals(s.getPeriodNo(), maxSettledPeriod) && s.getClosingPrincipal() != null) {
                        remainingPrincipal = scale2(s.getClosingPrincipal());
                        break;
                    }
                }
            } else if (schedules.get(0).getOpeningPrincipal() != null) {
                remainingPrincipal = scale2(schedules.get(0).getOpeningPrincipal());
            }
        }
        if (remainingPrincipal == null) {
            BigDecimal basePrincipal = c.getFinalDealPrice() != null && c.getFinalDealPrice().signum() > 0
                    ? c.getFinalDealPrice()
                    : (purchaseTotal.signum() > 0 ? purchaseTotal : nz(c.getEquipmentTotal()));
            remainingPrincipal = scale2(basePrincipal.subtract(receivedRent).max(ZERO));
        }
        out.setRemainingPrincipal(remainingPrincipal);

        // ---- 4. 逐月租金流入 & 回本期数 & 最大垫资峰值 ----
        BigDecimal cumRent = ZERO;
        Integer paybackPeriodNo = null;
        LocalDate lastScheduleDate = null;
        if (!schedules.isEmpty()) {
            for (RentSchedule s : schedules) {
                BigDecimal amt = nz(s.getAmount());
                cumRent = cumRent.add(amt);
                if (paybackPeriodNo == null && purchaseTotal.signum() > 0 && cumRent.compareTo(purchaseTotal) >= 0) {
                    paybackPeriodNo = s.getPeriodNo();
                }
                RentBill rb = normalBillByPeriod.get(s.getPeriodNo());
                LocalDate inDate = (rb != null && "已核销".equals(rb.getStatus()) && rb.getMatchedAt() != null)
                        ? rb.getMatchedAt().toLocalDate()
                        : s.getDueDate();
                addInflow(monthFlow, inDate, amt);
                if (s.getDueDate() != null) {
                    lastScheduleDate = s.getDueDate();
                }
            }
        } else if (c.getTermMonths() != null && c.getTermMonths() > 0 && nz(c.getMonthRent()).signum() > 0) {
            LocalDate base = c.getFirstPayDate() != null ? c.getFirstPayDate()
                    : (c.getStartDate() != null ? c.getStartDate().plusMonths(1) : LocalDate.now());
            for (int i = 1; i <= c.getTermMonths(); i++) {
                LocalDate due = base.plusMonths(i - 1);
                BigDecimal amt = nz(c.getMonthRent());
                cumRent = cumRent.add(amt);
                if (paybackPeriodNo == null && purchaseTotal.signum() > 0 && cumRent.compareTo(purchaseTotal) >= 0) {
                    paybackPeriodNo = i;
                }
                addInflow(monthFlow, due, amt);
                lastScheduleDate = due;
            }
        }
        if (nz(c.getEndTransferPrice()).signum() > 0) {
            addInflow(monthFlow, lastScheduleDate != null ? lastScheduleDate : out.getEndDate(), c.getEndTransferPrice());
        }

        // 计算逐月净现金流 & 最大垫资峰值
        BigDecimal runningNet = ZERO;
        BigDecimal minCumulative = ZERO;
        String peakMonth = null;
        List<ProjectPanoramaDtos.MonthCashflow> monthly = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> entry : monthFlow.entrySet()) {
            BigDecimal inAmt = scale2(entry.getValue()[0]);
            BigDecimal outAmt = scale2(entry.getValue()[1]);
            BigDecimal net = scale2(inAmt.subtract(outAmt));
            runningNet = scale2(runningNet.add(net));
            if (runningNet.compareTo(minCumulative) < 0) {
                minCumulative = runningNet;
                peakMonth = entry.getKey();
            }
            if (includeMonthly && seeCost) {
                ProjectPanoramaDtos.MonthCashflow mc = new ProjectPanoramaDtos.MonthCashflow();
                mc.setMonth(entry.getKey());
                mc.setRentInflow(inAmt);
                mc.setPurchaseOutflow(outAmt);
                mc.setNetFlow(net);
                mc.setCumulativeNet(runningNet);
                monthly.add(mc);
            }
        }
        BigDecimal peakCapitalOutlay = scale2(minCumulative.negate().max(ZERO));

        out.setWarrantyEndDate(latestWarrantyEnd);
        if (latestWarrantyEnd != null) {
            out.setWarrantyDaysLeft(ChronoUnit.DAYS.between(today, latestWarrantyEnd));
        }

        // ---- 5. 成本与利润口径（仅经营角色可见） ----
        if (seeCost) {
            out.setPurchaseTotal(purchaseTotal);
            out.setPurchasePaid(purchasePaid);
            out.setPurchaseUnpaid(purchaseUnpaid);
            out.setInputInvoiceTotal(scale2(inputInvoiceTotal));
            out.setInputInvoiceTax(scale2(inputInvoiceTax));
            out.setNextPayableDate(nextPayableDate);
            out.setNextPayableAmount(nextPayableAmount == null ? null : scale2(nextPayableAmount));
            out.setNextPayableStage(nextPayableStage);

            BigDecimal expectedGross = scale2(plannedCustomerTotal.subtract(purchaseTotal));
            out.setExpectedGrossProfit(expectedGross);
            out.setExpectedGrossMargin(purchaseTotal.signum() > 0
                    ? expectedGross.divide(purchaseTotal, 4, RoundingMode.HALF_UP)
                    : ZERO);

            BigDecimal outTaxRate = c.getInvoiceTaxRate() != null ? c.getInvoiceTaxRate()
                    : (c.getTaxRate() != null ? c.getTaxRate() : new BigDecimal("0.03"));
            BigDecimal estOutVat = outTaxRate.signum() > 0
                    ? plannedRent.multiply(outTaxRate).divide(BigDecimal.ONE.add(outTaxRate), 2, RoundingMode.HALF_UP)
                    : ZERO;
            BigDecimal estInVat = inputInvoiceTax.signum() > 0 ? scale2(inputInvoiceTax) : scale2(estimatedInputVatFromPc);
            // 3% 简易/小规模专票不可抵扣进项；一般纳税人(>5%)可抵扣进项税额
            BigDecimal estTaxCost = outTaxRate.compareTo(SIMPLE_VAT_THRESHOLD) <= 0
                    ? estOutVat
                    : estOutVat.subtract(estInVat).max(ZERO);
            estTaxCost = scale2(estTaxCost);

            out.setEstimatedOutputVat(estOutVat);
            out.setEstimatedInputVat(estInVat);
            out.setEstimatedTaxCost(estTaxCost);
            out.setEstimatedAfterTaxProfit(scale2(expectedGross.subtract(estTaxCost)));

            out.setActualNetCashflow(scale2(receivedRent.add(out.getDepositHeld()).subtract(purchasePaid)));
            out.setPaybackProgress(purchaseTotal.signum() > 0
                    ? receivedRent.divide(purchaseTotal, 4, RoundingMode.HALF_UP).max(ZERO).min(BigDecimal.ONE)
                    : ZERO);
            out.setPaybackPeriodNo(paybackPeriodNo);
            out.setPeakCapitalOutlay(peakCapitalOutlay);
            out.setPeakOutlayMonth(peakMonth);
            out.setMonthlyCashflows(monthly);
        }
        return out;
    }

    private ProjectPanoramaDtos.Brief toBrief(ProjectPanoramaDtos.Panorama p) {
        ProjectPanoramaDtos.Brief b = new ProjectPanoramaDtos.Brief();
        b.setContractId(p.getContractId());
        b.setContractNo(p.getContractNo());
        b.setCustomerId(p.getCustomerId());
        b.setCustomerName(p.getCustomerName());
        b.setNature(p.getNature());
        b.setStatus(p.getStatus());
        b.setTermMonths(p.getTermMonths());
        b.setMonthRent(p.getMonthRent());
        b.setPlannedCustomerTotal(p.getPlannedCustomerTotal());
        b.setReceivedRent(p.getReceivedRent());
        b.setOverdueAmount(p.getOverdueAmount());
        b.setPendingInvoiceCount(p.getPendingInvoiceCount());
        b.setOverdueInvoiceCount(p.getOverdueInvoiceCount());
        b.setPurchaseTotal(p.getPurchaseTotal());
        b.setPurchasePaid(p.getPurchasePaid());
        b.setPurchaseUnpaid(p.getPurchaseUnpaid());
        b.setExpectedGrossProfit(p.getExpectedGrossProfit());
        b.setEstimatedAfterTaxProfit(p.getEstimatedAfterTaxProfit());
        b.setActualNetCashflow(p.getActualNetCashflow());
        b.setPaybackProgress(p.getPaybackProgress());
        b.setPaybackPeriodNo(p.getPaybackPeriodNo());
        b.setPeakCapitalOutlay(p.getPeakCapitalOutlay());
        b.setNextPayableDate(p.getNextPayableDate());
        b.setNextPayableAmount(p.getNextPayableAmount());
        b.setWarrantyEndDate(p.getWarrantyEndDate());
        return b;
    }

    private static void addInflow(TreeMap<String, BigDecimal[]> map, LocalDate date, BigDecimal amt) {
        if (date == null || amt == null || amt.signum() == 0) {
            return;
        }
        String ym = YearMonth.from(date).toString();
        BigDecimal[] pair = map.computeIfAbsent(ym, k -> new BigDecimal[]{ZERO, ZERO});
        pair[0] = pair[0].add(amt);
    }

    private static void addOutflow(TreeMap<String, BigDecimal[]> map, LocalDate date, BigDecimal amt) {
        if (date == null || amt == null || amt.signum() == 0) {
            return;
        }
        String ym = YearMonth.from(date).toString();
        BigDecimal[] pair = map.computeIfAbsent(ym, k -> new BigDecimal[]{ZERO, ZERO});
        pair[1] = pair[1].add(amt);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
