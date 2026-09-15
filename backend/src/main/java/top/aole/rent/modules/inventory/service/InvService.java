package top.aole.rent.modules.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.inventory.domain.InvCompPrice;
import top.aole.rent.modules.inventory.domain.InvCompany;
import top.aole.rent.modules.inventory.domain.InvDamage;
import top.aole.rent.modules.inventory.domain.InvItem;
import top.aole.rent.modules.inventory.domain.InvMovement;
import top.aole.rent.modules.inventory.domain.InvRental;
import top.aole.rent.modules.inventory.dto.InvDtos;
import top.aole.rent.modules.inventory.mapper.InvCompPriceMapper;
import top.aole.rent.modules.inventory.mapper.InvCompanyMapper;
import top.aole.rent.modules.inventory.mapper.InvDamageMapper;
import top.aole.rent.modules.inventory.mapper.InvItemMapper;
import top.aole.rent.modules.inventory.mapper.InvMovementMapper;
import top.aole.rent.modules.inventory.mapper.InvRentalMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static top.aole.rent.modules.inventory.service.InvRules.*;

/**
 * 资产管理(仓库实物,一批同规格按数量管理)。数量变动一律走条件更新(源状态数量 ≥ n 才扣),并发下不会扣成负数。
 */
@Service
@RequiredArgsConstructor
public class InvService {

    public static final String BIZ_ITEM = "inv_item";
    public static final String BIZ_MOVEMENT = "inv_movement";
    private static final String TARGET = "inv_item";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 合同到期提前提醒天数 */
    static final int CONTRACT_REMIND_DAYS = 30;
    /** 预计归还提前提醒天数 */
    static final int RETURN_REMIND_DAYS = 7;

    private final InvItemMapper itemMapper;
    private final InvRentalMapper rentalMapper;
    private final InvMovementMapper movementMapper;
    private final InvDamageMapper damageMapper;
    private final InvCompPriceMapper priceMapper;
    private final InvCompanyMapper companyMapper;
    private final CustomerMapper customerMapper;
    private final ContractMapper contractMapper;
    private final FileObjectMapper fileObjectMapper;
    private final AuditLogService auditLogService;

    // ============================== 资产 ==============================

    public PageResult<InvDtos.ItemView> listItems(String keyword, String category, int page, int size) {
        String kw = trimToNull(keyword);
        String cat = trimToNull(category);
        List<InvItem> rows = itemMapper.selectList(new LambdaQueryWrapper<InvItem>()
                .eq(cat != null, InvItem::getCategory, cat)
                .and(kw != null, w -> w.like(InvItem::getCode, kw).or().like(InvItem::getName, kw)
                        .or().like(InvItem::getSpec, kw).or().like(InvItem::getLocation, kw))
                .orderByDesc(InvItem::getId));
        List<InvItem> pageRows = slice(rows, page, size);
        return new PageResult<>(rows.size(), page, size, toItemViews(pageRows));
    }

    public InvDtos.ItemDetail itemDetail(Long id) {
        InvItem item = requireItem(id);
        InvDtos.ItemDetail d = new InvDtos.ItemDetail();
        d.setItem(toItemViews(Collections.singletonList(item)).get(0));
        d.setRentals(toRentalViews(rentalMapper.selectList(new LambdaQueryWrapper<InvRental>()
                .eq(InvRental::getItemId, id).orderByDesc(InvRental::getId))));
        d.setMovements(toMovementViews(movementMapper.selectList(new LambdaQueryWrapper<InvMovement>()
                .eq(InvMovement::getItemId, id).orderByDesc(InvMovement::getId))));
        return d;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long createItem(InvDtos.ItemSave req) {
        CurrentUser u = UserContext.require();
        String name = trimToNull(req.getName());
        if (name == null) {
            throw new BizException(400, "资产名称不能为空");
        }
        int initial = req.getInitialQty() == null ? 0 : req.getInitialQty();
        if (initial < 0) {
            throw new BizException(400, "初始数量不能为负");
        }
        String code = trimToNull(req.getCode());
        if (code == null) {
            String prefix = "ZC" + LocalDate.now().format(DAY);
            code = nextCode(prefix, itemMapper.maxCode(prefix));
        }
        assertCodeFree(code, null);

        InvItem item = new InvItem();
        applyItemFields(item, req, name);
        item.setCode(code);
        item.setTotalQty(initial);
        item.setStockQty(initial);
        item.setReservedQty(0);
        item.setRentedQty(0);
        item.setRepairQty(0);
        item.setScrappedQty(0);
        item.setQrToken(UUID.randomUUID().toString().replace("-", ""));
        item.setCreateByName(u.getUserName());
        try {
            itemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "资产编号已存在(含已删除的资产):" + code);
        }
        if (initial > 0) {
            insertMovement(item.getId(), null, M_IN, initial, "新建资产初始入库", null, null);
        }
        auditLogService.record("资产建档", TARGET, item.getId(), AuditLogService.EXECUTED,
                "编号=" + code + " 名称=" + name + " 初始数量=" + initial);
        return item.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateItem(Long id, InvDtos.ItemSave req) {
        InvItem item = requireItem(id);
        String name = trimToNull(req.getName());
        if (name == null) {
            throw new BizException(400, "资产名称不能为空");
        }
        String code = trimToNull(req.getCode());
        if (code != null && !code.equals(item.getCode())) {
            assertCodeFree(code, id);
            item.setCode(code);
        }
        applyItemFields(item, req, name);
        // 数量走入库/状态调整,编辑不改数量
        InvItem patch = new InvItem();
        patch.setCode(item.getCode());
        patch.setName(item.getName());
        patch.setUnit(item.getUnit());
        try {
            itemMapper.update(patch, new LambdaUpdateWrapper<InvItem>()
                    .eq(InvItem::getId, id)
                    .set(InvItem::getSpec, item.getSpec())
                    .set(InvItem::getCategory, item.getCategory())
                    .set(InvItem::getLocation, item.getLocation())
                    .set(InvItem::getRemark, item.getRemark()));
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "资产编号已存在(含已删除的资产):" + code);
        }
        auditLogService.record("资产编辑", TARGET, id, AuditLogService.EXECUTED, "编号=" + item.getCode() + " 名称=" + name);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long id) {
        InvItem item = requireItem(id);
        if (nz(item.getReservedQty()) > 0 || nz(item.getRentedQty()) > 0) {
            throw new BizException(400, "该资产还有已预订或出租中的数量,归还/取消后才能删除");
        }
        itemMapper.deleteById(id);
        auditLogService.record("资产删除", TARGET, id, AuditLogService.EXECUTED, "编号=" + item.getCode());
    }

