package top.aole.rent.modules.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("yc_rent_bank_statement")
public class BankStatement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchNo;
    private LocalDate txDate;
    private String counterpartyName;
    private BigDecimal amount;
    /** IN=收款 / OUT=付款 */
    private String direction;
    private String bankSerial;
    private String memo;
    /** 已匹配 / 待确认 / 未匹配 / 已核销 / 已忽略 */
    private String matchStatus;
    /** RENT_BILL / PURCHASE_TERM / NONE */
    private String matchType;
    private Long matchedBizId;
    private String matchedBizNo;
    private String matchedCustomer;
    private Integer confidence;
    private String matchReason;
    private LocalDateTime reconciledAt;
    private String operatorName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;
}
