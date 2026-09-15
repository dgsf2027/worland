package top.aole.rent.modules.inventory.service;

import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.inventory.domain.InvCompPrice;
import top.aole.rent.modules.inventory.domain.InvDamage;
import top.aole.rent.modules.inventory.domain.InvRental;
import top.aole.rent.modules.inventory.dto.InvDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产管理的纯计算规则(无 IO,便于单测):状态列映射、归还拆分、赔偿计算、出租单状态、编号序号。
 */
public final class InvRules {

    private InvRules() {
    }

    public static final String S_STOCK = "库存";
    public static final String S_RESERVED = "已预订";
    public static final String S_RENTED = "出租中";
    public static final String S_REPAIR = "维修中";
    public static final String S_SCRAPPED = "已报废";

    public static final String R_RESERVED = "已预订";
    public static final String R_RENTED = "出租中";
    public static final String R_RETURNED = "已归还";
    public static final String R_CANCELLED = "已取消";

    public static final String M_OUT = "出库";
    public static final String M_RETURN = "归还";
    public static final String M_IN = "入库";
    public static final String M_TO_REPAIR = "送修";
    public static final String M_REPAIRED = "修好";
    public static final String M_SCRAP = "报废";

    public static final String SETTLE_PENDING = "待收取";
    public static final Set<String> SETTLE_STATUSES = new HashSet<>(Arrays.asList("待收取", "已收取", "已减免"));
    public static final Set<String> CONDITION_LEVELS = new HashSet<>(Arrays.asList("完好", "轻微损坏", "损坏"));

    /** 可做出入库/出租登记的角色 */
    public static final Set<String> OPERATE_ROLES = new HashSet<>(Arrays.asList("老板", "供应链", "业务"));

    /** 状态 → 数量列 */
    private static final Map<String, String> STATUS_COLUMN = new LinkedHashMap<>();
    static {
        STATUS_COLUMN.put(S_STOCK, "stock_qty");
        STATUS_COLUMN.put(S_RESERVED, "reserved_qty");
        STATUS_COLUMN.put(S_RENTED, "rented_qty");
        STATUS_COLUMN.put(S_REPAIR, "repair_qty");
        STATUS_COLUMN.put(S_SCRAPPED, "scrapped_qty");
    }

    public static boolean canOperate(String role) {
        return role != null && OPERATE_ROLES.contains(role);
    }

    public static String column(String status) {
        String col = STATUS_COLUMN.get(status);
        if (col == null) {
            throw new BizException(400, "未知状态:" + status);
        }
        return col;
    }

    public static int positive(Integer v, String label) {
        if (v == null || v <= 0) {
            throw new BizException(400, label + "须为正整数");
        }
        return v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 归还拆分:完好/维修/报废 三者之和须等于归还数量;都没填 → 全部完好入库。返回 [good, repair, scrap]。
     */
    public static int[] resolveReturnSplit(int qty, Integer good, Integer repair, Integer scrap) {
        if (good == null && repair == null && scrap == null) {
            return new int[]{qty, 0, 0};
        }
        int g = nz(good);
        int r = nz(repair);
        int s = nz(scrap);
        if (g < 0 || r < 0 || s < 0) {
            throw new BizException(400, "完好/维修/报废数量不能为负");
        }
        if (g + r + s != qty) {
            throw new BizException(400, "完好(" + g + ")+维修(" + r + ")+报废(" + s + ")须等于归还数量 " + qty);
        }
        return new int[]{g, r, s};
    }

    /**
     * 按价目计算损坏缺件行:金额 = 损坏数×损坏单价 + 缺失数×缺失单价。忽略数量都为 0 的行;同名合并报错。
     * 价目里没有的检查项单价按 0 计,并返回在 unpriced 中。
     */
    public static List<InvDamage> buildDamages(List<InvDtos.DamageLine> lines, List<InvCompPrice> prices, List<String> unpriced) {
        Map<String, InvCompPrice> priceByName = new LinkedHashMap<>();
        for (InvCompPrice p : prices == null ? Collections.<InvCompPrice>emptyList() : prices) {
            if (p.getPartName() != null) {
                priceByName.put(p.getPartName().trim(), p);
            }
        }
        List<InvDamage> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (InvDtos.DamageLine l : lines == null ? Collections.<InvDtos.DamageLine>emptyList() : lines) {
            String name = l.getPartName() == null ? "" : l.getPartName().trim();
            int damaged = nz(l.getDamagedQty());
            int missing = nz(l.getMissingQty());
            if (damaged < 0 || missing < 0) {
                throw new BizException(400, "检查项「" + name + "」损坏/缺失数量不能为负");
            }
            if (damaged == 0 && missing == 0) {
                continue;
            }
            if (name.isEmpty()) {
                throw new BizException(400, "损坏缺件的检查项名称不能为空");
            }
            if (!seen.add(name)) {
                throw new BizException(400, "检查项重复:" + name);
            }
            InvCompPrice p = priceByName.get(name);
            BigDecimal dp = p == null || p.getDamagePrice() == null ? BigDecimal.ZERO : p.getDamagePrice();
            BigDecimal mp = p == null || p.getMissingPrice() == null ? BigDecimal.ZERO : p.getMissingPrice();
            if ((damaged > 0 && dp.signum() == 0) || (missing > 0 && mp.signum() == 0)) {
                unpriced.add(name);
            }
            InvDamage d = new InvDamage();
            d.setPartName(name);
            d.setDamagedQty(damaged);
            d.setMissingQty(missing);
            d.setDamagePrice(dp);
            d.setMissingPrice(mp);
            d.setAmount(dp.multiply(BigDecimal.valueOf(damaged)).add(mp.multiply(BigDecimal.valueOf(missing)))
                    .setScale(2, RoundingMode.HALF_UP));
            d.setSettleStatus(SETTLE_PENDING);
            d.setRemark(trimToNull(l.getRemark()));
            out.add(d);
        }
        return out;
    }

    public static BigDecimal sumAmount(List<InvDamage> damages) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvDamage d : damages) {
            total = total.add(d.getAmount() == null ? BigDecimal.ZERO : d.getAmount());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** 出入库后出租单状态:全部出库且全部归还 → 已归还;有出库 → 出租中;否则 已预订。 */
    public static String rentalStatus(InvRental r) {
        int qty = nz(r.getQty());
        int out = nz(r.getOutQty());
        int ret = nz(r.getReturnedQty());
        if (qty > 0 && out >= qty && ret >= out) {
            return R_RETURNED;
        }
        return out > 0 ? R_RENTED : R_RESERVED;
    }

    /** 合同到期日 = 起租日 + 租期月数(缺一返回 null) */
    public static LocalDate contractEnd(LocalDate start, Integer termMonths) {
        if (start == null || termMonths == null || termMonths <= 0) {
            return null;
        }
        return start.plusMonths(termMonths);
    }

    /** 编号序号:prefix + 3 位流水。maxCode 为当前同前缀最大编号(可空)。 */
    public static String nextCode(String prefix, String maxCode) {
        int seq = 0;
        if (maxCode != null && maxCode.startsWith(prefix)) {
            try {
                seq = Integer.parseInt(maxCode.substring(prefix.length()));
            } catch (NumberFormatException ignore) {
                seq = 0;
            }
        }
        return prefix + String.format("%03d", seq + 1);
    }

    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