    /** 入库/送修/修好/报废 */
    @Transactional(rollbackFor = Exception.class)
    public Long adjust(Long itemId, InvDtos.AdjustRequest req) {
        InvItem item = requireItem(itemId);
        int n = positive(req.getQty(), "数量");
        String type = trimToNull(req.getType());
        if (M_IN.equals(type)) {
            int updated = itemMapper.update(null, new LambdaUpdateWrapper<InvItem>()
                    .setSql("total_qty = total_qty + " + n)
                    .setSql("stock_qty = stock_qty + " + n)
                    .eq(InvItem::getId, itemId));
            if (updated != 1) {
                throw new BizException(404, "资产不存在");
            }
        } else if (M_TO_REPAIR.equals(type)) {
            moveQty(item, S_STOCK, S_REPAIR, n);
        } else if (M_REPAIRED.equals(type)) {
            moveQty(item, S_REPAIR, S_STOCK, n);
        } else if (M_SCRAP.equals(type)) {
            String from = trimToNull(req.getFromStatus()) == null ? S_STOCK : req.getFromStatus().trim();
            if (!S_STOCK.equals(from) && !S_REPAIR.equals(from)) {
                throw new BizException(400, "报废只能从「库存」或「维修中」转出");
            }
            moveQty(item, from, S_SCRAPPED, n);
        } else {
            throw new BizException(400, "调整类型须为 入库/送修/修好/报废");
        }
        Long mid = insertMovement(itemId, null, type, n, trimToNull(req.getRemark()), null, trimToNull(req.getConditionDesc()));
        auditLogService.record("资产" + type, TARGET, itemId, AuditLogService.EXECUTED, "编号=" + item.getCode() + " 数量=" + n);
        return mid;
    }

    // ============================== 出租 ==============================

