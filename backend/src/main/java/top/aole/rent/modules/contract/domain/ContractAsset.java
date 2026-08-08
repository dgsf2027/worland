package top.aole.rent.modules.contract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合同↔设备(N 台;单台月租分摊 · M1-11)。alloc_rent 单一真值,Σ=contract.month_rent。
 */
@Data
@TableName("yc_rent_contract_asset")
public class ContractAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;

    private Long assetId;

    /** 单台月租分摊(元·单一真值) */
    private BigDecimal allocRent;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
