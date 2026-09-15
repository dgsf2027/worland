package top.aole.rent.modules.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 资产管理 DTO。状态口径:
 * <ul>
 *   <li>库存(闲置) = 在库未预订 stockQty</li>
 *   <li>在库 = 库存 + 已预订</li>
 *   <li>出租 = 出租中 rentedQty</li>
 * </ul>
 */
public final class InvDtos {

    private InvDtos() {
    }

    // ============ 资产 ============

    @Data
    public static class ItemSave {
        /** 编号;空 = 自动生成 */
        private String code;
        private String name;
        private String spec;
        private String category;
        private String unit;
        private String location;
        private String remark;
        /** 仅新建有效:初始库存数量 */
        private Integer initialQty;
    }

    @Data
    public static class ItemView {
        private Long id;
        private String code;
        private String name;
        private String spec;
        private String category;
        private String unit;
        private String location;
        private String remark;
        private Integer totalQty;
        private Integer stockQty;
        private Integer reservedQty;
        private Integer rentedQty;
        private Integer repairQty;
        private Integer scrappedQty;
        /** 在库 = 库存 + 已预订 */
        private Integer inStoreQty;
        private String qrToken;
        private Integer photoCount;
        private String createByName;
        private LocalDateTime createTime;
    }

    @Data
    public static class ItemDetail {
        private ItemView item;
        private List<RentalView> rentals = new ArrayList<>();
        private List<MovementView> movements = new ArrayList<>();
    }

    /** 入库/送修/修好/报废 */
    @Data
    public static class AdjustRequest {
        /** 入库/送修/修好/报废 */
        private String type;
        private Integer qty;
        /** 报废来源:库存/维修中(默认库存) */
        private String fromStatus;
        private String conditionDesc;
        private String remark;
    }

    // ============ 出租 ============

    @Data
    public static class RentalSave {
        private Long itemId;
        private Long customerId;
        private String customerName;
        private Long contractId;
        private String installAddress;
        private String contact;
        private String phone;
        private Integer qty;
        private LocalDate startDate;
        private LocalDate expectedReturnDate;
        private String remark;
    }

    @Data
    public static class RentalView {
        private Long id;
        private String rentalNo;
        private Long itemId;
        private String itemCode;
        private String itemName;
        private String itemSpec;
        private String unit;
        private Long customerId;
        private String customerName;
        private Long contractId;
        private String contractNo;
        /** 合同到期日 = 起租日 + 租期月数 */
        private LocalDate contractEndDate;
        private String installAddress;
        private String contact;
        private String phone;
        private Integer qty;
        private Integer outQty;
        private Integer returnedQty;
        /** 待出库 = 数量 - 已出库 */
        private Integer pendingOutQty;
        /** 在外 = 已出库 - 已归还 */
        private Integer onSiteQty;
        private LocalDate startDate;
        private LocalDate expectedReturnDate;
        private LocalDate actualReturnDate;
        private String status;
        /** 逾期天数(出租中且已过预计归还日),否则 null */
        private Long overdueDays;
        private BigDecimal compensationTotal;
        private String remark;
        private String createByName;
        private LocalDateTime createTime;
    }

    // ============ 出入库 ============

    @Data
    public static class OutRequest {
        private Integer qty;
        private String accessories;
        private String conditionLevel;
        private String conditionDesc;
        private String remark;
    }

    @Data
    public static class DamageLine {
        private String partName;
        private Integer damagedQty;
        private Integer missingQty;
        private String remark;
    }

    @Data
    public static class ReturnRequest {
        private Integer qty;
        /** 完好入库 / 转维修 / 报废;三者之和 = qty(都为空时全部完好入库) */
        private Integer goodQty;
        private Integer repairQty;
        private Integer scrapQty;
        private String accessories;
        private String conditionLevel;
        private String conditionDesc;
        private String remark;
        private List<DamageLine> damages = new ArrayList<>();
    }

    @Data
    public static class MovementResult {
        private Long movementId;
        private BigDecimal compensationTotal;
        /** 价目单价为 0 的检查项(提示去设置价目) */
        private List<String> unpricedParts = new ArrayList<>();
        private String rentalStatus;
    }

    @Data
    public static class MovementView {
        private Long id;
        private Long itemId;
        private String itemCode;
        private String itemName;
        private Long rentalId;
        private String rentalNo;
        private String customerName;
        private String type;
        private Integer qty;
        private Integer goodQty;
        private Integer repairQty;
        private Integer scrapQty;
        private String accessories;
        private String conditionLevel;
        private String conditionDesc;
        private BigDecimal compensationTotal;
        private String operatorName;
        private LocalDateTime opTime;
        private String remark;
        private Integer photoCount;
        private List<DamageView> damages = new ArrayList<>();
    }

    // ============ 损坏缺件 ============

    @Data
    public static class DamageView {
        private Long id;
        private Long movementId;
        private Long rentalId;
        private String rentalNo;
        private String customerName;
        private Long itemId;
        private String itemCode;
        private String itemName;
        private String partName;
        private Integer damagedQty;
        private Integer missingQty;
        private BigDecimal damagePrice;
        private BigDecimal missingPrice;
        private BigDecimal amount;
        private String settleStatus;
        private String remark;
        private LocalDateTime createTime;
    }

    @Data
    public static class SettleRequest {
        /** 待收取/已收取/已减免 */
        private String settleStatus;
        private String remark;
    }

    @Data
    public static class PriceRow {
        private Long id;
        private String partName;
        private String unit;
        private BigDecimal damagePrice;
        private BigDecimal missingPrice;
        private Integer sortNo;
    }

    @Data
    public static class CompanyInfo {
        private String companyName;
        private String phone;
        private String address;
        private String website;
        private String notice;
    }

    // ============ 提醒与报表 ============

    @Data
    public static class Reminder {
        /** 合同到期/即将归还/设备逾期/需要维修/赔偿待收 */
        private String type;
        /** danger/warning/info */
        private String level;
        private String title;
        private String detail;
        private Long itemId;
        private Long rentalId;
        private LocalDate date;
    }

    @Data
    public static class Overview {
        private int itemCount;
        private int totalQty;
        /** 在库 = 库存 + 已预订 */
        private int inStoreQty;
        /** 库存(闲置,在库未预订) */
        private int stockQty;
        private int reservedQty;
        /** 出租数量 */
        private int rentedQty;
        private int repairQty;
        private int scrappedQty;
        /** 闲置 = 库存 */
        private int idleQty;
        private int activeRentalCount;
        private int overdueRentalCount;
        private BigDecimal pendingCompensation = BigDecimal.ZERO;
        private List<Reminder> reminders = new ArrayList<>();
    }

    // ============ 扫码 ============

    /** 未登录可见:企业信息 + 名称规格 */
    @Data
    public static class PublicScanView {
        private CompanyInfo company;
        private String code;
        private String name;
        private String spec;
        private String category;
        private String unit;
    }

    /** 登录后:库存与出租信息,可出库/归还 */
    @Data
    public static class ScanView {
        private CompanyInfo company;
        private ItemView item;
        /** 可出库(已预订有待出库)与可归还(有在外数量)的出租单 */
        private List<RentalView> activeRentals = new ArrayList<>();
        private List<PriceRow> prices = new ArrayList<>();
        private boolean canOperate;
    }
}
