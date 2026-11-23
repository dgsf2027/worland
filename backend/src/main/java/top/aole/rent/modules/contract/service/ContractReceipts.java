package top.aole.rent.modules.contract.service;

import top.aole.rent.modules.billing.domain.RentBill;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Actual rent receipts only. Elapsed time, issued bills, deposits and penalties are not rent receipts. */
public final class ContractReceipts {
    private ContractReceipts() {}
    public static Map<Long, BigDecimal> totals(List<RentBill> bills) {
        Map<Long, BigDecimal> result = new HashMap<>();
        Map<Long, RentBill> originals = new HashMap<>();
        for (RentBill bill : bills) {
            if (!Integer.valueOf(1).equals(bill.getIsDeleted()) && "正常".equals(bill.getBillKind())) {
                originals.put(bill.getId(), bill);
            }
        }
        for (RentBill bill : bills) {
            if (Integer.valueOf(1).equals(bill.getIsDeleted()) || !"已核销".equals(bill.getStatus())) continue;
            boolean rental = "正常".equals(bill.getBillKind());
            RentBill original = originals.get(bill.getRefBillId());
            boolean refund = "退款".equals(bill.getBillKind()) && original != null
                    && Objects.equals(original.getContractId(), bill.getContractId());
            if ((rental || refund) && bill.getReceivedAmount() != null) {
                result.merge(bill.getContractId(), bill.getReceivedAmount(), BigDecimal::add);
            }
        }
        return result;
    }
    public static BigDecimal totalRent(BigDecimal monthRent, Integer termMonths) {
        return monthRent == null || termMonths == null ? BigDecimal.ZERO : monthRent.multiply(BigDecimal.valueOf(termMonths));
    }
    public static BigDecimal ratio(BigDecimal received, BigDecimal total) {
        return total.signum() <= 0 ? BigDecimal.ZERO : received.divide(total, 6, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }
}
