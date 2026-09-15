package top.aole.rent.modules.inventory.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产出租单。新建即预订(库存→已预订),出库(已预订→出租中)与归还可分批。
 */
@Data
@TableName("yc_rent_inv_rental")
public class InvRental {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String rentalNo;

    private Long itemId;

    private Long customerId;

    private String customerName;

    private Long contractId;

    private String installAddress;

    private String contact;

    private String phone;

    private Integer qty;

    private Integer outQty;

    private Integer returnedQty;

    private LocalDate startDate;

    private LocalDate expectedReturnDate;

    private LocalDate actualReturnDate;

    /** 已预订/出租中/已归还/已取消 */
    private String status;

    private String remark;

    private String createByName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
