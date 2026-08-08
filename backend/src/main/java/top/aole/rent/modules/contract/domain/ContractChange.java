package top.aole.rent.modules.contract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 合同变更留痕(变更/作废/续租/提前结清 · 含 reverse 红冲语义)。
 */
@Data
@TableName("yc_rent_contract_change")
public class ContractChange {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;

    /** 类型:变更/作废/续租/提前结清 */
    private String changeType;

    /** 是否红冲(作废=整份红冲) */
    private Integer isReverse;

    private String beforeJson;

    private String afterJson;

    private String detail;

    private Long operatorId;

    private LocalDateTime bizTime;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