    public PageResult<InvDtos.RentalView> listRentals(String keyword, String status, Long itemId, Long customerId, int page, int size) {
        String kw = trimToNull(keyword);
        String st = trimToNull(status);
        List<InvRental> rows = rentalMapper.selectList(new LambdaQueryWrapper<InvRental>()
                .eq(st != null, InvRental::getStatus, st)
                .eq(itemId != null, InvRental::getItemId, itemId)
                .eq(customerId != null, InvRental::getCustomerId, customerId)
                .and(kw != null, w -> w.like(InvRental::getRentalNo, kw).or().like(InvRental::getCustomerName, kw)
                        .or().like(InvRental::getInstallAddress, kw))
                .orderByDesc(InvRental::getId));
        return new PageResult<>(rows.size(), page, size, toRentalViews(slice(rows, page, size)));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long createRental(InvDtos.RentalSave req) {
        CurrentUser u = UserContext.require();
        if (req.getItemId() == null) {
            throw new BizException(400, "请选择资产");
        }
        InvItem item = requireItem(req.getItemId());
        int qty = positive(req.getQty(), "出租数量");
        InvRental r = new InvRental();
        r.setItemId(item.getId());
        applyRentalFields(r, req);
        String prefix = "CZ" + LocalDate.now().format(DAY);
        r.setRentalNo(nextCode(prefix, rentalMapper.maxCode(prefix)));
        r.setQty(qty);
        r.setOutQty(0);
        r.setReturnedQty(0);
        r.setStatus(R_RESERVED);
        r.setCreateByName(u.getUserName());

        moveQty(item, S_STOCK, S_RESERVED, qty);
        rentalMapper.insert(r);
        auditLogService.record("资产出租预订", TARGET, item.getId(), AuditLogService.EXECUTED,
                "出租单=" + r.getRentalNo() + " 客户=" + r.getCustomerName() + " 数量=" + qty);
        return r.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateRental(Long id, InvDtos.RentalSave req) {
        InvRental r = requireRental(id);
        assertActive(r);
        if (req.getItemId() != null && !req.getItemId().equals(r.getItemId())) {
            throw new BizException(400, "出租单不能更换资产,请取消后重新建单");
        }
        applyRentalFields(r, req);
        if (req.getQty() != null && req.getQty() != nz(r.getQty())) {
            int newQty = positive(req.getQty(), "出租数量");
            if (newQty < nz(r.getOutQty())) {
                throw new BizException(400, "出租数量不能少于已出库数量 " + r.getOutQty());
            }
            InvItem item = requireItem(r.getItemId());
            int diff = newQty - nz(r.getQty());
            if (diff > 0) {
                moveQty(item, S_STOCK, S_RESERVED, diff);
            } else {
                moveQty(item, S_RESERVED, S_STOCK, -diff);
            }
            r.setQty(newQty);
        }
        String status = rentalStatus(r);
        rentalMapper.update(null, new LambdaUpdateWrapper<InvRental>()
                .eq(InvRental::getId, id)
                .set(InvRental::getCustomerId, r.getCustomerId())
                .set(InvRental::getCustomerName, r.getCustomerName())
                .set(InvRental::getContractId, r.getContractId())
                .set(InvRental::getInstallAddress, r.getInstallAddress())
                .set(InvRental::getContact, r.getContact())
                .set(InvRental::getPhone, r.getPhone())
                .set(InvRental::getStartDate, r.getStartDate())
                .set(InvRental::getExpectedReturnDate, r.getExpectedReturnDate())
                .set(InvRental::getRemark, r.getRemark())
                .set(InvRental::getQty, r.getQty())
                .set(InvRental::getStatus, status)
                .set(R_RETURNED.equals(status), InvRental::getActualReturnDate, LocalDate.now()));
        auditLogService.record("资产出租编辑", TARGET, r.getItemId(), AuditLogService.EXECUTED,
                "出租单=" + r.getRentalNo() + " 客户=" + r.getCustomerName() + " 数量=" + r.getQty());
    }

    /** 取消/释放剩余预订:未出库的数量退回库存;一台都没出库 → 已取消,否则数量改为已出库数。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelRental(Long id) {
        InvRental r = requireRental(id);
        assertActive(r);
        int pending = nz(r.getQty()) - nz(r.getOutQty());
        if (pending <= 0) {
            throw new BizException(400, "该出租单已全部出库,没有可释放的预订数量");
        }
        InvItem item = requireItem(r.getItemId());
        moveQty(item, S_RESERVED, S_STOCK, pending);
        r.setQty(r.getOutQty());
        String status = nz(r.getOutQty()) == 0 ? R_CANCELLED : rentalStatus(r);
        rentalMapper.update(null, new LambdaUpdateWrapper<InvRental>()
                .eq(InvRental::getId, id)
                .set(nz(r.getOutQty()) > 0, InvRental::getQty, r.getOutQty())
                .set(InvRental::getStatus, status)
                .set(R_RETURNED.equals(status), InvRental::getActualReturnDate, LocalDate.now()));
        auditLogService.record(R_CANCELLED.equals(status) ? "资产出租取消" : "资产出租释放预订", TARGET, r.getItemId(),
                AuditLogService.EXECUTED, "出租单=" + r.getRentalNo() + " 释放数量=" + pending);
    }

    // ============================== 出库 / 归还 ==============================

    @Transactional(rollbackFor = Exception.class)
    public InvDtos.MovementResult outbound(Long rentalId, InvDtos.OutRequest req) {
        InvRental r = requireRental(rentalId);
        assertActive(r);
        int n = positive(req.getQty(), "出库数量");
        int pending = nz(r.getQty()) - nz(r.getOutQty());
        if (n > pending) {
            throw new BizException(400, "出库数量超出待出库数量 " + pending);
        }
        String level = checkLevel(req.getConditionLevel());
        int updated = rentalMapper.update(null, new LambdaUpdateWrapper<InvRental>()
                .setSql("out_qty = out_qty + " + n)
                .set(InvRental::getStatus, R_RENTED)
                .eq(InvRental::getId, rentalId)
                .apply("out_qty + {0} <= qty", n));
        if (updated != 1) {
            throw new BizException(409, "出租单已被他人更新,请刷新后重试");
        }
        InvItem item = requireItem(r.getItemId());
        moveQty(item, S_RESERVED, S_RENTED, n);

        InvMovement m = newMovement(r.getItemId(), rentalId, M_OUT, n, trimToNull(req.getRemark()));
        m.setAccessories(trimToNull(req.getAccessories()));
        m.setConditionLevel(level);
        m.setConditionDesc(trimToNull(req.getConditionDesc()));
        movementMapper.insert(m);
        auditLogService.record("资产出库", TARGET, r.getItemId(), AuditLogService.EXECUTED,
                "出租单=" + r.getRentalNo() + " 数量=" + n);

        InvDtos.MovementResult res = new InvDtos.MovementResult();
        res.setMovementId(m.getId());
        res.setCompensationTotal(BigDecimal.ZERO);
        res.setRentalStatus(R_RENTED);
        return res;
    }

    @Transactional(rollbackFor = Exception.class)
    public InvDtos.MovementResult returnBack(Long rentalId, InvDtos.ReturnRequest req) {
        InvRental r = requireRental(rentalId);
        assertActive(r);
        int n = positive(req.getQty(), "归还数量");
        int onSite = nz(r.getOutQty()) - nz(r.getReturnedQty());
        if (n > onSite) {
            throw new BizException(400, "归还数量超出在外数量 " + onSite);
        }
        int[] split = resolveReturnSplit(n, req.getGoodQty(), req.getRepairQty(), req.getScrapQty());
        String level = checkLevel(req.getConditionLevel());
        List<String> unpriced = new ArrayList<>();
        List<InvDamage> damages = buildDamages(req.getDamages(), priceMapper.selectList(new LambdaQueryWrapper<>()), unpriced);
        BigDecimal compensation = sumAmount(damages);

        int updated = rentalMapper.update(null, new LambdaUpdateWrapper<InvRental>()
                .setSql("returned_qty = returned_qty + " + n)
                .eq(InvRental::getId, rentalId)
                .apply("returned_qty + {0} <= out_qty", n));
        if (updated != 1) {
            throw new BizException(409, "出租单已被他人更新,请刷新后重试");
        }
        int itemUpdated = itemMapper.update(null, new LambdaUpdateWrapper<InvItem>()
                .setSql("rented_qty = rented_qty - " + n)
                .setSql("stock_qty = stock_qty + " + split[0])
                .setSql("repair_qty = repair_qty + " + split[1])
                .setSql("scrapped_qty = scrapped_qty + " + split[2])
                .eq(InvItem::getId, r.getItemId())
                .apply("rented_qty >= {0}", n));
        if (itemUpdated != 1) {
            throw new BizException(409, "资产出租中数量不足,请刷新后重试");
        }
        r.setReturnedQty(nz(r.getReturnedQty()) + n);
        String status = rentalStatus(r);
        rentalMapper.update(null, new LambdaUpdateWrapper<InvRental>()
                .eq(InvRental::getId, rentalId)
                .set(InvRental::getStatus, status)
                .set(R_RETURNED.equals(status), InvRental::getActualReturnDate, LocalDate.now()));

        InvMovement m = newMovement(r.getItemId(), rentalId, M_RETURN, n, trimToNull(req.getRemark()));
        m.setGoodQty(split[0]);
        m.setRepairQty(split[1]);
        m.setScrapQty(split[2]);
        m.setAccessories(trimToNull(req.getAccessories()));
        m.setConditionLevel(level);
        m.setConditionDesc(trimToNull(req.getConditionDesc()));
        m.setCompensationTotal(compensation);
        movementMapper.insert(m);
        for (InvDamage d : damages) {
            d.setMovementId(m.getId());
            d.setRentalId(rentalId);
            d.setItemId(r.getItemId());
            damageMapper.insert(d);
        }
        auditLogService.record("资产归还", TARGET, r.getItemId(), AuditLogService.EXECUTED,
                "出租单=" + r.getRentalNo() + " 数量=" + n + " 完好/维修/报废=" + split[0] + "/" + split[1] + "/" + split[2]
                        + " 赔偿=" + compensation);

        InvDtos.MovementResult res = new InvDtos.MovementResult();
        res.setMovementId(m.getId());
        res.setCompensationTotal(compensation);
        res.setUnpricedParts(unpriced);
        res.setRentalStatus(status);
        return res;
    }

    public PageResult<InvDtos.MovementView> listMovements(Long itemId, Long rentalId, String type, int page, int size) {
        String t = trimToNull(type);
        List<InvMovement> rows = movementMapper.selectList(new LambdaQueryWrapper<InvMovement>()
                .eq(itemId != null, InvMovement::getItemId, itemId)
                .eq(rentalId != null, InvMovement::getRentalId, rentalId)
                .eq(t != null, InvMovement::getType, t)
                .orderByDesc(InvMovement::getId));
        return new PageResult<>(rows.size(), page, size, toMovementViews(slice(rows, page, size)));
    }

    // ============================== 损坏缺件 / 价目 / 企业信息 ==============================

    public PageResult<InvDtos.DamageView> listDamages(String settleStatus, String keyword, int page, int size) {
        String st = trimToNull(settleStatus);
        String kw = trimToNull(keyword);
        List<InvDamage> rows = damageMapper.selectList(new LambdaQueryWrapper<InvDamage>()
                .eq(st != null, InvDamage::getSettleStatus, st)
                .orderByDesc(InvDamage::getId));
        List<InvDtos.DamageView> views = toDamageViews(rows);
        if (kw != null) {
            views = views.stream().filter(v -> contains(v.getPartName(), kw) || contains(v.getCustomerName(), kw)
                    || contains(v.getRentalNo(), kw) || contains(v.getItemName(), kw) || contains(v.getItemCode(), kw))
                    .collect(Collectors.toList());
        }
        return new PageResult<>(views.size(), page, size, slice(views, page, size));
    }

    @Transactional(rollbackFor = Exception.class)
    public void settleDamage(Long id, InvDtos.SettleRequest req) {
        InvDamage d = damageMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "损坏缺件记录不存在");
        }
        String st = trimToNull(req.getSettleStatus());
        if (st == null || !SETTLE_STATUSES.contains(st)) {
            throw new BizException(400, "处理状态须为 待收取/已收取/已减免");
        }
        damageMapper.update(null, new LambdaUpdateWrapper<InvDamage>()
                .eq(InvDamage::getId, id)
                .set(InvDamage::getSettleStatus, st)
                .set(req.getRemark() != null, InvDamage::getRemark, trimToNull(req.getRemark())));
        auditLogService.record("赔偿处理", TARGET, d.getItemId(), AuditLogService.EXECUTED,
                "检查项=" + d.getPartName() + " 金额=" + d.getAmount() + " → " + st);
    }

    public List<InvDtos.PriceRow> listPrices() {
        List<InvCompPrice> rows = priceMapper.selectList(new LambdaQueryWrapper<>());
        rows.sort(Comparator.comparing((InvCompPrice p) -> nz(p.getSortNo())).thenComparing(InvCompPrice::getId));
        List<InvDtos.PriceRow> out = new ArrayList<>();
        for (InvCompPrice p : rows) {
            InvDtos.PriceRow v = new InvDtos.PriceRow();
            v.setId(p.getId());
            v.setPartName(p.getPartName());
            v.setUnit(p.getUnit());
            v.setDamagePrice(p.getDamagePrice());
            v.setMissingPrice(p.getMissingPrice());
            v.setSortNo(p.getSortNo());
            out.add(v);
        }
        return out;
    }

    /** 整表保存价目:带 id 的更新,不带 id 的新增,表里没有的删除。已登记的赔偿按当时单价快照,不受影响。 */
    @Transactional(rollbackFor = Exception.class)
    public void savePrices(List<InvDtos.PriceRow> rows) {
        List<InvDtos.PriceRow> list = rows == null ? Collections.emptyList() : rows;
        Set<String> names = new HashSet<>();
        for (InvDtos.PriceRow r : list) {
            String name = trimToNull(r.getPartName());
            if (name == null) {
                throw new BizException(400, "检查项名称不能为空");
            }
            if (!names.add(name)) {
                throw new BizException(400, "检查项重复:" + name);
            }
            if (negative(r.getDamagePrice()) || negative(r.getMissingPrice())) {
                throw new BizException(400, "检查项「" + name + "」单价不能为负");
            }
        }
        Map<Long, InvCompPrice> existing = priceMapper.selectList(new LambdaQueryWrapper<InvCompPrice>()).stream()
                .collect(Collectors.toMap(InvCompPrice::getId, Function.identity()));
        Set<Long> kept = new HashSet<>();
        int sort = 1;
        for (InvDtos.PriceRow r : list) {
            InvCompPrice p = r.getId() == null ? null : existing.get(r.getId());
            boolean isNew = p == null;
            if (isNew) {
                p = new InvCompPrice();
            }
            p.setPartName(r.getPartName().trim());
            p.setUnit(trimToNull(r.getUnit()) == null ? "个" : r.getUnit().trim());
            p.setDamagePrice(r.getDamagePrice() == null ? BigDecimal.ZERO : r.getDamagePrice());
            p.setMissingPrice(r.getMissingPrice() == null ? BigDecimal.ZERO : r.getMissingPrice());
            p.setSortNo(sort++);
            if (isNew) {
                priceMapper.insert(p);
            } else {
                priceMapper.updateById(p);
                kept.add(p.getId());
            }
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                priceMapper.deleteById(id);
            }
        }
        auditLogService.record("赔偿价目保存", "inv_comp_price", null, AuditLogService.EXECUTED, "共 " + list.size() + " 项");
    }

    public InvDtos.CompanyInfo company() {
        InvCompany c = companyMapper.selectById(1L);
        InvDtos.CompanyInfo info = new InvDtos.CompanyInfo();
        if (c == null) {
            info.setCompanyName("曜石科技");
            return info;
        }
        info.setCompanyName(c.getCompanyName());
        info.setPhone(c.getPhone());
        info.setAddress(c.getAddress());
        info.setWebsite(c.getWebsite());
        info.setNotice(c.getNotice());
        return info;
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveCompany(InvDtos.CompanyInfo req) {
        String name = trimToNull(req.getCompanyName());
        if (name == null) {
            throw new BizException(400, "企业名称不能为空");
        }
        InvCompany c = new InvCompany();
        c.setId(1L);
        c.setCompanyName(name);
        c.setPhone(trimToNull(req.getPhone()));
        c.setAddress(trimToNull(req.getAddress()));
        c.setWebsite(trimToNull(req.getWebsite()));
        c.setNotice(trimToNull(req.getNotice()));
        if (companyMapper.selectById(1L) == null) {
            companyMapper.insert(c);
        } else {
            companyMapper.update(c, new LambdaUpdateWrapper<InvCompany>()
                    .eq(InvCompany::getId, 1L)
                    .set(InvCompany::getPhone, c.getPhone())
                    .set(InvCompany::getAddress, c.getAddress())
                    .set(InvCompany::getWebsite, c.getWebsite())
                    .set(InvCompany::getNotice, c.getNotice()));
        }
        auditLogService.record("资产标签企业信息保存", "inv_company", 1L, AuditLogService.EXECUTED, "企业名称=" + name);
    }

    // ============================== 提醒与报表 ==============================

    public InvDtos.Overview overview() {
        return overview(LocalDate.now());
    }

    InvDtos.Overview overview(LocalDate today) {
        InvDtos.Overview o = new InvDtos.Overview();
        List<InvItem> items = itemMapper.selectList(new LambdaQueryWrapper<>());
        Map<Long, InvItem> itemById = new HashMap<>();
        for (InvItem it : items) {
            itemById.put(it.getId(), it);
            o.setItemCount(o.getItemCount() + 1);
            o.setTotalQty(o.getTotalQty() + nz(it.getTotalQty()));
            o.setStockQty(o.getStockQty() + nz(it.getStockQty()));
            o.setReservedQty(o.getReservedQty() + nz(it.getReservedQty()));
            o.setRentedQty(o.getRentedQty() + nz(it.getRentedQty()));
            o.setRepairQty(o.getRepairQty() + nz(it.getRepairQty()));
            o.setScrappedQty(o.getScrappedQty() + nz(it.getScrappedQty()));
            if (nz(it.getRepairQty()) > 0) {
                o.getReminders().add(reminder("需要维修", "warning", itemLabel(it) + " 维修中 " + it.getRepairQty() + " " + unit(it),
                        "存放位置:" + dash(it.getLocation()), it.getId(), null, null));
            }
        }
        o.setInStoreQty(o.getStockQty() + o.getReservedQty());
        o.setIdleQty(o.getStockQty());

        List<InvRental> active = rentalMapper.selectList(new LambdaQueryWrapper<InvRental>()
                .in(InvRental::getStatus, Arrays.asList(R_RESERVED, R_RENTED)));
        o.setActiveRentalCount(active.size());
        Map<Long, Contract> contracts = loadContracts(active);
        for (InvRental r : active) {
            InvItem it = itemById.get(r.getItemId());
            String label = r.getCustomerName() + " · " + (it == null ? "资产#" + r.getItemId() : itemLabel(it));
            int onSite = nz(r.getOutQty()) - nz(r.getReturnedQty());
            LocalDate expected = r.getExpectedReturnDate();
            if (expected != null && onSite > 0 && expected.isBefore(today)) {
                long days = ChronoUnit.DAYS.between(expected, today);
                o.setOverdueRentalCount(o.getOverdueRentalCount() + 1);
                o.getReminders().add(reminder("设备逾期", "danger", label + " 逾期 " + days + " 天未归还",
                        "出租单 " + r.getRentalNo() + ",在外 " + onSite + ",预计归还 " + expected, r.getItemId(), r.getId(), expected));
            } else if (expected != null && !expected.isBefore(today) && !expected.isAfter(today.plusDays(RETURN_REMIND_DAYS))) {
                long days = ChronoUnit.DAYS.between(today, expected);
                o.getReminders().add(reminder("即将归还", "warning", label + (days == 0 ? " 今天到期归还" : " " + days + " 天后到期归还"),
                        "出租单 " + r.getRentalNo() + ",预计归还 " + expected, r.getItemId(), r.getId(), expected));
            }
            Contract c = r.getContractId() == null ? null : contracts.get(r.getContractId());
            LocalDate end = c == null ? null : contractEnd(c.getStartDate(), c.getTermMonths());
            if (end != null && !end.isAfter(today.plusDays(CONTRACT_REMIND_DAYS))) {
                boolean passed = end.isBefore(today);
                long days = Math.abs(ChronoUnit.DAYS.between(today, end));
                o.getReminders().add(reminder("合同到期", passed ? "danger" : "warning",
                        label + " 合同 " + c.getNo() + (passed ? " 已到期 " + days + " 天" : (days == 0 ? " 今天到期" : " " + days + " 天后到期")),
                        "合同到期日 " + end + ",出租单 " + r.getRentalNo(), r.getItemId(), r.getId(), end));
            }
        }

        List<InvDamage> pending = damageMapper.selectList(new LambdaQueryWrapper<InvDamage>()
                .eq(InvDamage::getSettleStatus, SETTLE_PENDING));
        BigDecimal pendingTotal = sumAmount(pending);
        o.setPendingCompensation(pendingTotal);
        if (!pending.isEmpty()) {
            o.getReminders().add(reminder("赔偿待收", "info", "损坏缺件赔偿待收取 ¥" + pendingTotal.toPlainString(),
                    "共 " + pending.size() + " 项待处理", null, null, null));
        }
        Map<String, Integer> levelRank = new HashMap<>();
        levelRank.put("danger", 0);
        levelRank.put("warning", 1);
        levelRank.put("info", 2);
        o.getReminders().sort(Comparator.comparing((InvDtos.Reminder x) -> levelRank.getOrDefault(x.getLevel(), 9))
                .thenComparing(x -> x.getDate() == null ? LocalDate.MAX : x.getDate()));
        return o;
    }

    // ============================== 扫码 ==============================

    public InvDtos.PublicScanView publicScan(String token) {
        InvItem item = requireByToken(token);
        InvDtos.PublicScanView v = new InvDtos.PublicScanView();
        v.setCompany(company());
        v.setCode(item.getCode());
        v.setName(item.getName());
        v.setSpec(item.getSpec());
        v.setCategory(item.getCategory());
        v.setUnit(item.getUnit());
        return v;
    }

    public InvDtos.ScanView scan(String token) {
        CurrentUser u = UserContext.require();
        InvItem item = requireByToken(token);
        InvDtos.ScanView v = new InvDtos.ScanView();
        v.setCompany(company());
        v.setItem(toItemViews(Collections.singletonList(item)).get(0));
        v.setActiveRentals(toRentalViews(rentalMapper.selectList(new LambdaQueryWrapper<InvRental>()
                .eq(InvRental::getItemId, item.getId())
                .in(InvRental::getStatus, Arrays.asList(R_RESERVED, R_RENTED))
                .orderByAsc(InvRental::getExpectedReturnDate))));
        v.setPrices(listPrices());
        v.setCanOperate(canOperate(u.getRole()));
        return v;
    }

    /** 上传资产/出入库照片前校验目标存在与角色 */
    public void assertCanAttach(String bizType, Long bizId) {
        CurrentUser u = UserContext.require();
        if (!canOperate(u.getRole())) {
            throw new BizException(403, "当前角色无权上传资产照片(需 老板/供应链/业务)");
        }
        boolean exists = bizId != null && (BIZ_ITEM.equals(bizType)
                ? itemMapper.selectById(bizId) != null
                : movementMapper.selectById(bizId) != null);
        if (!exists) {
            throw new BizException(404, BIZ_ITEM.equals(bizType) ? "请先保存资产再上传照片" : "出入库记录不存在");
        }
    }

    // ============================== 内部 ==============================

    private void moveQty(InvItem item, String from, String to, int n) {
        String f = column(from);
        String t = column(to);
        int updated = itemMapper.update(null, new LambdaUpdateWrapper<InvItem>()
                .setSql(f + " = " + f + " - " + n)
                .setSql(t + " = " + t + " + " + n)
                .eq(InvItem::getId, item.getId())
                .apply(f + " >= {0}", n));
        if (updated != 1) {
            InvItem fresh = itemMapper.selectById(item.getId());
            int available = fresh == null ? 0 : qtyOf(fresh, from);
            throw new BizException(400, itemLabel(item) + "「" + from + "」数量不足:当前 " + available + ",需要 " + n);
        }
    }

    static int qtyOf(InvItem it, String status) {
        switch (status) {
            case S_STOCK: return nz(it.getStockQty());
            case S_RESERVED: return nz(it.getReservedQty());
            case S_RENTED: return nz(it.getRentedQty());
            case S_REPAIR: return nz(it.getRepairQty());
            case S_SCRAPPED: return nz(it.getScrappedQty());
            default: return 0;
        }
    }

    private Long insertMovement(Long itemId, Long rentalId, String type, int qty, String remark, String level, String conditionDesc) {
        InvMovement m = newMovement(itemId, rentalId, type, qty, remark);
        m.setConditionLevel(level);
        m.setConditionDesc(conditionDesc);
        movementMapper.insert(m);
        return m.getId();
    }

    private InvMovement newMovement(Long itemId, Long rentalId, String type, int qty, String remark) {
        CurrentUser u = UserContext.require();
        InvMovement m = new InvMovement();
        m.setItemId(itemId);
        m.setRentalId(rentalId);
        m.setType(type);
        m.setQty(qty);
        m.setGoodQty(0);
        m.setRepairQty(0);
        m.setScrapQty(0);
        m.setCompensationTotal(BigDecimal.ZERO);
        m.setOperatorId(u.getUserId());
        m.setOperatorName(u.getUserName());
        m.setOpTime(LocalDateTime.now());
        m.setRemark(remark);
        return m;
    }

    private void applyItemFields(InvItem item, InvDtos.ItemSave req, String name) {
        item.setName(name);
        item.setSpec(trimToNull(req.getSpec()));
        item.setCategory(trimToNull(req.getCategory()));
        item.setUnit(trimToNull(req.getUnit()) == null ? "套" : req.getUnit().trim());
        item.setLocation(trimToNull(req.getLocation()));
        item.setRemark(trimToNull(req.getRemark()));
    }

    private void applyRentalFields(InvRental r, InvDtos.RentalSave req) {
        if (req.getCustomerId() != null) {
            Customer c = customerMapper.selectById(req.getCustomerId());
            if (c == null) {
                throw new BizException(404, "客户不存在,请在客户 CRM 中选择");
            }
            r.setCustomerId(c.getId());
            r.setCustomerName(c.getName());
        } else {
            String name = trimToNull(req.getCustomerName());
            if (name == null) {
                throw new BizException(400, "请选择或填写客户");
            }
            r.setCustomerId(null);
            r.setCustomerName(name);
        }
        if (req.getContractId() != null && contractMapper.selectById(req.getContractId()) == null) {
            throw new BizException(404, "关联合同不存在");
        }
        r.setContractId(req.getContractId());
        if (req.getStartDate() == null || req.getExpectedReturnDate() == null) {
            throw new BizException(400, "开始时间和预计归还时间必填");
        }
        if (req.getExpectedReturnDate().isBefore(req.getStartDate())) {
            throw new BizException(400, "预计归还时间不能早于开始时间");
        }
        r.setStartDate(req.getStartDate());
        r.setExpectedReturnDate(req.getExpectedReturnDate());
        r.setInstallAddress(trimToNull(req.getInstallAddress()));
        r.setContact(trimToNull(req.getContact()));
        r.setPhone(trimToNull(req.getPhone()));
        r.setRemark(trimToNull(req.getRemark()));
    }

    private void assertCodeFree(String code, Long selfId) {
        Long count = itemMapper.selectCount(new LambdaQueryWrapper<InvItem>()
                .eq(InvItem::getCode, code)
                .ne(selfId != null, InvItem::getId, selfId));
        if (count != null && count > 0) {
            throw new BizException(400, "资产编号已存在:" + code);
        }
    }

    private static void assertActive(InvRental r) {
        if (!R_RESERVED.equals(r.getStatus()) && !R_RENTED.equals(r.getStatus())) {
            throw new BizException(400, "出租单「" + r.getRentalNo() + "」状态为" + r.getStatus() + ",不能再操作");
        }
    }

    private static String checkLevel(String level) {
        String l = trimToNull(level);
        if (l != null && !CONDITION_LEVELS.contains(l)) {
            throw new BizException(400, "设备状况须为 完好/轻微损坏/损坏");
        }
        return l;
    }

    private InvItem requireItem(Long id) {
        InvItem item = id == null ? null : itemMapper.selectById(id);
        if (item == null) {
            throw new BizException(404, "资产不存在");
        }
        return item;
    }

    private InvItem requireByToken(String token) {
        String t = trimToNull(token);
        InvItem item = t == null ? null : itemMapper.selectOne(new LambdaQueryWrapper<InvItem>().eq(InvItem::getQrToken, t));
        if (item == null) {
            throw new BizException(404, "二维码无效或资产已删除");
        }
        return item;
    }

    private InvRental requireRental(Long id) {
        InvRental r = id == null ? null : rentalMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "出租单不存在");
        }
        return r;
    }

