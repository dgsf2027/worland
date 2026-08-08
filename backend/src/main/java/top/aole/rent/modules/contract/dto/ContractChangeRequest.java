package top.aole.rent.modules.contract.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 合同变更入参(变更/作废/续租/提前结清)。
 */
@Data
public class ContractChangeRequest {

    /** 变更说明/裁决(留痕) */
    private String detail;

    /** 续租:追加期数(月) */
    private Integer renewMonths;

    /** 续租/变更:新月租(可空=沿用) */
    private java.math.BigDecimal newMonthRent;

    /** 提前结清:结清日(截断该日之后未到期计划);空=今天 */
    private LocalDate settleDate;
}
