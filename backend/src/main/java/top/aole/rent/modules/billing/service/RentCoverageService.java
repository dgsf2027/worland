package top.aole.rent.modules.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.dto.RentCoverageDto;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 收租对照查询(只读)。按合同聚合收租单与逾期案,供采购应付侧回答「这笔货款靠哪些租金还」。
 *
 * <p>只做聚合不写库;批量口径 {@link #byContract(Collection)} 一次性把所有合同查完,避免列表页 N+1。
 */
@Service
@RequiredArgsConstructor
public class RentCoverageService {

    private final RentBillMapper rentBillMapper;
    private final OverdueCaseMapper overdueCaseMapper;

    /** 逾期三步走的紧迫度排序,取开启中最靠后的一步展示。 */
    private static final List<String> STEP_ORDER = Arrays.asList("延期", "罚息", "锁机", "收回");

    /** 单份合同的收租对照;contractId 为空返回 null(采购单理论上必绑合同,防御性处理)。 */
    public RentCoverageDto of(Long contractId) {
        if (contractId == null) {
            return null;
        }
        return byContract(java.util.Collections.singletonList(contractId)).get(contractId);
    }

    /**
     * 批量收租对照:两条 in 查询查完所有合同,没有收租单的合同也返回一行零值(前端不必判空)。
     */
    public Map<Long, RentCoverageDto> byContract(Collection<Long> contractIds) {
        Map<Long, RentCoverageDto> result = new HashMap<>();
        if (contractIds == null || contractIds.isEmpty()) {
            return result;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(contractIds));
        ids.removeIf(java.util.Objects::isNull);
        if (ids.isEmpty()) {
            return result;
        }
        for (Long id : ids) {
            result.put(id, empty(id));
        }

        List<RentBill> bills = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .in(RentBill::getContractId, ids));
        for (RentBill b : bills) {
            RentCoverageDto d = result.get(b.getContractId());
            if (d == null || "红冲".equals(b.getStatus())) {
                continue;   // 红冲的原单与红字行都不在册
            }
            d.setBillCount(d.getBillCount() + 1);
            if ("已核销".equals(b.getStatus())) {
                d.setCollectedAmount(d.getCollectedAmount().add(nz(b.getReceivedAmount())));
                continue;
            }
            if ("逾期".equals(b.getStatus())) {
                d.setOverdueAmount(d.getOverdueAmount().add(nz(b.getAmount())));
                d.setOverdueCount(d.getOverdueCount() + 1);
            } else if ("待收".equals(b.getStatus())) {
                d.setPendingAmount(d.getPendingAmount().add(nz(b.getAmount())));
            }
            // 下一期 = 未收单里到期日最早的一张(逾期单排在最前,催收先看它)
            if (b.getDueDate() != null
                    && (d.getNextDueDate() == null || b.getDueDate().isBefore(d.getNextDueDate()))) {
                d.setNextDueDate(b.getDueDate());
                d.setNextDueAmount(scale(nz(b.getAmount())));
            }
        }

        List<OverdueCase> cases = overdueCaseMapper.selectList(new LambdaQueryWrapper<OverdueCase>()
                .in(OverdueCase::getContractId, ids)
                .eq(OverdueCase::getStatus, "开启"));
        for (OverdueCase c : cases) {
            RentCoverageDto d = result.get(c.getContractId());
            if (d == null) {
                continue;
            }
            d.setOpenCaseCount(d.getOpenCaseCount() + 1);
            if (urgency(c.getStep()) > urgency(d.getOpenCaseStep())) {
                d.setOpenCaseStep(c.getStep());
            }
        }

        for (RentCoverageDto d : result.values()) {
            d.setCollectedAmount(scale(d.getCollectedAmount()));
            d.setPendingAmount(scale(d.getPendingAmount()));
            d.setOverdueAmount(scale(d.getOverdueAmount()));
        }
        return result;
    }

    private RentCoverageDto empty(Long contractId) {
        RentCoverageDto d = new RentCoverageDto();
        d.setContractId(contractId);
        d.setCollectedAmount(BigDecimal.ZERO);
        d.setPendingAmount(BigDecimal.ZERO);
        d.setOverdueAmount(BigDecimal.ZERO);
        d.setOverdueCount(0);
        d.setBillCount(0);
        d.setOpenCaseCount(0);
        return d;
    }

    private static int urgency(String step) {
        return STEP_ORDER.indexOf(step);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