    private List<InvDtos.ItemView> toItemViews(List<InvItem> rows) {
        Map<Long, Integer> photos = countFiles(BIZ_ITEM, rows.stream().map(InvItem::getId).collect(Collectors.toList()));
        List<InvDtos.ItemView> out = new ArrayList<>();
        for (InvItem it : rows) {
            InvDtos.ItemView v = new InvDtos.ItemView();
            v.setId(it.getId());
            v.setCode(it.getCode());
            v.setName(it.getName());
            v.setSpec(it.getSpec());
            v.setCategory(it.getCategory());
            v.setUnit(it.getUnit());
            v.setLocation(it.getLocation());
            v.setRemark(it.getRemark());
            v.setTotalQty(nz(it.getTotalQty()));
            v.setStockQty(nz(it.getStockQty()));
            v.setReservedQty(nz(it.getReservedQty()));
            v.setRentedQty(nz(it.getRentedQty()));
            v.setRepairQty(nz(it.getRepairQty()));
            v.setScrappedQty(nz(it.getScrappedQty()));
            v.setInStoreQty(nz(it.getStockQty()) + nz(it.getReservedQty()));
            v.setQrToken(it.getQrToken());
            v.setPhotoCount(photos.getOrDefault(it.getId(), 0));
            v.setCreateByName(it.getCreateByName());
            v.setCreateTime(it.getCreateTime());
            out.add(v);
        }
        return out;
    }

