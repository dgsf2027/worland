package top.aole.rent.modules.contract.service;

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
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractBoq;
import top.aole.rent.modules.contract.dto.BoqDtos;
import top.aole.rent.modules.contract.mapper.ContractBoqMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 合同清单(《工程量清单计价表》)。一份设备租赁合同一张清单:含税合计 = 合同设备总价(回写 contract.equipment_total),
 * 税率用于拆出不含税金额与税额;填了品类的行可按数量一键生成设备并回挂本合同。
 *
 * <p>设备上的「配件 BOM 明细」是另一回事,只记配件构成与故障档案,不参与合同金额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractBoqService {

    /** 可生成设备的品类 */
    public static final Set<String> ASSET_CATEGORIES = new HashSet<>(java.util.Arrays.asList("播种墙", "货架", "阁楼", "配件"));

    private final ContractBoqMapper boqMapper;
    private final ContractMapper contractMapper;
    private final AssetService assetService;
    private final AuditLogService auditLogService;

    // ============================== 查询 ==============================

    public BoqDtos.Boq boq(Contract contract) {
        List<ContractBoq> rows = load(contract.getId());
        BoqDtos.Boq out = new BoqDtos.Boq();
        int generated = 0;
        int pending = 0;
        for (ContractBoq r : rows) {
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
            l.setAssetCategory(r.getAssetCategory());
            l.setGeneratedCount(nz(r.getGeneratedCount()));
            l.setPendingCount(pendingCount(r));
            l.setRemark(r.getRemark());
            out.getLines().add(l);
            generated += nz(r.getGeneratedCount());
            pending += pendingCount(r);
        }
        BigDecimal total = total(rows);
        out.setTaxRate(contract.getTaxRate());
        out.setLinked(!rows.isEmpty());
        out.setGeneratedAssets(generated);
        out.setPendingAssets(pending);
        if (!rows.isEmpty()) {
            out.setTotalWithTax(total);
            out.setTotalUpper(upperAmount(total));
            if (contract.getTaxRate() != null && contract.getTaxRate().signum() > 0) {
                BigDecimal without = total.divide(BigDecimal.ONE.add(contract.getTaxRate()), 2, RoundingMode.HALF_UP);
                out.setTotalWithoutTax(without);
                out.setTaxAmount(total.subtract(without));
            }
        }
        return out;
    }

    /** 清单合计(含税);没有清单行返回 null。 */
    public BigDecimal totalIfAny(Long contractId) {
        List<ContractBoq> rows = load(contractId);
        return rows.isEmpty() ? null : total(rows);
    }

    private BigDecimal total(List<ContractBoq> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (ContractBoq r : rows) {
            total = total.add(amountOf(r));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** 行金额:手填取金额(空=不计价),否则 数量×单价。 */
    static BigDecimal amountOf(ContractBoq r) {
        if (Integer.valueOf(1).equals(r.getAmountManual())) {
            return r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
        }
        if (r.getQty() == null || r.getUnitPrice() == null) {
            return r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
        }
        return r.getQty().multiply(r.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
    }

    /** 还可生成的设备台数:填了品类的行才算,数量向下取整减已生成。 */
    private static int pendingCount(ContractBoq r) {
        if (r.getAssetCategory() == null || r.getQty() == null) {
            return 0;
        }
        return Math.max(0, r.getQty().setScale(0, RoundingMode.DOWN).intValue() - nz(r.getGeneratedCount()));
    }

    private List<ContractBoq> load(Long contractId) {
        List<ContractBoq> rows = boqMapper.selectList(new LambdaQueryWrapper<ContractBoq>()
                .eq(ContractBoq::getContractId, contractId));
        rows.sort(Comparator.comparing((ContractBoq r) -> r.getSeq() == null ? Integer.MAX_VALUE : r.getSeq())
                .thenComparing(ContractBoq::getId));
        return rows;
    }

    // ============================== 保存 / 导入 ==============================

    /** 整表保存:带 id 的更新,不带 id 的新增,表里没有的删除;保存后回写设备总价。 */
    @Transactional
    public BoqDtos.Boq save(Contract contract, List<BoqDtos.Line> lines) {
        requireCostRole("维护合同清单");
        List<BoqDtos.Line> list = lines == null ? new ArrayList<>() : lines;
        Map<Long, ContractBoq> existing = load(contract.getId()).stream()
                .collect(Collectors.toMap(ContractBoq::getId, Function.identity()));
        Set<Long> kept = new HashSet<>();
        int seq = 1;
        for (BoqDtos.Line l : list) {
            String name = trimToNull(l.getName());
            if (name == null) {
                throw new BizException(400, "第 " + seq + " 行名称不能为空");
            }
            String category = trimToNull(l.getAssetCategory());
            if (category != null && !ASSET_CATEGORIES.contains(category)) {
                throw new BizException(400, "第 " + seq + " 行品类「" + category + "」不在 "
                        + String.join("/", ASSET_CATEGORIES) + " 之内");
            }
            ContractBoq row = l.getId() == null ? null : existing.get(l.getId());
            boolean isNew = row == null;
            if (isNew) {
                row = new ContractBoq();
                row.setContractId(contract.getId());
                row.setGeneratedCount(0);
            }
            if (!isNew && nz(row.getGeneratedCount()) > 0 && category == null) {
                throw new BizException(400, "第 " + seq + " 行「" + name + "」已生成 " + row.getGeneratedCount()
                        + " 台设备,不能清空品类;如需重来请先删除这些设备");
            }
            row.setSeq(seq++);
            row.setName(name);
            row.setModel(trimToNull(l.getModel()));
            row.setSpec(trimToNull(l.getSpec()));
            row.setUnit(trimToNull(l.getUnit()));
            row.setQty(l.getQty());
            row.setUnitPrice(l.getUnitPrice());
            row.setAssetCategory(category);
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
        for (Map.Entry<Long, ContractBoq> e : existing.entrySet()) {
            if (!kept.contains(e.getKey())) {
                assertNoGeneratedAssets(e.getValue());
                boqMapper.deleteById(e.getKey());
            }
        }
        syncEquipmentTotal(contract.getId());
        auditLogService.record("合同清单保存", "contract", contract.getId(), AuditLogService.EXECUTED,
                "共 " + list.size() + " 行,设备总价(含税)=" + totalIfAny(contract.getId()));
        return boq(reload(contract.getId()));
    }

    /** 导入落库:整表替换(已生成设备的行不允许被覆盖掉)。 */
    @Transactional
    public BoqDtos.ImportResult replaceAll(Contract contract, List<BoqDtos.Line> lines, List<String> messages) {
        requireCostRole("导入合同清单");
        for (ContractBoq r : load(contract.getId())) {
            assertNoGeneratedAssets(r);
            boqMapper.deleteById(r.getId());
        }
        int seq = 1;
        for (BoqDtos.Line l : lines) {
            ContractBoq row = new ContractBoq();
            row.setContractId(contract.getId());
            row.setSeq(seq++);
            row.setName(l.getName());
            row.setModel(l.getModel());
            row.setSpec(l.getSpec());
            row.setUnit(l.getUnit());
            row.setQty(l.getQty());
            row.setUnitPrice(l.getUnitPrice());
            row.setAmount(l.getAmount());
            row.setAmountManual(Boolean.TRUE.equals(l.getAmountManual()) ? 1 : 0);
            row.setAssetCategory(trimToNull(l.getAssetCategory()));
            row.setGeneratedCount(0);
            row.setRemark(l.getRemark());
            boqMapper.insert(row);
        }
        syncEquipmentTotal(contract.getId());
        BigDecimal total = totalIfAny(contract.getId());
        auditLogService.record("合同清单导入", "contract", contract.getId(), AuditLogService.EXECUTED,
                "导入 " + lines.size() + " 行,设备总价(含税)=" + total);

        BoqDtos.ImportResult res = new BoqDtos.ImportResult();
        res.setTotal(lines.size());
        res.setImported(lines.size());
        res.setTotalWithTax(total);
        res.getMessages().addAll(messages);
        return res;
    }

    private void assertNoGeneratedAssets(ContractBoq row) {
        if (nz(row.getGeneratedCount()) > 0) {
            throw new BizException(400, "清单行「" + row.getName() + "」已生成 " + row.getGeneratedCount()
                    + " 台设备,不能删除或整表替换;请先在设备台账里删除这些设备");
        }
    }

    private void writeRow(ContractBoq row) {
        boqMapper.update(null, new LambdaUpdateWrapper<ContractBoq>()
                .eq(ContractBoq::getId, row.getId())
                .set(ContractBoq::getSeq, row.getSeq())
                .set(ContractBoq::getName, row.getName())
                .set(ContractBoq::getModel, row.getModel())
                .set(ContractBoq::getSpec, row.getSpec())
                .set(ContractBoq::getUnit, row.getUnit())
                .set(ContractBoq::getQty, row.getQty())
                .set(ContractBoq::getUnitPrice, row.getUnitPrice())
                .set(ContractBoq::getAmount, row.getAmount())
                .set(ContractBoq::getAmountManual, row.getAmountManual())
                .set(ContractBoq::getAssetCategory, row.getAssetCategory())
                .set(ContractBoq::getRemark, row.getRemark()));
    }

    /**
     * 设备总价 ← 合同清单含税合计(清单为空则置空)。设备总价供租金/IRR 与单笔 P&L 参考,
     * 不改动已生成的租金计划。
     */
    public void syncEquipmentTotal(Long contractId) {
        BigDecimal total = totalIfAny(contractId);
        contractMapper.update(null, new LambdaUpdateWrapper<Contract>()
                .eq(Contract::getId, contractId)
                .set(Contract::getEquipmentTotal, total));
    }

    // ============================== 按清单生成设备 ==============================

    /**
     * 按清单行的数量生成设备并挂到本合同(设备状态=采购,合同价=该行单价)。
     * 只处理填了品类的行;运费/安装费/优惠这类行不生成。重复点只补生成还差的台数。
     */
    @Transactional
    public BoqDtos.GenerateResult generateAssets(Contract contract, List<Long> lineIds) {
        requireCostRole("按合同清单生成设备");
        List<ContractBoq> rows = load(contract.getId());
        Set<Long> picked = lineIds == null || lineIds.isEmpty() ? null : new HashSet<>(lineIds);
        BoqDtos.GenerateResult res = new BoqDtos.GenerateResult();
        for (ContractBoq r : rows) {
            if (picked != null && !picked.contains(r.getId())) {
                continue;
            }
            if (r.getAssetCategory() == null) {
                if (picked != null) {
                    res.getMessages().add("「" + r.getName() + "」没填品类,未生成设备");
                }
                continue;
            }
            int n = pendingCount(r);
            if (n <= 0) {
                if (picked != null) {
                    res.getMessages().add("「" + r.getName() + "」已按数量生成过 " + nz(r.getGeneratedCount()) + " 台,无需重复生成");
                }
                continue;
            }
            BigDecimal unitPrice = r.getUnitPrice();
            if (unitPrice == null && r.getAmount() != null && r.getQty() != null && r.getQty().signum() > 0) {
                unitPrice = r.getAmount().divide(r.getQty(), 2, RoundingMode.HALF_UP);
            }
            for (int i = 0; i < n; i++) {
                assetService.createFromBoqLine(r.getAssetCategory(), modelOf(r), unitPrice,
                        contract.getId(), r.getId(),
                        "合同 " + contract.getNo() + " 清单第 " + r.getSeq() + " 行:" + r.getName()
                                + (r.getSpec() == null ? "" : "(" + r.getSpec() + ")"));
            }
            boqMapper.update(null, new LambdaUpdateWrapper<ContractBoq>()
                    .eq(ContractBoq::getId, r.getId())
                    .set(ContractBoq::getGeneratedCount, nz(r.getGeneratedCount()) + n));
            res.setCreated(res.getCreated() + n);
            res.getMessages().add("「" + r.getName() + "」生成 " + n + " 台" + r.getAssetCategory());
        }
        if (res.getCreated() == 0 && res.getMessages().isEmpty()) {
            res.getMessages().add("没有需要生成的行:请在清单行上选好「生成设备品类」,并确认数量大于已生成台数");
        }
        auditLogService.record("按合同清单生成设备", "contract", contract.getId(), AuditLogService.EXECUTED,
                "生成 " + res.getCreated() + " 台");
        return res;
    }

    private static String modelOf(ContractBoq r) {
        if (r.getModel() != null) {
            return r.getModel();
        }
        return r.getSpec() == null ? r.getName() : r.getName() + " " + r.getSpec();
    }

    private Contract reload(Long contractId) {
        Contract c = contractMapper.selectById(contractId);
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "合同不存在: id=" + contractId);
        }
        return c;
    }

    static void requireCostRole(String action) {
        if (!DataScope.canSeeCost(UserContext.getRole())) {
            throw new BizException(403, "当前角色无权" + action);
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
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
