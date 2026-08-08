package top.aole.rent.modules.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 客户跟进时间线(M1-04)。每次电话/拜访/微信留记录。
 */
@Data
@TableName("yc_rent_customer_followup")
public class CustomerFollowup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    private Long userId;

    private String userName;

    /** 方式:电话/拜访/微信 */
    private String method;

    private String content;

    private String result;

    private LocalDateTime followTime;

    private LocalDate nextFollowDate;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