    private List<InvDtos.RentalView> toRentalViews(List<InvRental> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, InvItem> items = loadItems(rows.stream().map(InvRental::getItemId).collect(Collectors.toSet()));
        Map<Long, Contract> contracts = loadContracts(rows);
        Map<Long, BigDecimal> comp = new HashMap<>();
        for (InvDamage d : damageMapper.selectList(new LambdaQueryWrapper<InvDamage>()
                .in(InvDamage::getRentalId, rows.stream().map(InvRental::getId).collect(Collectors.toList())))) {
            comp.merge(d.getRentalId(), d.getAmount() == null ? BigDecimal.ZERO : d.getAmount(), BigDecimal::add);
        }
        LocalDate today = LocalDate.now();
        List<InvDtos.RentalView> out = new ArrayList<>();
        for (InvRental r : rows) {
            InvDtos.RentalView v = new InvDtos.RentalView();
            v.setId(r.getId());
            v.setRentalNo(r.getRentalNo());
            v.setItemId(r.getItemId());
            InvItem it = items.get(r.getItemId());
            if (it != null) {
                v.setItemCode(it.getCode());
                v.setItemName(it.getName());
                v.setItemSpec(it.getSpec());
                v.setUnit(it.getUnit());
            }
            v.setCustomerId(r.getCustomerId());
            v.setCustomerName(r.getCustomerName());
            v.setContractId(r.getContractId());
            Contract c = r.getContractId() == null ? null : contracts.get(r.getContractId());
            if (c != null) {
                v.setContractNo(c.getNo());
                v.setContractEndDate(contractEnd(c.getStartDate(), c.getTermMonths()));
            }
            v.setInstallAddress(r.getInstallAddress());
            v.setContact(r.getContact());
            v.setPhone(r.getPhone());
            v.setQty(nz(r.getQty()));
            v.setOutQty(nz(r.getOutQty()));
            v.setReturnedQty(nz(r.getReturnedQty()));
            v.setPendingOutQty(Math.max(0, nz(r.getQty()) - nz(r.getOutQty())));
            v.setOnSiteQty(Math.max(0, nz(r.getOutQty()) - nz(r.getReturnedQty())));
            v.setStartDate(r.getStartDate());
            v.setExpectedReturnDate(r.getExpectedReturnDate());
            v.setActualReturnDate(r.getActualReturnDate());
            v.setStatus(r.getStatus());
            if (R_RENTED.equals(r.getStatus()) && r.getExpectedReturnDate() != null
                    && r.getExpectedReturnDate().isBefore(today) && v.getOnSiteQty() > 0) {
                v.setOverdueDays(ChronoUnit.DAYS.between(r.getExpectedReturnDate(), today));
            }
            v.setCompensationTotal(comp.getOrDefault(r.getId(), BigDecimal.ZERO));
            v.setRemark(r.getRemark());
            v.setCreateByName(r.getCreateByName());
            v.setCreateTime(r.getCreateTime());
            out.add(v);
        }
        return out;
    }

