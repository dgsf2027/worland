package top.aole.rent.modules.finance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 双账账套流水(M3-01/08)。已过账收入/成本按账套(tax/ops)汇总。
 *
 * <p><b>唯一写手</b>=VoucherService.post/reverse(append-only:原始过账写正额,红冲写负额行·防 stale 漂移)。
 * <p>500万营收红线取 {@code book='tax' AND entry_type='revenue'} 按自然年归集(ops≠tax·折旧只落 ops)。
 */
@Data
@TableName("yc_rent_ledger_book")
public class LedgerBook {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** tax 税务 / ops 经营 */
    private String book;

    /** 记账期 YYYY-MM */
    private String period;

    /** revenue/cost/payable/other */
    private String entryType;

    /** 有符号:原始为正,红冲为负 */
    private BigDecimal amount;

    private Long voucherId;

    private String sourceDocType;

    private Long sourceDocId;

    private LocalDate bizDate;

    private String remark;

    private LocalDateTime createTime;
}
