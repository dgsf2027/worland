package top.aole.rent.modules.contract;

import org.junit.jupiter.api.Test;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.contract.service.ContractReceipts;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractReceiptsTest {
    RentBill bill(long id, long contract, String kind, String status, String received) {
        RentBill b = new RentBill(); b.setId(id); b.setContractId(contract); b.setBillKind(kind);
        b.setStatus(status); b.setReceivedAmount(new BigDecimal(received)); b.setIsDeleted(0);
        return b;
    }
    @Test void elapsedOrIssuedUnpaidBillsDoNotCountAsReceipts() {
        Map<Long,BigDecimal> totals = ContractReceipts.totals(Arrays.asList(
                bill(1,1,"正常","待收","0"),bill(2,1,"正常","逾期","0")));
        assertEquals(BigDecimal.ZERO,totals.getOrDefault(1L,BigDecimal.ZERO));
        assertEquals(0,ContractReceipts.ratio(BigDecimal.ZERO,new BigDecimal("1868400")).compareTo(BigDecimal.ZERO));
    }
    @Test void aggregatesNetReceiptsByContractExcludingPenaltiesDeletedAndReversals() {
        RentBill refund = bill(2,1,"退款","已核销","-200"); refund.setRefBillId(1L);
        RentBill deleted = bill(3,1,"正常","已核销","900"); deleted.setIsDeleted(1);
        RentBill penaltyRefund = bill(6,1,"退款","已核销","-50"); penaltyRefund.setRefBillId(5L);
        Map<Long,BigDecimal> totals = ContractReceipts.totals(Arrays.asList(bill(1,1,"正常","已核销","1000"),refund,
                deleted,bill(4,1,"正常","红冲","700"),bill(5,1,"罚息","已核销","100"),penaltyRefund,
                bill(7,2,"正常","已核销","500"),bill(8,1,"红冲","红冲","-700")));
        assertEquals(new BigDecimal("800"),totals.get(1L));
        assertEquals(new BigDecimal("500"),totals.get(2L));
        assertEquals(new BigDecimal("0.400000"),ContractReceipts.ratio(totals.get(1L),new BigDecimal("2000")));
    }
    @Test void noScheduleRequiredAndProgressIsBounded() {
        assertEquals(new BigDecimal("12000"),ContractReceipts.totalRent(new BigDecimal("1000"),12));
        assertEquals(BigDecimal.ZERO,ContractReceipts.ratio(BigDecimal.TEN,BigDecimal.ZERO));
        assertEquals(BigDecimal.ONE,ContractReceipts.ratio(new BigDecimal("1100"),new BigDecimal("1000")));
        assertEquals(BigDecimal.ZERO,ContractReceipts.ratio(new BigDecimal("-1"),new BigDecimal("1000")));
    }
}
