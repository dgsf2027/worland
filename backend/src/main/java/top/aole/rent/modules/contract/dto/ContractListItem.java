package top.aole.rent.modules.contract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同列表项。
 */
@Data
public class ContractListItem {

    private Long id;
    private String no;
    private Long customerId;
    private String customerName;
    private String status;
    private Integer termMonths;
    private BigDecimal monthRent;
    /** 设备总价(含税)= 合同清单合计 */
    private BigDecimal equipmentTotal;
    private BigDecimal endTransferPrice;
    private Integer assetCount;
    private LocalDate startDate;
    /** 已过租期数，仅表示时间进度 */
    private Integer elapsedPeriods;
    private BigDecimal collectedRent;
    private BigDecimal totalRent;
    /** 实际净收租 / 合同全期租金，不含押金、留购和罚息 */
    private BigDecimal repaymentRatio;
}
