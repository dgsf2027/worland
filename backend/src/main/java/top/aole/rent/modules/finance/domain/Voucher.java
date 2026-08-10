package top.aole.rent.modules.finance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 凭证头(业财一体双账 · M3-01)。一业务事件 × 一账套 = 一张凭证(tax/ops 各一)。
 *
 * <p>§4.24 单一真相源:借贷平衡 Σdr=Σcr 服务端校验;{@code ledger_book} 由过账时唯一写入。
 * <p>P0-F 红冲:{@code reverses_id} 唯一约束 = 幂等键(一原凭证仅允许被红冲一次),红冲=负额新凭证,
 * 原凭证保留,单事务原子,落 {@code locked_period} 期拒写(锁账守卫)。
 */
@Data
@TableName("yc_rent_voucher")
public class Voucher {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String voucherNo;

    /** rent_bill/purchase_in/transfer/depreciation/manual */
    private String sourceDocType;

    private Long sourceDocId;

    /** tax 税务 / ops 经营 */
    private String book;

    /** 记账期 YYYY-MM(锁账守卫基准) */
    private String period;

    private LocalDate bizDate;

    /** 借方合计=贷方合计(借贷平衡校验值) */
    private BigDecimal totalAmount;

    /** revenue/cost/payable/other(ledger_book 汇总维度) */
    private String entryType;

    private String summary;

    /** 0 原始 / 1 红冲 */
    private Integer isReversal;

    /** 红冲指向的原凭证 id(幂等键·唯一约束) */
    private Long reversesId;

    private Integer lockedPeriod;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
