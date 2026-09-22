package top.aole.rent.modules.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一份合同的收租侧对照(M2 → 采购应付)。
 *
 * <p>回答「这份合同的货款,靠哪些租金还」:采购单/应付按 {@code contract_id} 反查收租单与逾期案,
 * 不新建绑定关系表 —— 采购与收租本来就都挂在同一份合同上(§4.24 单一真相源)。
 *
 * <p>口径:已收=收租单 {@code status=已核销} 的 {@code received_amount} 净额(退款为负数,自动冲减);
 * 红冲单(原单与红字行)两边都不计;待收与逾期分列,逾期即 {@code status=逾期}(cron 扫描标记并开案)。
 */
@Data
public class RentCoverageDto {

    private Long contractId;

    /** 已收租金净额(已核销·含退款冲减) */
    private BigDecimal collectedAmount;

    /** 待收(未到期/已到期未标逾期) */
    private BigDecimal pendingAmount;

    /** 逾期未收 */
    private BigDecimal overdueAmount;

    /** 逾期单张数 */
    private Integer overdueCount;

    /** 在册收租单张数(不含红冲) */
    private Integer billCount;

    /** 下一期到期日(待收/逾期里最早的一张) */
    private LocalDate nextDueDate;

    /** 下一期应收 */
    private BigDecimal nextDueAmount;

    /** 开启中的逾期案件数 */
    private Integer openCaseCount;

    /** 开启中最紧迫的一步(延期<罚息<锁机<收回) */
    private String openCaseStep;
}
