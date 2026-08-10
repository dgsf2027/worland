package top.aole.rent.modules.monthly.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.analytics.service.CashflowService;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetDepreciationLine;
import top.aole.rent.modules.asset.mapper.AssetDepreciationLineMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.billing.domain.AccountingPeriod;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.AccountingPeriodMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.distribution.dto.DistributionDtos;
import top.aole.rent.modules.distribution.service.DistributionService;
import top.aole.rent.modules.finance.domain.LedgerBook;
import top.aole.rent.modules.finance.domain.Voucher;
import top.aole.rent.modules.finance.domain.VoucherLine;
import top.aole.rent.modules.finance.mapper.LedgerBookMapper;
import top.aole.rent.modules.finance.mapper.VoucherLineMapper;
import top.aole.rent.modules.finance.mapper.VoucherMapper;
import top.aole.rent.modules.monthly.dto.MonthlyDtos;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 月度报表包聚合服务(M3-07,DESIGN_DOC §4.3 六件套)。
 *
 * 唯一职责:把已建成的 billing/finance/analytics/asset/distribution 服务产出按"月"聚成一个包,
 * 只读、不重算(§4.24 单一真相源)。数字全部来自源模块:利润=ledger_book ops、现金=voucher 银行存款分录、
 * 往来=CashflowService 分层、资产=asset+折旧行、分配=DistributionService(角色投影)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    private final RentBillMapper rentBillMapper;
    private final AccountingPeriodMapper accountingPeriodMapper;
    private final LedgerBookMapper ledgerBookMapper;
    private final VoucherMapper voucherMapper;
    private final VoucherLineMapper voucherLineMapper;
    private final CashflowService cashflowService;
    private final PayableMapper payableMapper;
    private final AssetMapper assetMapper;
    private final AssetDepreciationLineMapper depreciationLineMapper;
    private final DistributionService distributionService;

    private static final String ACC_BANK = "1002"; // 银行存款(现金流水口径)
    /** 分配表可见角色(其余角色 → 分配表不出现,§4.5 分配明细按角色可见) */
    private static final List<String> DIST_VISIBLE_ROLES =
            java.util.Arrays.asList("老板", "财务", "GP", "LP");

    // ============================== 报表包总览 ==============================

    public MonthlyDtos.PackageResp buildPackage(String period) {
        List<String> periods = availablePeriods();
        String p = resolvePeriod(period, periods);

        MonthlyDtos.PackageResp resp = new MonthlyDtos.PackageResp();
        resp.setPeriod(p);
        resp.setPeriods(periods);
        resp.setDataAsOf(java.time.LocalDateTime.now());
        resp.setLocked(isOpsLocked(p));

        resp.setRentLedger(rentLedger(p));
        resp.setProfit(profitStatement(p));
        resp.setCashflow(cashflowSheet(p));
        resp.setDueSheet(dueSheet());
        resp.setAsset(assetSnapshot(p));
        applyDistribution(resp, p);
        return resp;
    }

    // ============================== 口径月解析 ==============================

    /** 有数据的账期:并集(ledger_book ops period ∪ rent_bill account_period),降序。 */
    public List<String> availablePeriods() {
        TreeSet<String> set = new TreeSet<>(Comparator.reverseOrder());
        for (LedgerBook lb : ledgerBookMapper.selectList(new LambdaQueryWrapper<LedgerBook>()
                .eq(LedgerBook::getBook, "ops").select(LedgerBook::getPeriod))) {
            if (lb.getPeriod() != null) {
                set.add(lb.getPeriod());
            }
        }
        for (RentBill b : rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0).isNotNull(RentBill::getAccountPeriod)
                .select(RentBill::getAccountPeriod))) {
            if (b.getAccountPeriod() != null && !b.getAccountPeriod().isEmpty()) {
                set.add(b.getAccountPeriod());
            }
        }
        if (set.isEmpty()) {
            set.add(LocalDate.now().toString().substring(0, 7));
        }
        return new ArrayList<>(set);
    }

    private String resolvePeriod(String period, List<String> periods) {
        if (period != null && !period.isEmpty()) {
            return period;
        }
        return periods.get(0);
    }

    private boolean isOpsLocked(String period) {
        AccountingPeriod ap = accountingPeriodMapper.selectOne(new LambdaQueryWrapper<AccountingPeriod>()
                .eq(AccountingPeriod::getPeriod, period)
                .eq(AccountingPeriod::getBook, "ops")
                .last("LIMIT 1"));
        return ap != null && Integer.valueOf(1).equals(ap.getIsLocked());
    }

    // ============================== ① 收租台账 ==============================

    public MonthlyDtos.RentLedger rentLedger(String period) {
        MonthlyDtos.RentLedger led = new MonthlyDtos.RentLedger();
        led.setPeriod(period);
        List<RentBill> bills = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .eq(RentBill::getAccountPeriod, period)
                .orderByAsc(RentBill::getDueDate));

        BigDecimal receivable = BigDecimal.ZERO, received = BigDecimal.ZERO;
        int matched = 0, overdue = 0;
        LocalDate today = LocalDate.now();
        for (RentBill b : bills) {
            MonthlyDtos.RentLedgerRow row = new MonthlyDtos.RentLedgerRow();
            row.setBillId(b.getId());
            row.setBillNo(b.getBillNo());
            row.setContractNo(b.getContractId() == null ? null : "合同#" + b.getContractId());
            row.setPeriodNo(b.getPeriodNo());
            row.setDueDate(b.getDueDate());
            row.setAmount(nz(b.getAmount()));
            row.setReceivedAmount(nz(b.getReceivedAmount()));
            row.setStatus(b.getStatus());
            row.setBillKind(b.getBillKind());
            boolean isOverdue = !"已核销".equals(b.getStatus()) && b.getDueDate() != null
                    && b.getDueDate().isBefore(today) && nz(b.getAmount()).signum() > 0;
            row.setOverdue(isOverdue);
            led.getRows().add(row);

            receivable = receivable.add(nz(b.getAmount()));
            received = received.add(nz(b.getReceivedAmount()));
            if ("已核销".equals(b.getStatus())) {
                matched++;
            }
            if (isOverdue) {
                overdue++;
            }
        }
        led.setBillCount(bills.size());
        led.setMatchedCount(matched);
        led.setOverdueCount(overdue);
        led.setTotalReceivable(scale2(receivable));
        led.setTotalReceived(scale2(received));
        led.setTotalOutstanding(scale2(receivable.subtract(received)));
        led.setCollectionRate(receivable.signum() == 0 ? BigDecimal.ZERO
                : received.multiply(BigDecimal.valueOf(100)).divide(receivable, 1, RoundingMode.HALF_UP));
        return led;
    }

    // ============================== ② 利润表(ops) ==============================

    /** 从 ledger_book(book=ops,period)按 entry_type 聚合:收入 revenue − 成本 cost = 经营利润。 */
    public MonthlyDtos.ProfitStatement profitStatement(String period) {
        MonthlyDtos.ProfitStatement pl = new MonthlyDtos.ProfitStatement();
        pl.setPeriod(period);
        pl.setLocked(isOpsLocked(period));

        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (LedgerBook lb : ledgerBookMapper.selectList(new LambdaQueryWrapper<LedgerBook>()
                .eq(LedgerBook::getBook, "ops")
                .eq(LedgerBook::getPeriod, period))) {
            byType.merge(lb.getEntryType() == null ? "other" : lb.getEntryType(), nz(lb.getAmount()), BigDecimal::add);
        }
        BigDecimal revenue = byType.getOrDefault("revenue", BigDecimal.ZERO);
        BigDecimal cost = byType.getOrDefault("cost", BigDecimal.ZERO);
        BigDecimal residual = byType.getOrDefault("residual", BigDecimal.ZERO);
        BigDecimal op = revenue.subtract(cost).add(residual);

        pl.getRows().add(new MonthlyDtos.PlRow("leaseRevenue", "租赁收入", scale2(revenue), false,
                "ops 账 revenue 净额(6051 租赁收入·净红冲)"));
        pl.getRows().add(new MonthlyDtos.PlRow("depreciation", "折旧费用", scale2(cost.negate()), false,
                "ops 账 cost 净额(6602 折旧费用)"));
        if (residual.signum() != 0) {
            pl.getRows().add(new MonthlyDtos.PlRow("residual", "残值损益", scale2(residual), false,
                    "ops 账 residual(转让处置损益)"));
        }
        pl.getRows().add(new MonthlyDtos.PlRow("operatingProfit", "经营利润", scale2(op), true,
                "= 租赁收入 − 折旧费用 ± 残值损益"));
        pl.setOperatingProfit(scale2(op));
        return pl;
    }

    // ============================== ③ 现金流水 ==============================

    /** 本期银行存款(1002)分录:dr=收 cr=支;头寸/杠杆取 CashflowService 不重算。 */
    public MonthlyDtos.CashflowSheet cashflowSheet(String period) {
        MonthlyDtos.CashflowSheet cs = new MonthlyDtos.CashflowSheet();
        cs.setPeriod(period);

        List<Voucher> vouchers = voucherMapper.selectList(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getBook, "ops")
                .eq(Voucher::getPeriod, period)
                .eq(Voucher::getIsDeleted, 0));
        List<Long> vids = vouchers.stream().map(Voucher::getId).collect(Collectors.toList());
        BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO;
        int inCount = 0, outCount = 0;
        if (!vids.isEmpty()) {
            for (VoucherLine ln : voucherLineMapper.selectList(new LambdaQueryWrapper<VoucherLine>()
                    .in(VoucherLine::getVoucherId, vids)
                    .eq(VoucherLine::getAccountCode, ACC_BANK))) {
                BigDecimal amt = nz(ln.getAmount());
                if ("dr".equals(ln.getDirection())) {
                    in = in.add(amt);
                    inCount++;
                } else {
                    out = out.add(amt);
                    outCount++;
                }
            }
        }
        cs.setInflow(scale2(in));
        cs.setOutflow(scale2(out));
        cs.setNet(scale2(in.subtract(out)));
        cs.setInCount(inCount);
        cs.setOutCount(outCount);

        CashflowDtos.Cashflow cf = cashflowService.cashflow(12);
        cs.setNetPosition(nz(cf.getNetPosition()));
        if (cf.getLeverage() != null) {
            cs.setOwnCapital(nz(cf.getLeverage().getOwnCapital()));
            cs.setSupplierCredit(nz(cf.getLeverage().getSupplierCredit()));
            cs.setFinancing(nz(cf.getLeverage().getFinancing()));
        }
        return cs;
    }

    // ============================== ④ 往来 ==============================

    public MonthlyDtos.DueSheet dueSheet() {
        MonthlyDtos.DueSheet ds = new MonthlyDtos.DueSheet();
        CashflowDtos.Cashflow cf = cashflowService.cashflow(12);
        ds.setAsOf(cf.getAsOf());
        if (cf.getReceivable() != null) {
            ds.setReceivableWithin1Y(nz(cf.getReceivable().getWithin1Year()));
            ds.setReceivableBeyond1Y(nz(cf.getReceivable().getBeyond1Year()));
            ds.setReceivableTotal(nz(cf.getReceivable().getTotal()));
            ds.setReceivableCount(cf.getReceivable().getCount());
        }
        if (cf.getPayable() != null) {
            ds.setPayableWithin1Y(nz(cf.getPayable().getWithin1Year()));
            ds.setPayableBeyond1Y(nz(cf.getPayable().getBeyond1Year()));
            ds.setPayableTotal(nz(cf.getPayable().getTotal()));
            ds.setPayableCount(cf.getPayable().getCount());
        }
        LocalDate today = LocalDate.now();
        for (Payable pay : payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getStatus, "待付")
                .eq(Payable::getIsDeleted, 0)
                .orderByAsc(Payable::getDueDate))) {
            MonthlyDtos.PayableRow row = new MonthlyDtos.PayableRow();
            row.setPayableId(pay.getId());
            row.setPurchaseInId(pay.getPurchaseInId());
            row.setStage(pay.getStage());
            row.setDueDate(pay.getDueDate());
            row.setAmount(nz(pay.getAmount()));
            row.setOverdue(pay.getDueDate() != null && pay.getDueDate().isBefore(today));
            ds.getPayables().add(row);
        }
        return ds;
    }

    // ============================== ⑤ 资产快照 ==============================

    public MonthlyDtos.AssetSnapshot assetSnapshot(String period) {
        MonthlyDtos.AssetSnapshot snap = new MonthlyDtos.AssetSnapshot();
        snap.setPeriod(period);
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getIsDeleted, 0));

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        BigDecimal marketTotal = BigDecimal.ZERO, bookTotal = BigDecimal.ZERO;
        int rented = 0, idle = 0, pending = 0;
        for (Asset a : assets) {
            String st = a.getStatus() == null ? "未知" : a.getStatus();
            byStatus.merge(st, 1, Integer::sum);
            if (st.contains("在租")) {
                rented++;
            } else if (st.contains("投放")) {
                idle++;
            } else if (st.contains("待处置") || st.contains("收回")) {
                pending++;
            }
            marketTotal = marketTotal.add(nz(a.getMarketPrice()));
            bookTotal = bookTotal.add(netBookValue(a));
        }
        snap.setTotal(assets.size());
        snap.setRentedCount(rented);
        snap.setIdleCount(idle);
        snap.setPendingDisposal(pending);
        snap.setRentedRatio(assets.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(rented).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(assets.size()), 1, RoundingMode.HALF_UP));
        snap.setMarketPriceTotal(scale2(marketTotal));
        snap.setBookValueTotal(scale2(bookTotal));
        for (Map.Entry<String, Integer> e : byStatus.entrySet()) {
            snap.getByStatus().add(new MonthlyDtos.StatusCount(e.getKey(), e.getValue()));
        }
        return snap;
    }

    /** 账面净值(ops):取最新一期折旧行 book_value_after;无折旧行则退回集采价(§4.17 即时算不 stale)。 */
    private BigDecimal netBookValue(Asset a) {
        AssetDepreciationLine last = depreciationLineMapper.selectOne(new LambdaQueryWrapper<AssetDepreciationLine>()
                .eq(AssetDepreciationLine::getAssetId, a.getId())
                .eq(AssetDepreciationLine::getIsDeleted, 0)
                .orderByDesc(AssetDepreciationLine::getPeriodNo)
                .last("LIMIT 1"));
        if (last != null && last.getBookValueAfter() != null) {
            return last.getBookValueAfter();
        }
        return nz(a.getPurchasePrice());
    }

    // ============================== ⑥ 分配表(角色可见) ==============================

    private void applyDistribution(MonthlyDtos.PackageResp resp, String period) {
        CurrentUser cu = UserContext.get();
        String role = cu == null ? null : cu.getRole();
        if (role == null || !DIST_VISIBLE_ROLES.contains(role)) {
            resp.setDistributionVisible(false);
            resp.setDistribution(null);
            resp.setDistributionMaskNote("分配表按角色可见(§4.5):当前角色「" + (role == null ? "未登录" : role)
                    + "」无分配明细可见权限,本件套已隐藏");
            return;
        }
        resp.setDistributionVisible(true);
        MonthlyDtos.DistributionSheet sheet = new MonthlyDtos.DistributionSheet();
        sheet.setPeriod(period);

        List<DistributionDtos.DistributionItem> items = distributionService.list(period, true);
        if (items.isEmpty()) {
            sheet.setPresent(false);
            sheet.setScopeNote("本期暂无生效分配单");
            resp.setDistribution(sheet);
            return;
        }
        DistributionDtos.DistributionItem head = items.get(0);
        sheet.setPresent(true);
        sheet.setDistributable(nz(head.getDistributable()));
        sheet.setMgmtFee(nz(head.getMgmtFee()));
        sheet.setCash50(nz(head.getCash50()));
        sheet.setRoll50(nz(head.getRoll50()));
        sheet.setReserveAfter(nz(head.getReserveAfter()));
        // 复用 DistributionService 详情(P0-E:老板/财务见全表,GP/LP 仅本人)
        DistributionDtos.DistributionDetail det = distributionService.detail(head.getId());
        boolean full = "老板".equals(role) || "财务".equals(role);
        for (DistributionDtos.ShareItem s : det.getShares()) {
            MonthlyDtos.ShareRow r = new MonthlyDtos.ShareRow();
            r.setName(s.getName());
            r.setRole(s.getRole());
            r.setRatio(nz(s.getRatio()));
            r.setCashShare(nz(s.getCashShare()));
            r.setRollShare(nz(s.getRollShare()));
            r.setMgmtFee(nz(s.getMgmtFee()));
            r.setTotalGain(nz(s.getTotalGain()));
            r.setSelf(Boolean.TRUE.equals(s.getSelf()));
            sheet.getShares().add(r);
        }
        sheet.setScopeNote(full ? "全表可见(老板/财务)" : "仅本人那份可见(GP/LP 只读)");
        resp.setDistribution(sheet);
    }

    // ============================== 工具 ==============================

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