    private List<InvDtos.MovementView> toMovementViews(List<InvMovement> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = rows.stream().map(InvMovement::getId).collect(Collectors.toList());
        Map<Long, InvItem> items = loadItems(rows.stream().map(InvMovement::getItemId).collect(Collectors.toSet()));
        Set<Long> rentalIds = rows.stream().map(InvMovement::getRentalId).filter(x -> x != null).collect(Collectors.toSet());
        Map<Long, InvRental> rentals = rentalIds.isEmpty() ? Collections.emptyMap()
                : rentalMapper.selectBatchIds(rentalIds).stream().collect(Collectors.toMap(InvRental::getId, Function.identity()));
        Map<Long, List<InvDtos.DamageView>> damages = toDamageViews(damageMapper.selectList(new LambdaQueryWrapper<InvDamage>()
                .in(InvDamage::getMovementId, ids))).stream().collect(Collectors.groupingBy(InvDtos.DamageView::getMovementId));
        Map<Long, Integer> photos = countFiles(BIZ_MOVEMENT, ids);
        List<InvDtos.MovementView> out = new ArrayList<>();
        for (InvMovement m : rows) {
            InvDtos.MovementView v = new InvDtos.MovementView();
            v.setId(m.getId());
            v.setItemId(m.getItemId());
            InvItem it = items.get(m.getItemId());
            if (it != null) {
                v.setItemCode(it.getCode());
                v.setItemName(it.getName());
            }
            v.setRentalId(m.getRentalId());
            InvRental r = m.getRentalId() == null ? null : rentals.get(m.getRentalId());
            if (r != null) {
                v.setRentalNo(r.getRentalNo());
                v.setCustomerName(r.getCustomerName());
            }
            v.setType(m.getType());
            v.setQty(nz(m.getQty()));
            v.setGoodQty(nz(m.getGoodQty()));
            v.setRepairQty(nz(m.getRepairQty()));
            v.setScrapQty(nz(m.getScrapQty()));
            v.setAccessories(m.getAccessories());
            v.setConditionLevel(m.getConditionLevel());
            v.setConditionDesc(m.getConditionDesc());
            v.setCompensationTotal(m.getCompensationTotal());
            v.setOperatorName(m.getOperatorName());
            v.setOpTime(m.getOpTime());
            v.setRemark(m.getRemark());
            v.setPhotoCount(photos.getOrDefault(m.getId(), 0));
            v.setDamages(damages.getOrDefault(m.getId(), new ArrayList<>()));
            out.add(v);
        }
        return out;
    }

