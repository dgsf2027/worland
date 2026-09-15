package top.aole.rent.modules.inventory.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 资产标签企业信息(单行 id=1),印在二维码标签上、扫码页展示。 */
@Data
@TableName("yc_rent_inv_company")
public class InvCompany {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String companyName;

    private String phone;

    private String address;

    private String website;

    private String notice;

    private LocalDateTime updateTime;
}
