package top.aole.rent.modules.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.billing.domain.AccountingPeriod;
import top.aole.rent.modules.billing.mapper.AccountingPeriodMapper;
import top.aole.rent.modules.finance.domain.LedgerBook;
import top.aole.rent.modules.finance.domain.Voucher;
import top.aole.rent.modules.finance.domain.VoucherLine;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.finance.mapper.LedgerBookMapper;
import top.aole.rent.modules.finance.mapper.VoucherLineMapper;
import top.aole.rent.modules.finance.mapper.VoucherMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 凭证双账服务(M3-01/11)。业务单据自动生成凭证 + 借贷平衡校验 + ledger_book 双账过账 + P0-F 红冲内核。
 *
 * <p><b>双账(§4.3/ADR-003)</b>:一业务事件按账套拆两张凭证 —— tax 税务(分期收款销售)/ops 经营(三层回报)。
 * 收租收入两账套都确认;折旧只落 ops(经营口径)→ 两账套天然分岔(ops≠tax),500万红线取 tax。
 * <p><b>借贷平衡</b>:每张凭证 Σ(dr.amount)=Σ(cr.amount),过账时服务端强校验,不平抛异常整体回滚。
 * <p><b>ledger_book 唯一写手</b>:仅本服务 post/reverse 写入(append-only,红冲写负额行,防 stale 漂移)。
 * <p><b>P0-F 红冲三保险</b>:①{@code @Transactional} 单事务原子 ②{@code reverses_id} 唯一约束幂等键
 * (重复红冲 DB DuplicateKey 被拒) ③锁账守卫(落 is_locked 期的写一律拒,只走上期调整)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final VoucherMapper voucherMapper;
    private final VoucherLineMapper voucherLineMapper;
    private final LedgerBookMapper ledgerBookMapper;
    private final AccountingPeriodMapper accountingPeriodMapper;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    public static final String BOOK_TAX = "tax";
    public static final String BOOK_OPS = "ops";
    public static final String DR = "dr";
    public static final String CR = "cr";

    // 会计科目(简化科目表)
    private static final String ACC_BANK = "1002";        private static final String ACC_BANK_N = "银行存款";
    private static final String ACC_AR = "1122";          private static final String ACC_AR_N = "应收账款";
    private static final String ACC_FIXED = "1601";       private static final String ACC_FIXED_N = "固定资产";
    private static final String ACC_ACC_DEPR = "1602";    private static final String ACC_ACC_DEPR_N = "累计折旧";
    private static final String ACC_AP = "2202";          private static final String ACC_AP_N = "应付账款";
    private static final String ACC_MAIN_REV = "6001";    private static final String ACC_MAIN_REV_N = "主营业务收入(分期收款销售)";
    private static final String ACC_LEASE_REV = "6051";   private static final String ACC_LEASE_REV_N = "租赁收入";
    private static final String ACC_DEPR_EXP = "6602";    private static final String ACC_DEPR_EXP_N = "折旧费用";
    private static final String ACC_DISPOSAL = "6301";    private static final String ACC_DISPOSAL_N = "资产处置损益";

    // ================= 内部过账原语(建头+行+平衡校验+ledger_book+锁账守卫) =================

    /** 一条分录行的内存表示(过账前不落库)。 */
    public static class Entry {
        final String accountCode, accountName, direction;
        final BigDecimal amount;
        Entry(String code, String name, String direction, BigDecimal amount) {
            this.accountCode = code; this.accountName = name; this.direction = direction; this.amount = amount;
        }
    }

    static Entry dr(String code, String name, BigDecimal amt) { return new Entry(code, name, DR, amt); }
    static Entry cr(String code, String name, BigDecimal amt) { return new Entry(code, name, CR, amt); }

    /**
     * 过账一张凭证:借贷平衡校验 → 锁账守卫 → 写头+行 → 写 ledger_book(entry_type≠other 时)。返回凭证 id。
     * 供 post* 各业务入口复用;调用方须在同一 {@code @Transactional} 内(与业务单据同生共死)。
     */
    Long post(String sourceDocType, Long sourceDocId, String book, LocalDate bizDate,
              String entryType, BigDecimal ledgerAmount, String summary, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new BizException(500, "凭证分录为空: " + sourceDocType + "#" + sourceDocId);
        }
        String period = bizDate.format(YM);
        assertPeriodOpen(period, book);

        // 借贷平衡校验
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (Entry e : entries) {
            if (e.amount == null || e.amount.signum() < 0) {
                throw new BizException(500, "分录金额须为非负(方向由 direction 表达): " + e.accountName);
            }
            if (DR.equals(e.direction)) dr = dr.add(e.amount);
            else if (CR.equals(e.direction)) cr = cr.add(e.amount);
            else throw new BizException(500, "非法借贷方向: " + e.direction);
        }
        if (dr.compareTo(cr) != 0) {
            throw new BizException(500, "凭证借贷不平衡: 借 " + dr + " ≠ 贷 " + cr + "(" + summary + ")");
        }

        Voucher v = new Voucher();
        v.setVoucherNo(genVoucherNo(book, sourceDocType, sourceDocId, period, false));
        v.setSourceDocType(sourceDocType);
        v.setSourceDocId(sourceDocId);
        v.setBook(book);
        v.setPeriod(period);
        v.setBizDate(bizDate);
        v.setTotalAmount(dr);
        v.setEntryType(entryType);
        v.setSummary(summary);
        v.setIsReversal(0);
        v.setLockedPeriod(0);
        v.setOperatorId(currentUserId());
        voucherMapper.insert(v);

        for (Entry e : entries) {
            VoucherLine line = new VoucherLine();
            line.setVoucherId(v.getId());
            line.setAccountCode(e.accountCode);
            line.setAccountName(e.accountName);
            line.setDirection(e.direction);
            line.setAmount(e.amount);
            voucherLineMapper.insert(line);
        }

        // ledger_book 过账(收入/成本/应付计入账套流水;other 不入)
        if (entryType != null && !"other".equals(entryType) && ledgerAmount != null && ledgerAmount.signum() != 0) {
            writeLedger(book, period, entryType, ledgerAmount, v.getId(), sourceDocType, sourceDocId, bizDate, summary);
        }
        return v.getId();
    }

    private void writeLedger(String book, String period, String entryType, BigDecimal amount, Long voucherId,
                             String sourceDocType, Long sourceDocId, LocalDate bizDate, String remark) {
        LedgerBook lb = new LedgerBook();
        lb.setBook(book);
        lb.setPeriod(period);
        lb.setEntryType(entryType);
        lb.setAmount(amount);
        lb.setVoucherId(voucherId);
        lb.setSourceDocType(sourceDocType);
        lb.setSourceDocId(sourceDocId);
        lb.setBizDate(bizDate);
        lb.setRemark(remark);
        ledgerBookMapper.insert(lb);
    }

    // ================= 收租核销 → 收入凭证(双账) =================

    /**
     * 收租核销 → 生成收入凭证(税务账 + 经营账各一)。返回税务账凭证 id(回填 rent_bill.voucher_id)。
     * 幂等:同 rent_bill 已有非红冲凭证则跳过(返回既有 tax 凭证 id)。amount 负数(退款)→ 收入冲减(方向反转)。
     */
    @Transactional
    public Long postRentIncome(Long rentBillId, BigDecimal amount, LocalDate bizDate, String contractNo, int periodNo) {
        Long exist = existingTaxVoucher("rent_bill", rentBillId);
        if (exist != null) {
            return exist;   // 幂等:已过账
        }
        BigDecimal a = amount.abs();
        boolean income = amount.signum() >= 0;
        String tag = income ? "收租确认收入" : "退款冲减收入";
        String sum = tag + " " + contractNo + " 期" + periodNo + " " + a + "元";

        List<Entry> taxLines = new ArrayList<>();
        List<Entry> opsLines = new ArrayList<>();
        if (income) {
            taxLines.add(dr(ACC_AR, ACC_AR_N, a));
            taxLines.add(cr(ACC_MAIN_REV, ACC_MAIN_REV_N, a));
            opsLines.add(dr(ACC_BANK, ACC_BANK_N, a));
            opsLines.add(cr(ACC_LEASE_REV, ACC_LEASE_REV_N, a));
        } else {
            taxLines.add(dr(ACC_MAIN_REV, ACC_MAIN_REV_N, a));
            taxLines.add(cr(ACC_AR, ACC_AR_N, a));
            opsLines.add(dr(ACC_LEASE_REV, ACC_LEASE_REV_N, a));
            opsLines.add(cr(ACC_BANK, ACC_BANK_N, a));
        }
        BigDecimal ledgerAmt = income ? a : a.negate();
        Long taxId = post("rent_bill", rentBillId, BOOK_TAX, bizDate, "revenue", ledgerAmt, "[税务]" + sum, taxLines);
        post("rent_bill", rentBillId, BOOK_OPS, bizDate, "revenue", ledgerAmt, "[经营]" + sum, opsLines);
        log.info("[凭证·收租收入] rent_bill#{} {} 双账各一 · tax凭证#{}", rentBillId, a, taxId);
        return taxId;
    }

    // ================= 折旧 → 折旧凭证(仅经营口径 ops) =================

    /** 折旧计提 → ops 折旧凭证(dr 折旧费用 / cr 累计折旧)。返回凭证 id。供 DepreciationService 调用。 */
    @Transactional
    public Long postDepreciation(Long assetId, String serialNo, int periodNo, BigDecimal deprAmount, LocalDate bizDate) {
        String sum = "计提折旧 " + serialNo + " 第" + periodNo + "期 " + deprAmount + "元(经营口径)";
        List<Entry> lines = new ArrayList<>();
        lines.add(dr(ACC_DEPR_EXP, ACC_DEPR_EXP_N, deprAmount));
        lines.add(cr(ACC_ACC_DEPR, ACC_ACC_DEPR_N, deprAmount));
        return post("depreciation", assetId, BOOK_OPS, bizDate, "cost", deprAmount, sum, lines);
    }

    // ================= 采购入库 → 应付凭证(税务账·钩子) =================

    /** 采购入库/验收 → 应付凭证(dr 固定资产 / cr 应付账款,tax 账套)。幂等:同 purchase_in 已过账则跳过。 */
    @Transactional
    public Long postPayable(Long purchaseInId, BigDecimal amount, LocalDate bizDate, String summary) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        Long exist = existingTaxVoucher("purchase_in", purchaseInId);
        if (exist != null) {
            return exist;
        }
        List<Entry> lines = new ArrayList<>();
        lines.add(dr(ACC_FIXED, ACC_FIXED_N, amount));
        lines.add(cr(ACC_AP, ACC_AP_N, amount));
        return post("purchase_in", purchaseInId, BOOK_TAX, bizDate, "payable", amount,
                summary == null ? "采购入库应付 " + amount + "元" : summary, lines);
    }

    // ================= 转让/处置 → 残值凭证(双账 · M4-01/02) =================

    /**
     * 转让/处置 → 残值结转凭证(双账·M4)。返回税务账凭证 id(报废无收款则返 ops 凭证 id),回填 transfer_order_line.voucher_id。
     *
     * <p><b>税务账(tax·并入分期收款销售计税)</b>:仅当 transferPrice&gt;0 —— dr 银行存款 / cr 主营业务收入,
     * ledger revenue += transferPrice(计税收入)。报废(transferPrice=0)不确认销售收入,跳过税务账。
     * <p><b>经营账(ops·三层回报之残值收益)</b>:dr 银行存款 transferPrice + 结转账面价 cr 固定资产 bookValue +
     * 处置损益 gain(gain&gt;0 → cr 资产处置损益;gain&lt;0/报废 → dr 资产处置损益)。借贷自平衡;
     * ledger revenue += gain(处置净损益计入经营口径回报,与折旧/收租同源汇总,不重复计银行流水)。
     * <p>幂等:同 transfer_line 已过账则返既有 tax(或 ops)凭证 id。
     */
    @Transactional
    public Long postResidual(Long transferLineId, BigDecimal transferPrice, BigDecimal bookValue, LocalDate bizDate) {
        BigDecimal price = transferPrice == null ? BigDecimal.ZERO : transferPrice;
        BigDecimal book = bookValue == null ? BigDecimal.ZERO : bookValue;
        if (price.signum() < 0) {
            throw new BizException(400, "处置价不可为负: " + price);
        }
        Long existTax = existingTaxVoucher("transfer_line", transferLineId);
        if (existTax != null) {
            return existTax;   // 幂等:已过账
        }
        // 幂等兜底:report ops 也查一遍(报废单只有 ops)
        Voucher existOps = voucherMapper.selectOne(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getSourceDocType, "transfer_line")
                .eq(Voucher::getSourceDocId, transferLineId)
                .eq(Voucher::getBook, BOOK_OPS)
                .eq(Voucher::getIsReversal, 0)
                .last("limit 1"));
        if (existOps != null) {
            return existOps.getId();
        }

        BigDecimal gain = price.subtract(book);
        String sum = "资产处置结转 line#" + transferLineId + " 处置价" + price + " 账面" + book + " 损益" + gain;

        // 经营账 ops:结转账面 + 处置损益
        List<Entry> opsLines = new ArrayList<>();
        if (price.signum() > 0) {
            opsLines.add(dr(ACC_BANK, ACC_BANK_N, price));
        }
        opsLines.add(cr(ACC_FIXED, ACC_FIXED_N, book));
        if (gain.signum() > 0) {
            opsLines.add(cr(ACC_DISPOSAL, ACC_DISPOSAL_N, gain));
        } else if (gain.signum() < 0) {
            opsLines.add(dr(ACC_DISPOSAL, ACC_DISPOSAL_N, gain.abs()));
        }
        Long opsId = post("transfer_line", transferLineId, BOOK_OPS, bizDate, "revenue", gain, "[经营]" + sum, opsLines);

        // 税务账 tax:并入分期收款销售(仅有收款)
        Long taxId = null;
        if (price.signum() > 0) {
            List<Entry> taxLines = new ArrayList<>();
            taxLines.add(dr(ACC_BANK, ACC_BANK_N, price));
            taxLines.add(cr(ACC_MAIN_REV, ACC_MAIN_REV_N, price));
            taxId = post("transfer_line", transferLineId, BOOK_TAX, bizDate, "revenue", price,
                    "[税务]" + sum + "(并入分期收款销售)", taxLines);
        }
        log.info("[凭证·残值处置] transfer_line#{} 处置价{} 账面{} 损益{} · tax#{} ops#{}",
                transferLineId, price, book, gain, taxId, opsId);
        return taxId != null ? taxId : opsId;
    }

    // ================= P0-F 通用凭证红冲 =================

    /**
     * 红冲一张凭证(P0-F 内核):
     * ① 单事务原子;② reverses_id 唯一约束幂等键(重复红冲 DuplicateKey → 回滚);
     * ③ 锁账守卫(原凭证记账期若 is_locked 则拒,只走上期调整);
     * ④ 生成方向反转的红冲凭证 + 写 ledger_book 负额行冲销。返回影响清单。
     */
    @Transactional
    public VoucherDtos.VoucherReverseImpact reverse(Long id, VoucherDtos.ReverseRequest req) {
        Voucher orig = load(id);
        if (Integer.valueOf(1).equals(orig.getIsReversal())) {
            throw new BizException(400, "红冲凭证本身不可再红冲: " + orig.getVoucherNo());
        }
        boolean existRev = !voucherMapper.selectList(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getReversesId, id)).isEmpty();
        if (existRev) {
            throw new BizException(400, "该凭证已被红冲(幂等拒),不可重复红冲: " + orig.getVoucherNo());
        }
        assertPeriodOpen(orig.getPeriod(), orig.getBook());   // 锁账守卫:原凭证期已锁则拒
        String reason = req != null && req.getReason() != null ? req.getReason() : "凭证红冲";

        List<VoucherLine> origLines = voucherLineMapper.selectList(new LambdaQueryWrapper<VoucherLine>()
                .eq(VoucherLine::getVoucherId, id).orderByAsc(VoucherLine::getId));
        if (origLines.isEmpty()) {
            throw new BizException(500, "原凭证无分录,无法红冲: " + orig.getVoucherNo());
        }

        List<String> impact = new ArrayList<>();
        Voucher rev = new Voucher();
        rev.setVoucherNo(genVoucherNo(orig.getBook(), orig.getSourceDocType(), orig.getSourceDocId(), orig.getPeriod(), true));
        rev.setSourceDocType(orig.getSourceDocType());
        rev.setSourceDocId(orig.getSourceDocId());
        rev.setBook(orig.getBook());
        rev.setPeriod(orig.getPeriod());
        rev.setBizDate(orig.getBizDate());
        rev.setTotalAmount(orig.getTotalAmount());
        rev.setEntryType(orig.getEntryType());
        rev.setSummary("红冲: " + orig.getVoucherNo() + " · " + reason);
        rev.setIsReversal(1);
        rev.setReversesId(id);   // 唯一约束在此兜底(并发重复红冲抛 DuplicateKey → 回滚)
        rev.setLockedPeriod(0);
        rev.setOperatorId(currentUserId());
        rev.setRemark(reason);
        voucherMapper.insert(rev);
        impact.add("生成红冲凭证 " + rev.getVoucherNo() + "(账套 " + orig.getBook() + ")");

        // 方向反转的镜像分录
        for (VoucherLine ol : origLines) {
            VoucherLine nl = new VoucherLine();
            nl.setVoucherId(rev.getId());
            nl.setAccountCode(ol.getAccountCode());
            nl.setAccountName(ol.getAccountName());
            nl.setDirection(DR.equals(ol.getDirection()) ? CR : DR);
            nl.setAmount(ol.getAmount());
            nl.setRemark("红冲镜像: " + ol.getAccountName());
            voucherLineMapper.insert(nl);
        }
        impact.add("镜像反转 " + origLines.size() + " 条分录(借贷对调,金额相等)");

        // ledger_book 冲销:对原凭证的 ledger 流水写等额负行
        List<LedgerBook> origLedger = ledgerBookMapper.selectList(new LambdaQueryWrapper<LedgerBook>()
                .eq(LedgerBook::getVoucherId, id));
        for (LedgerBook lb : origLedger) {
            writeLedger(lb.getBook(), lb.getPeriod(), lb.getEntryType(), lb.getAmount().negate(),
                    rev.getId(), lb.getSourceDocType(), lb.getSourceDocId(), lb.getBizDate(), "红冲冲销");
        }
        if (!origLedger.isEmpty()) {
            impact.add("账套流水冲销 " + origLedger.size() + " 行(ledger_book 写负额·营收红线同步回退)");
        }

        auditLogService.record("凭证红冲", "voucher", id, AuditLogService.EXECUTED,
                "红冲 " + orig.getVoucherNo() + " 期=" + orig.getPeriod() + " · " + reason);
        log.info("[凭证红冲] {} 期 {} by {} 影响 {} 项", orig.getVoucherNo(), orig.getPeriod(), currentUserId(), impact.size());

        VoucherDtos.VoucherReverseImpact ri = new VoucherDtos.VoucherReverseImpact();
        ri.setOriginalVoucherId(id);
        ri.setOriginalVoucherNo(orig.getVoucherNo());
        ri.setReversalVoucherId(rev.getId());
        ri.setReversalVoucherNo(rev.getVoucherNo());
        ri.setBook(orig.getBook());
        ri.setPeriod(orig.getPeriod());
        ri.setAmount(orig.getTotalAmount());
        ri.setItems(impact);
        return ri;
    }

    /** 按来源单据红冲其全部原始凭证(收租单红冲→冲销 tax+ops 两张)。返回红冲凭证数。 */
    @Transactional
    public int reverseBySource(String sourceDocType, Long sourceDocId, String reason) {
        List<Voucher> vs = voucherMapper.selectList(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getSourceDocType, sourceDocType)
                .eq(Voucher::getSourceDocId, sourceDocId)
                .eq(Voucher::getIsReversal, 0));
        int n = 0;
        for (Voucher v : vs) {
            boolean alreadyReversed = !voucherMapper.selectList(new LambdaQueryWrapper<Voucher>()
                    .eq(Voucher::getReversesId, v.getId())).isEmpty();
            if (alreadyReversed) {
                continue;
            }
            VoucherDtos.ReverseRequest req = new VoucherDtos.ReverseRequest();
            req.setReason(reason);
            reverse(v.getId(), req);
            n++;
        }
        return n;
    }

    // ================= 列表 / 详情 =================

    public PageResult<VoucherDtos.VoucherItem> list(String book, String sourceDocType, String period,
                                                    Boolean isReversal, int page, int size) {
        LambdaQueryWrapper<Voucher> qw = new LambdaQueryWrapper<Voucher>()
                .eq(book != null && !book.isEmpty(), Voucher::getBook, book)
                .eq(sourceDocType != null && !sourceDocType.isEmpty(), Voucher::getSourceDocType, sourceDocType)
                .eq(period != null && !period.isEmpty(), Voucher::getPeriod, period)
                .eq(isReversal != null, Voucher::getIsReversal, isReversal != null && isReversal ? 1 : 0)
                .orderByDesc(Voucher::getId);
        List<Voucher> all = voucherMapper.selectList(qw);
        List<VoucherDtos.VoucherItem> items = new ArrayList<>();
        for (Voucher v : all) {
            items.add(toItem(v));
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<VoucherDtos.VoucherItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    public VoucherDtos.VoucherDetail detail(Long id) {
        Voucher v = load(id);
        VoucherDtos.VoucherDetail d = new VoucherDtos.VoucherDetail();
        d.setVoucher(toItem(v));

        List<VoucherLine> lines = voucherLineMapper.selectList(new LambdaQueryWrapper<VoucherLine>()
                .eq(VoucherLine::getVoucherId, id).orderByAsc(VoucherLine::getId));
        List<VoucherDtos.LineItem> lineItems = new ArrayList<>();
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (VoucherLine l : lines) {
            VoucherDtos.LineItem li = new VoucherDtos.LineItem();
            li.setId(l.getId());
            li.setAccountCode(l.getAccountCode());
            li.setAccountName(l.getAccountName());
            li.setDirection(l.getDirection());
            li.setAmount(l.getAmount());
            li.setRemark(l.getRemark());
            lineItems.add(li);
            if (DR.equals(l.getDirection())) dr = dr.add(l.getAmount());
            else cr = cr.add(l.getAmount());
        }
        d.setLines(lineItems);
        d.setDebitTotal(dr);
        d.setCreditTotal(cr);
        d.setBalanced(dr.compareTo(cr) == 0);

        // 双账口径对照:同源单据的对家账套凭证(原始,非红冲)
        List<VoucherDtos.VoucherItem> siblings = new ArrayList<>();
        if (v.getSourceDocId() != null) {
            List<Voucher> sibs = voucherMapper.selectList(new LambdaQueryWrapper<Voucher>()
                    .eq(Voucher::getSourceDocType, v.getSourceDocType())
                    .eq(Voucher::getSourceDocId, v.getSourceDocId())
                    .eq(Voucher::getIsReversal, 0)
                    .ne(Voucher::getId, id));
            for (Voucher s : sibs) {
                siblings.add(toItem(s));
            }
        }
        d.setSiblingBooks(siblings);

        Voucher revBy = voucherMapper.selectOne(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getReversesId, id).last("limit 1"));
        d.setReversedByVoucherId(revBy != null ? revBy.getId() : null);
        return d;
    }

    // ================= 工具 =================

    private VoucherDtos.VoucherItem toItem(Voucher v) {
        VoucherDtos.VoucherItem it = new VoucherDtos.VoucherItem();
        it.setId(v.getId());
        it.setVoucherNo(v.getVoucherNo());
        it.setSourceDocType(v.getSourceDocType());
        it.setSourceDocId(v.getSourceDocId());
        it.setBook(v.getBook());
        it.setPeriod(v.getPeriod());
        it.setBizDate(v.getBizDate());
        it.setTotalAmount(v.getTotalAmount());
        it.setEntryType(v.getEntryType());
        it.setSummary(v.getSummary());
        it.setIsReversal(Integer.valueOf(1).equals(v.getIsReversal()));
        it.setReversesId(v.getReversesId());
        it.setCreateTime(v.getCreateTime());
        // 平衡校验(展示)
        List<VoucherLine> lines = voucherLineMapper.selectList(new LambdaQueryWrapper<VoucherLine>()
                .eq(VoucherLine::getVoucherId, v.getId()));
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (VoucherLine l : lines) {
            if (DR.equals(l.getDirection())) dr = dr.add(l.getAmount());
            else cr = cr.add(l.getAmount());
        }
        it.setBalanced(dr.compareTo(cr) == 0);
        return it;
    }

    /** 某来源单据已过账的税务账原始凭证 id(幂等判定)。 */
    private Long existingTaxVoucher(String sourceDocType, Long sourceDocId) {
        Voucher v = voucherMapper.selectOne(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getSourceDocType, sourceDocType)
                .eq(Voucher::getSourceDocId, sourceDocId)
                .eq(Voucher::getBook, BOOK_TAX)
                .eq(Voucher::getIsReversal, 0)
                .last("limit 1"));
        return v != null ? v.getId() : null;
    }

    /** 锁账守卫:period(YYYY-MM)在 book 账套 is_locked=1 → 拒写;无期间行=开放。 */
    private void assertPeriodOpen(String period, String book) {
        AccountingPeriod ap = accountingPeriodMapper.selectOne(new LambdaQueryWrapper<AccountingPeriod>()
                .eq(AccountingPeriod::getPeriod, period)
                .eq(AccountingPeriod::getBook, book)
                .last("limit 1"));
        if (ap != null && Integer.valueOf(1).equals(ap.getIsLocked())) {
            throw new BizException(400, "会计期 " + period + "(" + book + ")已锁账,禁止过账凭证;请走上期调整(P0-F 锁账守卫)");
        }
    }

    /** 凭证号:PZ-{book}-{sourceType}-{sourceId}-{period}[-R];撞车追加 -{n} 保唯一。 */
    private String genVoucherNo(String book, String sourceType, Long sourceId, String period, boolean reversal) {
        String base = "PZ-" + book + "-" + sourceType + "-" + (sourceId == null ? "X" : sourceId)
                + "-" + period.replace("-", "") + (reversal ? "-R" : "");
        String no = base;
        int n = 1;
        while (voucherMapper.selectCount(new LambdaQueryWrapper<Voucher>().eq(Voucher::getVoucherNo, no)) > 0) {
            no = base + "-" + (++n);
        }
        return no;
    }

    private Voucher load(Long id) {
        Voucher v = voucherMapper.selectById(id);
        if (v == null || Integer.valueOf(1).equals(v.getIsDeleted())) {
            throw new BizException(404, "凭证不存在: id=" + id);
        }
        return v;
    }

    private Long currentUserId() {
        return UserContext.get() != null ? UserContext.get().getUserId() : null;
    }
}