    private List<InvDtos.DamageView> toDamageViews(List<InvDamage> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, InvItem> items = loadItems(rows.stream().map(InvDamage::getItemId).collect(Collectors.toSet()));
        Set<Long> rentalIds = rows.stream().map(InvDamage::getRentalId).filter(x -> x != null).collect(Collectors.toSet());
        Map<Long, InvRental> rentals = rentalIds.isEmpty() ? Collections.emptyMap()
                : rentalMapper.selectBatchIds(rentalIds).stream().collect(Collectors.toMap(InvRental::getId, Function.identity()));
        List<InvDtos.DamageView> out = new ArrayList<>();
        for (InvDamage d : rows) {
            InvDtos.DamageView v = new InvDtos.DamageView();
            v.setId(d.getId());
            v.setMovementId(d.getMovementId());
            v.setRentalId(d.getRentalId());
            InvRental r = d.getRentalId() == null ? null : rentals.get(d.getRentalId());
            if (r != null) {
                v.setRentalNo(r.getRentalNo());
                v.setCustomerName(r.getCustomerName());
            }
            v.setItemId(d.getItemId());
            InvItem it = items.get(d.getItemId());
            if (it != null) {
                v.setItemCode(it.getCode());
                v.setItemName(it.getName());
            }
            v.setPartName(d.getPartName());
            v.setDamagedQty(nz(d.getDamagedQty()));
            v.setMissingQty(nz(d.getMissingQty()));
            v.setDamagePrice(d.getDamagePrice());
            v.setMissingPrice(d.getMissingPrice());
            v.setAmount(d.getAmount());
            v.setSettleStatus(d.getSettleStatus());
            v.setRemark(d.getRemark());
            v.setCreateTime(d.getCreateTime());
            out.add(v);
        }
        return out;
    }

