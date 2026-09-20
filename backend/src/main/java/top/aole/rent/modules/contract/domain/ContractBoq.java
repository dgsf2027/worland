package top.aole.rent.modules.contract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合同清单行(《工程量清单计价表》:序号/名称/型号/规格/单位/数量/单价/金额/备注)。
 * 一份设备租赁合同一张清单;含税合计 = 合同设备总价。赠送行金额为空、优惠行金额为负(amountManual=1)。
 * 填了 assetCategory 的行可按数量一键生成设备并回挂到本合同。
 */
@Data
@TableName("yc_rent_contract_boq")
public class ContractBoq {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;

    private Integer seq;

    private String name;

    private String model;

    private String spec;

    private String unit;

    private BigDecimal qty;

    /** 单价(含税) */
    private BigDecimal unitPrice;

    /** 金额(含税);null = 表格里的「-」 */
    private BigDecimal amount;

    /** 1=金额手填(赠送/优惠行),0=数量×单价自动算 */
    private Integer amountManual;

    /** 生成设备时的品类;空=本行不生成设备(运费/安装费/优惠等) */
    private String assetCategory;

    /** 已按本行生成的设备台数 */
    private Integer generatedCount;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
