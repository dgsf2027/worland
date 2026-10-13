package top.aole.rent.modules.payproof.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 资金进出回单关联(V122):一个回单文件可挂多张单据。无逻辑删除——回单只增不删。 */
@Data
@TableName("yc_rent_pay_proof")
public class PayProof {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;

    /** payable / rent_bill / inv_damage */
    private String bizType;

    private Long bizId;

    /** 付款/核销/批量核销/退款/还款/赔偿收取/补传 */
    private String action;

    private String createByName;

    private LocalDateTime createTime;
}