    private Map<Long, InvItem> loadItems(Collection<Long> ids) {
        Set<Long> set = ids.stream().filter(x -> x != null).collect(Collectors.toSet());
        if (set.isEmpty()) {
            return Collections.emptyMap();
        }
        return itemMapper.selectBatchIds(set).stream().collect(Collectors.toMap(InvItem::getId, Function.identity()));
    }

    private Map<Long, Contract> loadContracts(List<InvRental> rentals) {
        Set<Long> ids = rentals.stream().map(InvRental::getContractId).filter(x -> x != null).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return contractMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(Contract::getId, Function.identity()));
    }

    private Map<Long, Integer> countFiles(String bizType, List<Long> bizIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (bizIds.isEmpty()) {
            return out;
        }
        for (FileObject fo : fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getBizType, bizType)
                .in(FileObject::getBizId, bizIds)
                .eq(FileObject::getIsDeleted, 0))) {
            out.merge(fo.getBizId(), 1, Integer::sum);
        }
        return out;
    }

    private static InvDtos.Reminder reminder(String type, String level, String title, String detail, Long itemId, Long rentalId, LocalDate date) {
        InvDtos.Reminder r = new InvDtos.Reminder();
        r.setType(type);
        r.setLevel(level);
        r.setTitle(title);
        r.setDetail(detail);
        r.setItemId(itemId);
        r.setRentalId(rentalId);
        r.setDate(date);
        return r;
    }

    private static <T> List<T> slice(List<T> rows, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, size);
        int from = (p - 1) * s;
        if (from >= rows.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + s)));
    }

    private static String itemLabel(InvItem it) {
        return it.getName() + (it.getSpec() == null ? "" : "(" + it.getSpec() + ")") + " " + it.getCode();
    }

    private static String unit(InvItem it) {
        return it.getUnit() == null ? "套" : it.getUnit();
    }

    private static String dash(String s) {
        return s == null ? "—" : s;
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.contains(kw);
    }

    private static boolean negative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
