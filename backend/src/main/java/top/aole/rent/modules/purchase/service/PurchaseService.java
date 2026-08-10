package top.aole.rent.modules.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.dto.PurchaseDetailResponse;
import top.aole.rent.modules.purchase.dto.PurchaseListItem;
import top.aole.rent.modules.purchase.dto.PurchaseOrderRequest;
import top.aole.rent.modules.purchase.dto.ReturnRequest;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseItemMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购服务(M1-12/13)。下单(先签约后采购校验)→入库(逐件生成 asset)→应付计划;退货红冲(设备报废释放+应付红字)。
 *
 * <p><b>单一真相源(§4.24)</b>:
 * <ul>
 *   <li>设备状态机 owner={@link AssetService}:入库生成设备/退货报废均调其方法,本服务不直写 asset.status。</li>
 *   <li>应付计划 {@code payable} 待付=层级②"负债"口径(M3 兑付缺口扫描读此·为其留字段)。</li>
 *   <li>先签约后采购:下单必绑一份存续合同(草稿/生效),已作废合同拒绝建单。</li>
 * </ul>
 * <p>应付分段:首付(下单付·到期=下单日)/验收(入库付·到期=入库日)/尾款(账期后付·到期=入库日+账期天数),
 * 三段合计=采购总额;比例走 rule_config[payable_stage_ratio],禁硬编码。
 * 敏感成本(集采价/应付金额)对 GP/LP 打码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseInMapper purchaseInMapper;
    private final PurchaseItemMapper purchaseItemMapper;
    private final PayableMapper payableMapper;
    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final SupplierMapper supplierMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final VoucherService voucherService;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    // ============ 列表 ============

    public PageResult<PurchaseListItem> list(String status, Long contractId, String keyword, int page, int size) {
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());
        LambdaQueryWrapper<PurchaseIn> qw = new LambdaQueryWrapper<PurchaseIn>()
                .eq(status != null && !status.isEmpty(), PurchaseIn::getStatus, status)
                .eq(contractId != null, PurchaseIn::getContractId, contractId)
                .and(keyword != null && !keyword.trim().isEmpty(), w -> w.like(PurchaseIn::getNo, keyword.trim()))
                .orderByDesc(PurchaseIn::getId);
        List<PurchaseIn> all = purchaseInMapper.selectList(qw);

        List<PurchaseListItem> items = new ArrayList<>();
        for (PurchaseIn p : all) {
            PurchaseListItem it = new PurchaseListItem();
            it.setId(p.getId());
            it.setNo(p.getNo());
            it.setStatus(p.getStatus());
            it.setContractId(p.getContractId());
            it.setContractNo(contractNo(p.getContractId()));
            it.setCustomerName(customerNameOfContract(p.getContractId()));
            it.setSupplierId(p.getSupplierId());
            it.setSupplierName(supplierName(p.getSupplierId()));
            it.setTotalAmount(seeCost ? p.getTotalAmount() : null);
            it.setItemCount(itemsOf(p.getId()).size());
            it.setOrderDate(p.getOrderDate());
            it.setReceiveDate(p.getReceiveDate());
            it.setPayableOutstanding(seeCost ? outstanding(p.getId()) : null);
            it.setSensitiveMasked(!seeCost);
            items.add(it);
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<PurchaseListItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    // ============ 下单(先签约后采购) ============

    @Transactional
    public Long order(PurchaseOrderRequest req) {
        // 先签约后采购:合同必存在且未作废
        Contract c = contractMapper.selectById(req.getContractId());
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "合同不存在,不允许建采购单(先签约后采购): contractId=" + req.getContractId());
        }
        if ("已作废".equals(c.getStatus())) {
            throw new BizException(400, "合同已作废,不允许建采购单: " + c.getNo());
        }
        PurchaseIn dup = purchaseInMapper.selectOne(new LambdaQueryWrapper<PurchaseIn>()
                .eq(PurchaseIn::getNo, req.getNo().trim()));
        if (dup != null) {
            throw new BizException(400, "采购单号已存在: " + req.getNo());
        }
        // 序列号预校验(与已有设备/本单内不重复)
        List<String> serials = new ArrayList<>();
        for (PurchaseOrderRequest.Item item : req.getItems()) {
            String sn = item.getSerialNo().trim();
            if (serials.contains(sn)) {
                throw new BizException(400, "本单序列号重复: " + sn);
            }
            serials.add(sn);
            Asset exist = assetMapper.selectOne(new LambdaQueryWrapper<Asset>().eq(Asset::getSerialNo, sn));
            if (exist != null) {
                throw new BizException(400, "序列号已被设备占用: " + sn);
            }
        }

        LocalDate orderDate = req.getOrderDate() != null ? req.getOrderDate() : LocalDate.now();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseOrderRequest.Item item : req.getItems()) {
            if (item.getPurchasePrice() != null) {
                total = total.add(item.getPurchasePrice());
            }
        }
        total = total.setScale(2, RoundingMode.HALF_UP);

        PurchaseIn p = new PurchaseIn();
        p.setNo(req.getNo().trim());
        p.setContractId(req.getContractId());
        p.setSupplierId(req.getSupplierId());
        p.setStatus("已下单");
        p.setTotalAmount(total);
        p.setFirstPayRatio(req.getFirstPayRatio());
        p.setAccountDays(req.getAccountDays());
        p.setOrderDate(orderDate);
        p.setRemark(req.getRemark());
        purchaseInMapper.insert(p);

        for (PurchaseOrderRequest.Item item : req.getItems()) {
            PurchaseItem pi = new PurchaseItem();
            pi.setPurchaseInId(p.getId());
            pi.setSerialNo(item.getSerialNo().trim());
            pi.setCategory(item.getCategory().trim());
            pi.setModel(item.getModel());
            pi.setMarketPrice(item.getMarketPrice());
            pi.setPurchasePrice(item.getPurchasePrice());
            pi.setSupplierId(item.getSupplierId() != null ? item.getSupplierId() : req.getSupplierId());
            pi.setMonthlyLaborValue(item.getMonthlyLaborValue());
            pi.setReplaceHeadcount(item.getReplaceHeadcount());
            pi.setRemark(item.getRemark());
            purchaseItemMapper.insert(pi);
        }

        // 应付计划:首付(下单付·到期=下单日)
        BigDecimal firstRatio = p.getFirstPayRatio() != null ? p.getFirstPayRatio() : stageRatio("首付");
        BigDecimal firstAmt = total.multiply(firstRatio).setScale(2, RoundingMode.HALF_UP);
        insertPayable(p.getId(), "首付", orderDate, firstAmt, "下单首付 " + pct(firstRatio));

        log.info("采购下单: no={}, id={}, contract={}, items={}, total={}, 首付={}",
                p.getNo(), p.getId(), c.getNo(), req.getItems().size(), total, firstAmt);
        return p.getId();
    }

    // ============ 入库(逐件生成 asset + 验收/尾款应付) ============

    @Transactional
    public void receive(Long id) {
        PurchaseIn p = load(id);
        if (!"已下单".equals(p.getStatus())) {
            throw new BizException(400, "采购单状态为" + p.getStatus() + ",仅已下单可入库");
        }
        LocalDate receiveDate = LocalDate.now();
        List<PurchaseItem> items = itemsOf(id);
        if (items.isEmpty()) {
            throw new BizException(400, "采购单无明细,不可入库");
        }
        // 逐件生成设备(状态机 owner=AssetService),回填 asset_id
        for (PurchaseItem pi : items) {
            Long assetId = assetService.createForPurchase(
                    pi.getSerialNo(), pi.getCategory(), pi.getModel(),
                    pi.getMarketPrice(), pi.getPurchasePrice(), pi.getSupplierId(),
                    pi.getMonthlyLaborValue(), pi.getReplaceHeadcount(), id, "采购入库");
            pi.setAssetId(assetId);
            purchaseItemMapper.updateById(pi);
        }
        // 验收(入库付·到期=入库日)+ 尾款(账期后·到期=入库日+账期天数);Σ 与首付合计=采购总额
        BigDecimal total = p.getTotalAmount() != null ? p.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal firstRatio = p.getFirstPayRatio() != null ? p.getFirstPayRatio() : stageRatio("首付");
        BigDecimal firstAmt = total.multiply(firstRatio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal accAmt = total.multiply(stageRatio("验收")).setScale(2, RoundingMode.HALF_UP);
        // 尾款=余额(补差,保证三段合计精确=总额)
        BigDecimal tailAmt = total.subtract(firstAmt).subtract(accAmt).setScale(2, RoundingMode.HALF_UP);
        int accountDays = p.getAccountDays() != null ? p.getAccountDays() : tailDays();
        insertPayable(id, "验收", receiveDate, accAmt, "到货验收付 " + pct(stageRatio("验收")));
        insertPayable(id, "尾款", receiveDate.plusDays(accountDays), tailAmt,
                "质保尾款(账期 " + accountDays + " 天)");

        p.setStatus("已入库");
        p.setReceiveDate(receiveDate);
        purchaseInMapper.updateById(p);

        // M3-01 钩子:采购入库 → 应付凭证(税务账·dr 固定资产 / cr 应付账款·借贷平衡·幂等)
        voucherService.postPayable(id, total, receiveDate,
                "采购入库应付 " + p.getNo() + " " + total + "元");
        log.info("采购入库: id={}, 生成设备{}件, 验收={}, 尾款={}", id, items.size(), accAmt, tailAmt);
    }

    // ============ 退货红冲(整单红冲·设备报废释放·应付红字) ============

    @Transactional
    public void returnOrder(Long id, ReturnRequest req) {
        PurchaseIn p = load(id);
        if ("已红冲".equals(p.getStatus())) {
            throw new BizException(400, "采购单已红冲");
        }
        String reason = req != null && req.getReason() != null ? req.getReason() : "采购退货红冲";

        // 对应设备报废释放(在租设备由 AssetService 拒绝)
        int scrapped = 0;
        for (PurchaseItem pi : itemsOf(id)) {
            if (pi.getAssetId() != null) {
                assetService.scrapOnPurchaseReturn(pi.getAssetId(), id);
                scrapped++;
            }
        }
        // 应付红字:未付置红冲;已生成的应付整体红字冲销(插等额负数行),原行标红冲
        BigDecimal redSum = BigDecimal.ZERO;
        List<Payable> payables = payablesOf(id);
        for (Payable pay : payables) {
            if ("红冲".equals(pay.getStatus())) {
                continue;
            }
            redSum = redSum.add(pay.getAmount());
            pay.setStatus("红冲");
            pay.setRemark((pay.getRemark() == null ? "" : pay.getRemark() + " | ") + "退货红冲");
            payableMapper.updateById(pay);
        }
        if (redSum.signum() != 0) {
            insertPayable(id, "退款红字", LocalDate.now(), redSum.negate().setScale(2, RoundingMode.HALF_UP),
                    "退货整单红字冲销: " + reason);
        }

        p.setStatus("已红冲");
        p.setRemark((p.getRemark() == null ? "" : p.getRemark() + " | ") + "退货红冲: " + reason);
        purchaseInMapper.updateById(p);

        auditLogService.record("采购退货", "purchase_in", id, AuditLogService.EXECUTED,
                "整单红冲 · 报废设备" + scrapped + "件 · 应付红字" + redSum.negate() + " · " + reason);
        log.info("采购退货红冲: id={}, 报废{}件, 红字={}, by={}", id, scrapped, redSum.negate(), UserContext.getUserId());
    }

    // ============ 详情 ============

    public PurchaseDetailResponse detail(Long id) {
        PurchaseIn p = load(id);
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());
        PurchaseDetailResponse r = new PurchaseDetailResponse();
        r.setId(p.getId());
        r.setNo(p.getNo());
        r.setStatus(p.getStatus());
        r.setContractId(p.getContractId());
        r.setContractNo(contractNo(p.getContractId()));
        r.setCustomerName(customerNameOfContract(p.getContractId()));
        r.setSupplierId(p.getSupplierId());
        r.setSupplierName(supplierName(p.getSupplierId()));
        r.setTotalAmount(seeCost ? p.getTotalAmount() : null);
        r.setOrderDate(p.getOrderDate());
        r.setReceiveDate(p.getReceiveDate());
        r.setRemark(p.getRemark());
        r.setSensitiveMasked(!seeCost);
        r.setPayableOutstanding(seeCost ? outstanding(id) : null);

        List<PurchaseDetailResponse.ItemLine> itemLines = new ArrayList<>();
        for (PurchaseItem pi : itemsOf(id)) {
            PurchaseDetailResponse.ItemLine il = new PurchaseDetailResponse.ItemLine();
            il.setId(pi.getId());
            il.setSerialNo(pi.getSerialNo());
            il.setCategory(pi.getCategory());
            il.setModel(pi.getModel());
            il.setMarketPrice(pi.getMarketPrice());
            il.setPurchasePrice(seeCost ? pi.getPurchasePrice() : null);
            il.setSupplierName(supplierName(pi.getSupplierId()));
            il.setAssetId(pi.getAssetId());
            if (pi.getAssetId() != null) {
                Asset a = assetMapper.selectById(pi.getAssetId());
                il.setAssetStatus(a != null ? a.getStatus() : null);
            }
            itemLines.add(il);
        }
        r.setItems(itemLines);

        List<PurchaseDetailResponse.PayableLine> payLines = new ArrayList<>();
        for (Payable pay : payablesOf(id)) {
            PurchaseDetailResponse.PayableLine pl = new PurchaseDetailResponse.PayableLine();
            pl.setId(pay.getId());
            pl.setStage(pay.getStage());
            pl.setDueDate(pay.getDueDate());
            pl.setAmount(seeCost ? pay.getAmount() : null);
            pl.setStatus(pay.getStatus());
            pl.setPaidDate(pay.getPaidDate());
            pl.setRemark(pay.getRemark());
            payLines.add(pl);
        }
        r.setPayables(payLines);
        return r;
    }

    // ============ 工具 ============

    private void insertPayable(Long purchaseInId, String stage, LocalDate due, BigDecimal amount, String remark) {
        Payable pay = new Payable();
        pay.setPurchaseInId(purchaseInId);
        pay.setStage(stage);
        pay.setDueDate(due);
        pay.setAmount(amount);
        pay.setStatus("待付");
        pay.setRemark(remark);
        payableMapper.insert(pay);
    }

    /** 待付应付合计(负债口径:排除红冲)。 */
    private BigDecimal outstanding(Long purchaseInId) {
        return payablesOf(purchaseInId).stream()
                .filter(p -> "待付".equals(p.getStatus()))
                .map(Payable::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private List<PurchaseItem> itemsOf(Long purchaseInId) {
        return purchaseItemMapper.selectList(new LambdaQueryWrapper<PurchaseItem>()
                .eq(PurchaseItem::getPurchaseInId, purchaseInId).orderByAsc(PurchaseItem::getId));
    }

    private List<Payable> payablesOf(Long purchaseInId) {
        return payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getPurchaseInId, purchaseInId).orderByAsc(Payable::getId));
    }

    private PurchaseIn load(Long id) {
        PurchaseIn p = purchaseInMapper.selectById(id);
        if (p == null || Integer.valueOf(1).equals(p.getIsDeleted())) {
            throw new BizException(404, "采购单不存在: id=" + id);
        }
        return p;
    }

    private BigDecimal stageRatio(String stage) {
        BigDecimal v = safeValue("payable_stage_ratio", stage);
        return v != null ? v : BigDecimal.ZERO;
    }

    private int tailDays() {
        BigDecimal v = safeValue("payable_tail_days", "");
        return v != null && v.intValue() > 0 ? v.intValue() : 90;
    }

    private String pct(BigDecimal ratio) {
        if (ratio == null) {
            return "";
        }
        return ratio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private BigDecimal safeValue(String ruleKey, String scopeKey) {
        try {
            return rules.getValue(ruleKey, scopeKey, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }

    private String contractNo(Long contractId) {
        if (contractId == null) {
            return null;
        }
        Contract c = contractMapper.selectById(contractId);
        return c != null ? c.getNo() : ("合同#" + contractId);
    }

    private String customerNameOfContract(Long contractId) {
        if (contractId == null) {
            return null;
        }
        Contract c = contractMapper.selectById(contractId);
        if (c == null) {
            return null;
        }
        Customer cust = customerMapper.selectById(c.getCustomerId());
        return cust != null ? cust.getName() : ("客户#" + c.getCustomerId());
    }

    private String supplierName(Long supplierId) {
        if (supplierId == null) {
            return null;
        }
        Supplier s = supplierMapper.selectById(supplierId);
        return s != null ? s.getName() : ("供应商#" + supplierId);
    }
}
