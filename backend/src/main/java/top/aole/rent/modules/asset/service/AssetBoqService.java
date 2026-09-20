package top.aole.rent.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetBoq;
import top.aole.rent.modules.asset.dto.BoqDtos;
import top.aole.rent.modules.asset.mapper.AssetBoqMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 合同清单(《工程量清单计价表》格式)。合计(含税) = Σ 金额 → 回写设备合同价(purchase_price),
 * 税率用于拆出不含税金额与税额。配件 BOM 明细是另一张表,不参与合同价。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetBoqService {

    private final AssetBoqMapper boqMapper;
    private final AssetMapper assetMapper;
    private final AssetPaymentService paymentService;
    private final AuditLogService auditLogService;

    // ============================== 查询 ==============================

    public BoqDtos.Boq boq(Asset asset) {
        List<AssetBoq> rows = load(asset.getId());
        BoqDtos.Boq out = new BoqDtos.Boq();
        for (AssetBoq r : rows) {
            BoqDtos.Line l = new BoqDtos.Line();
            l.setId(r.getId());
            l.setSeq(r.getSeq());
            l.setName(r.getName());
            l.setModel(r.getModel());
            l.setSpec(r.getSpec());
            l.setUnit(r.getUnit());
            l.setQty(r.getQty());
            l.setUnitPrice(r.getUnitPrice());
            l.setAmount(r.getAmount());
            l.setAmountManual(Integer.valueOf(1).equals(r.getAmountManual()));
            l.setRemark(r.getRemark());
            out.getLines().add(l);
        }
        BigDecimal total = total(rows);
        out.setTaxRate(asset.getTaxRate());
        out.setLinked(!rows.isEmpty());
        if (!rows.isEmpty()) {
            out.setTotalWithTax(total);
            out.setTotalUpper(upperAmount(total));
            if (asset.getTaxRate() != null && asset.getTaxRate().signum() > 0) {
                BigDecimal without = total.divide(BigDecimal.ONE.add(asset.getTaxRate()), 2, RoundingMode.HALF_UP);
                out.setTotalWithoutTax(without);
                out.setTaxAmount(total.subtract(without));
            }
        }
        return out;
    }

    /** 清单合计(含税);没有清单行返回 null(合同价保持手填/采购入库值)。 */
    public BigDecimal totalIfAny(Long assetId) {
        List<AssetBoq> rows = load(assetId);
        return rows.isEmpty() ? null : total(rows);
    }

    private BigDecimal total(List<AssetBoq> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (AssetBoq r : rows) {
            total = total.add(amountOf(r));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** 行金额:手填取金额(空=不计价),否则 数量×单价。 */
    static BigDecimal amountOf(AssetBoq r) {
        if (Integer.valueOf(1).equals(r.getAmountManual())) {
            return r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
        }
        if (r.getQty() == null || r.getUnitPrice() == null) {
            return r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
        }
        return r.getQty().multiply(r.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
    }

    private List<AssetBoq> load(Long assetId) {
        List<AssetBoq> rows = boqMapper.selectList(new LambdaQueryWrapper<AssetBoq>()
                .eq(AssetBoq::getAssetId, assetId));
        rows.sort(Comparator.comparing((AssetBoq r) -> r.getSeq() == null ? Integer.MAX_VALUE : r.getSeq())
                .thenComparing(AssetBoq::getId));
        return rows;
    }

    // ============================== 保存 ==============================

    /** 整表保存:带 id 的更新,不带 id 的新增,表里没有的删除;保存后回写合同价。 */
    @Transactional
    public BoqDtos.Boq save(Asset asset, List<BoqDtos.Line> lines) {
        requireCostRole("维护合同清单");
        List<BoqDtos.Line> list = lines == null ? new ArrayList<>() : lines;
        Map<Long, AssetBoq> existing = load(asset.getId()).stream()
                .collect(Collectors.toMap(AssetBoq::getId, Function.identity()));
        Set<Long> kept = new HashSet<>();
        int seq = 1;
        for (BoqDtos.Line l : list) {
            String name = trimToNull(l.getName());
            if (name == null) {
                throw new BizException(400, "第 " + seq + " 行名称不能为空");
            }
            AssetBoq row = l.getId() == null ? null : existing.get(l.getId());
            boolean isNew = row == null;
            if (isNew) {
                row = new AssetBoq();
                row.setAssetId(asset.getId());
            }
            row.setSeq(seq++);
            row.setName(name);
            row.setModel(trimToNull(l.getModel()));
            row.setSpec(trimToNull(l.getSpec()));
            row.setUnit(trimToNull(l.getUnit()));
            row.setQty(l.getQty());
            row.setUnitPrice(l.getUnitPrice());
            boolean manual = Boolean.TRUE.equals(l.getAmountManual());
            row.setAmountManual(manual ? 1 : 0);
            row.setAmount(manual ? l.getAmount()
                    : (l.getQty() == null || l.getUnitPrice() == null ? null
                    : l.getQty().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP)));
            row.setRemark(trimToNull(l.getRemark()));
            if (isNew) {
                boqMapper.insert(row);
            } else {
                writeRow(row);
                kept.add(row.getId());
            }
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                boqMapper.deleteById(id);
            }
        }
        syncContractPrice(asset);
        auditLogService.record("合同清单保存", "asset", asset.getId(), AuditLogService.EXECUTED,
                "共 " + list.size() + " 行,合计(含税)=" + totalIfAny(asset.getId()));
        return boq(reload(asset.getId()));
    }

    /** 金额可以清空(updateById 跳过 null),显式 set。 */
    private void writeRow(AssetBoq row) {
        boqMapper.update(null, new LambdaUpdateWrapper<AssetBoq>()
                .eq(AssetBoq::getId, row.getId())
                .set(AssetBoq::getSeq, row.getSeq())
                .set(AssetBoq::getName, row.getName())
                .set(AssetBoq::getModel, row.getModel())
                .set(AssetBoq::getSpec, row.getSpec())
                .set(AssetBoq::getUnit, row.getUnit())
                .set(AssetBoq::getQty, row.getQty())
                .set(AssetBoq::getUnitPrice, row.getUnitPrice())
                .set(AssetBoq::getAmount, row.getAmount())
                .set(AssetBoq::getAmountManual, row.getAmountManual())
                .set(AssetBoq::getRemark, row.getRemark()));
    }

    /** 导入落库:整表替换。 */
    @Transactional
    public BoqDtos.ImportResult replaceAll(Asset asset, List<BoqDtos.Line> lines, List<String> messages) {
        requireCostRole("导入合同清单");
        for (AssetBoq r : load(asset.getId())) {
            boqMapper.deleteById(r.getId());
        }
        int seq = 1;
        for (BoqDtos.Line l : lines) {
            AssetBoq row = new AssetBoq();
            row.setAssetId(asset.getId());
            row.setSeq(seq++);
            row.setName(l.getName());
            row.setModel(l.getModel());
            row.setSpec(l.getSpec());
            row.setUnit(l.getUnit());
            row.setQty(l.getQty());
            row.setUnitPrice(l.getUnitPrice());
            row.setAmount(l.getAmount());
            row.setAmountManual(Boolean.TRUE.equals(l.getAmountManual()) ? 1 : 0);
            row.setRemark(l.getRemark());
            boqMapper.insert(row);
        }
        syncContractPrice(asset);
        BigDecimal total = totalIfAny(asset.getId());
        auditLogService.record("合同清单导入", "asset", asset.getId(), AuditLogService.EXECUTED,
                "导入 " + lines.size() + " 行,合计(含税)=" + total);

        BoqDtos.ImportResult res = new BoqDtos.ImportResult();
        res.setTotal(lines.size());
        res.setImported(lines.size());
        res.setTotalWithTax(total);
        res.getMessages().addAll(messages);
        return res;
    }

    /**
     * 合同价 ← 合同清单合计(含税)。清单为空时不动合同价(保留手填/采购入库值)。
     * 合同价是折旧基数,变更只影响之后的折旧计提,已计提凭证不追溯;预计付款/待付应付随之重算。
     */
    public void syncContractPrice(Asset asset) {
        BigDecimal total = totalIfAny(asset.getId());
        if (total == null) {
            return;
        }
        Asset fresh = reload(asset.getId());
        if (fresh.getPurchasePrice() != null && fresh.getPurchasePrice().compareTo(total) == 0) {
            return;
        }
        assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, asset.getId())
                .set(Asset::getPurchasePrice, total));
        log.info("合同价随合同清单联动: assetId={}, {} → {}", asset.getId(), fresh.getPurchasePrice(), total);
        fresh.setPurchasePrice(total);
        paymentService.resyncPending(fresh);
    }

    private Asset reload(Long assetId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
            throw new BizException(404, "设备不存在: id=" + assetId);
        }
        return a;
    }

    static void requireCostRole(String action) {
        if (!DataScope.canSeeCost(UserContext.getRole())) {
            throw new BizException(403, "当前角色无权" + action);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ============================== 金额大写 ==============================

    private static final String[] DIGITS = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
    private static final String[] UNITS = {"", "拾", "佰", "仟"};
    private static final String[] GROUPS = {"", "万", "亿", "万亿"};

    /** 人民币金额大写,如 1335974.8 → 人民币壹佰叁拾叁万伍仟玖佰柒拾肆元捌角整。 */
    public static String upperAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        v = v.abs();
        long yuan = v.longValue();
        int cents = v.subtract(BigDecimal.valueOf(yuan)).movePointRight(2).intValue();
        StringBuilder sb = new StringBuilder("人民币");
        if (negative) {
            sb.append("负");
        }
        sb.append(yuan == 0 ? "零元" : integerPart(yuan) + "元");
        if (cents == 0) {
            sb.append("整");
        } else {
            int jiao = cents / 10;
            int fen = cents % 10;
            if (jiao > 0) {
                sb.append(DIGITS[jiao]).append("角");
            } else if (yuan > 0) {
                sb.append("零");
            }
            if (fen > 0) {
                sb.append(DIGITS[fen]).append("分");
            } else {
                sb.append("整");
            }
        }
        return sb.toString();
    }

    /** 逐位转大写:非零位带单位,连续零并成一个「零」,每四位满则补 万/亿。 */
    private static String integerPart(long yuan) {
        String digits = Long.toString(yuan);
        int len = digits.length();
        StringBuilder sb = new StringBuilder();
        boolean pendingZero = false;
        for (int i = 0; i < len; i++) {
            int d = digits.charAt(i) - '0';
            int pos = len - 1 - i;
            int unit = pos % 4;
            int group = pos / 4;
            if (d == 0) {
                pendingZero = sb.length() > 0;
            } else {
                if (pendingZero) {
                    sb.append("零");
                    pendingZero = false;
                }
                sb.append(DIGITS[d]).append(UNITS[unit]);
            }
            if (unit == 0 && group > 0 && group < GROUPS.length) {
                boolean groupHasNonZero = false;
                for (int k = Math.max(0, i - 3); k <= i; k++) {
                    groupHasNonZero |= digits.charAt(k) != '0';
                }
                if (groupHasNonZero) {
                    sb.append(GROUPS[group]);
                    pendingZero = false;
                }
            }
        }
        return sb.toString();
    }
}
