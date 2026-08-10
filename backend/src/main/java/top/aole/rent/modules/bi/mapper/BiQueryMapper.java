package top.aole.rent.modules.bi.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * BI 只读投影 mapper(M5-04)。<b>只读聚合不重算</b>(§4.24):读各模块拥有的规范列
 * (asset.status/contract.target_irr/rent_bill.received_amount…),不复制任何业务计算逻辑,
 * 口径与源模块一致(验收 SQL 逐维对齐)。仅 SELECT,无写入。
 */
@Mapper
public interface BiQueryMapper {

    /** 设备原始行(在租率/资产周转聚合源):id/品类/状态/供应商 */
    @Data
    class AssetRow {
        private Long id;
        private String category;
        private String status;
        private Long supplierId;
        private String supplierName;
        private BigDecimal purchasePrice;
        private BigDecimal marketPrice;
    }

    /** 合同原始行(加权回报聚合源):客户/性质/月租/目标IRR */
    @Data
    class ContractRow {
        private Long id;
        private Long customerId;
        private String customerName;
        private String nature;
        private String status;
        private BigDecimal monthRent;
        private BigDecimal targetIrr;
        private Integer termMonths;
    }

    /** 收租单原始行(应收账龄/回款趋势聚合源) */
    @Data
    class BillRow {
        private Long id;
        private Long contractId;
        private Long customerId;
        private String customerName;
        private BigDecimal amount;
        private BigDecimal receivedAmount;
        private String status;
        private String billKind;
        private LocalDate dueDate;
        private LocalDateTime matchedAt;
        private String accountPeriod;
    }

    @Select("SELECT a.id, a.category, a.status, a.supplier_id AS supplierId, s.name AS supplierName, "
            + "a.purchase_price AS purchasePrice, a.market_price AS marketPrice "
            + "FROM yc_rent_asset a LEFT JOIN yc_rent_supplier s ON s.id = a.supplier_id "
            + "WHERE a.is_deleted = 0")
    List<AssetRow> assets();

    @Select("SELECT c.id, c.customer_id AS customerId, cu.name AS customerName, c.nature, c.status, "
            + "c.month_rent AS monthRent, c.target_irr AS targetIrr, c.term_months AS termMonths "
            + "FROM yc_rent_contract c LEFT JOIN yc_rent_customer cu ON cu.id = c.customer_id "
            + "WHERE c.is_deleted = 0")
    List<ContractRow> contracts();

    @Select("SELECT b.id, b.contract_id AS contractId, c.customer_id AS customerId, cu.name AS customerName, "
            + "b.amount, b.received_amount AS receivedAmount, b.status, b.bill_kind AS billKind, "
            + "b.due_date AS dueDate, b.matched_at AS matchedAt, b.account_period AS accountPeriod "
            + "FROM yc_rent_rent_bill b "
            + "LEFT JOIN yc_rent_contract c ON c.id = b.contract_id "
            + "LEFT JOIN yc_rent_customer cu ON cu.id = c.customer_id "
            + "WHERE b.is_deleted = 0")
    List<BillRow> bills();
}
