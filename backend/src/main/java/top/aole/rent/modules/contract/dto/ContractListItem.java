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
    private BigDecimal endTransferPrice;
    private Integer assetCount;
    private LocalDate startDate;
    /** 回款进度:已过期数/总期数(计划态口径) */
    private Integer elapsedPeriods;
}
