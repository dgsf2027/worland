package top.aole.rent.modules.automation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.automation.domain.BankStatement;
import top.aole.rent.modules.automation.dto.AutomationDtos;
import top.aole.rent.modules.automation.mapper.BankStatementMapper;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.billing.service.RentBillService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 二期核心：银行流水智能自动匹配与一键批量核销服务（V132）。
 * <p>支持拖入网银导出的 CSV / Excel（自动识别交易日期、对方户名、金额、流水号、摘要）或手工批量粘贴，
 * 自动与待收/逾期租金单按「合同号+金额(99%) / 客户户名+金额(95%) / 客户部分收款(85%) / 金额唯一命中(75%)」匹配，
 * 确认后一键批量核销 → 自动生成收入凭证 + 启动 10 个工作日 3% 销项开票倒计时 + 触发满期 1 元自动留购检查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankReconcileService {

    private final BankStatementMapper statementMapper;
    private final RentBillMapper rentBillMapper;
    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final RentBillService rentBillService;
    private final AuditLogService auditLogService;

    public List<BankStatement> list(String matchStatus) {
        return statementMapper.selectList(new LambdaQueryWrapper<BankStatement>()
                .eq(BankStatement::getIsDeleted, 0)
                .eq(matchStatus != null && !matchStatus.isEmpty(), BankStatement::getMatchStatus, matchStatus)
                .orderByDesc(BankStatement::getTxDate)
                .orderByDesc(BankStatement::getId)
                .last("limit 300"));
    }

    /** 列出所有待核销的租金单选项，便于财务手工调整匹配目标。 */
    public List<AutomationDtos.UnpaidBillOption> listUnpaidBills() {
        List<RentBill> bills = loadCandidateBills();
        Map<Long, Contract> ctMap = loadContractMap();
        Map<Long, Customer> custMap = loadCustomerMap();
        List<AutomationDtos.UnpaidBillOption> out = new ArrayList<>();
        for (RentBill b : bills) {
            BigDecimal rem = remaining(b);
            if (rem.signum() <= 0) continue;
            Contract c = ctMap.get(b.getContractId());
            Customer cust = c != null ? custMap.get(c.getCustomerId()) : null;
            AutomationDtos.UnpaidBillOption o = new AutomationDtos.UnpaidBillOption();
            o.setBillId(b.getId());
            o.setBillNo(b.getBillNo());
            o.setContractNo(c != null ? c.getNo() : "");
            o.setCustomerName(cust != null ? cust.getName() : "");
            o.setPeriodNo(b.getPeriodNo());
            o.setDueDate(b.getDueDate());
            o.setRemainingAmount(rem);
            o.setStatus(b.getStatus());
            out.add(o);
        }
        return out;
    }

    /** 导入网银导出的 CSV 或 Excel 流水文件并自动执行智能匹配。 */
    @Transactional
    public AutomationDtos.ImportResult importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请上传银行流水文件（支持 .csv / .xls / .xlsx）");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        List<AutomationDtos.ManualStatementInput> rows;
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".csv") || name.endsWith(".txt")) {
                rows = parseCsv(bytes);
            } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                rows = parseExcel(bytes);
            } else {
                throw new BizException(400, "仅支持 .csv / .xls / .xlsx 格式的银行流水文件");
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(400, "流水文件解析失败：" + e.getMessage());
        }
        return ingestAndMatch(rows);
    }

    /** 手工批量录入/导入银行流水并自动匹配（支持前端快速试跑与接口直调）。 */
    @Transactional
    public AutomationDtos.ImportResult ingestAndMatch(List<AutomationDtos.ManualStatementInput> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BizException(400, "未解析到有效流水行");
        }
        String batchNo = "BS-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        List<RentBill> candidates = loadCandidateBills();
        Map<Long, Contract> ctMap = loadContractMap();
        Map<Long, Customer> custMap = loadCustomerMap();

        AutomationDtos.ImportResult res = new AutomationDtos.ImportResult();
        res.setBatchNo(batchNo);
        res.setTotalRows(rows.size());

        for (AutomationDtos.ManualStatementInput in : rows) {
            if (in.getAmount() == null || in.getAmount().signum() <= 0) {
                continue;
            }
            String serial = in.getBankSerial() == null ? null : in.getBankSerial().trim();
            if (serial != null && !serial.isEmpty()) {
                long dup = statementMapper.selectCount(new LambdaQueryWrapper<BankStatement>()
                        .eq(BankStatement::getBankSerial, serial)
                        .eq(BankStatement::getIsDeleted, 0));
                if (dup > 0) {
                    res.setDuplicateRows(res.getDuplicateRows() + 1);
                    continue;
                }
            }
            BankStatement st = new BankStatement();
            st.setBatchNo(batchNo);
            st.setTxDate(in.getTxDate() != null ? in.getTxDate() : LocalDate.now());
            st.setCounterpartyName(trim(in.getCounterpartyName()));
            st.setAmount(in.getAmount().setScale(2, RoundingMode.HALF_UP));
            st.setDirection("OUT".equalsIgnoreCase(in.getDirection()) ? "OUT" : "IN");
            st.setBankSerial(serial);
            st.setMemo(trim(in.getMemo()));
            st.setIsDeleted(0);

            if ("IN".equals(st.getDirection())) {
                applySmartMatch(st, candidates, ctMap, custMap);
            } else {
                st.setMatchStatus("已忽略");
                st.setMatchType("NONE");
                st.setConfidence(0);
                st.setMatchReason("支出流水暂不参与收租自动核销");
            }
            statementMapper.insert(st);
            res.setImportedRows(res.getImportedRows() + 1);
            if ("已匹配".equals(st.getMatchStatus())) res.setMatchedRows(res.getMatchedRows() + 1);
            else if ("待确认".equals(st.getMatchStatus())) res.setNeedConfirmRows(res.getNeedConfirmRows() + 1);
        }
        return res;
    }

    /** 手工调整某条银行流水对应的目标收租单。 */
    @Transactional
    public BankStatement reassign(Long statementId, Long billId) {
        BankStatement st = requireStatement(statementId);
        if ("已核销".equals(st.getMatchStatus())) {
            throw new BizException(400, "该流水已核销，不可改绑");
        }
        if (billId == null) {
            st.setMatchStatus("未匹配");
            st.setMatchType("NONE");
            st.setMatchedBizId(null);
            st.setMatchedBizNo(null);
            st.setMatchedCustomer(null);
            st.setConfidence(0);
            st.setMatchReason("手工解除匹配");
            statementMapper.updateById(st);
            return st;
        }
        RentBill b = rentBillMapper.selectById(billId);
        if (b == null || Integer.valueOf(1).equals(b.getIsDeleted())) {
            throw new BizException(404, "目标收租单不存在");
        }
        Contract c = b.getContractId() == null ? null : contractMapper.selectById(b.getContractId());
        Customer cust = c != null && c.getCustomerId() != null ? customerMapper.selectById(c.getCustomerId()) : null;
        st.setMatchStatus("已匹配");
        st.setMatchType("RENT_BILL");
        st.setMatchedBizId(b.getId());
        st.setMatchedBizNo(b.getBillNo());
        st.setMatchedCustomer(cust != null ? cust.getName() : null);
        st.setConfidence(100);
        st.setMatchReason("财务手工指定匹配至 " + b.getBillNo());
        statementMapper.updateById(st);
        return st;
    }

    /** 标记某条银行流水为忽略（如内部互转/非租金款）。 */
    @Transactional
    public void ignore(Long statementId) {
        BankStatement st = requireStatement(statementId);
        if ("已核销".equals(st.getMatchStatus())) {
            throw new BizException(400, "该流水已核销，不可忽略");
        }
        st.setMatchStatus("已忽略");
        statementMapper.updateById(st);
    }

    /** 一键批量确认核销银行流水：调用真实收租核销过账凭证 + 启动开票倒计时 + 更新流水状态。 */
    @Transactional
    public AutomationDtos.ReconcileResult confirmBatch(List<Long> statementIds) {
        if (statementIds == null || statementIds.isEmpty()) {
            throw new BizException(400, "请先勾选要核销的流水");
        }
        AutomationDtos.ReconcileResult res = new AutomationDtos.ReconcileResult();
        res.setRequested(statementIds.size());
        String operator = UserContext.get() != null ? UserContext.get().getUserName() : "系统";

        for (Long id : statementIds) {
            BankStatement st = statementMapper.selectById(id);
            if (st == null || Integer.valueOf(1).equals(st.getIsDeleted())) {
                res.setFailed(res.getFailed() + 1);
                res.getMessages().add("流水#" + id + " 不存在");
                continue;
            }
            if ("已核销".equals(st.getMatchStatus())) {
                res.setFailed(res.getFailed() + 1);
                res.getMessages().add("流水#" + id + " 已核销(跳过)");
                continue;
            }
            if (!"RENT_BILL".equals(st.getMatchType()) || st.getMatchedBizId() == null) {
                res.setFailed(res.getFailed() + 1);
                res.getMessages().add("流水#" + id + " 尚未匹配到收租单");
                continue;
            }
            try {
                rentBillService.matchFromBankStatement(st.getMatchedBizId(), st.getAmount(), st.getBankSerial(),
                        "银行流水自动核销(" + (st.getCounterpartyName() == null ? "" : st.getCounterpartyName()) + ")");
                st.setMatchStatus("已核销");
                st.setReconciledAt(LocalDateTime.now());
                st.setOperatorName(operator);
                statementMapper.updateById(st);
                res.setSucceeded(res.getSucceeded() + 1);
                res.getMessages().add("✓ " + st.getMatchedBizNo() + " 已自动核销 ¥" + st.getAmount());
            } catch (Exception e) {
                res.setFailed(res.getFailed() + 1);
                res.getMessages().add("✗ 流水#" + id + "（" + st.getMatchedBizNo() + "）：" + e.getMessage());
            }
        }
        auditLogService.record("银行流水批量核销", "bank_statement", 0L, AuditLogService.EXECUTED,
                "请求 " + res.getRequested() + " 笔，成功 " + res.getSucceeded() + " 笔，失败 " + res.getFailed() + " 笔");
        return res;
    }

    // ============ 智能匹配核心算法 ============

    void applySmartMatch(BankStatement st, List<RentBill> candidates,
                         Map<Long, Contract> ctMap, Map<Long, Customer> custMap) {
        String cp = norm(st.getCounterpartyName());
        String memo = norm(st.getMemo());
        BigDecimal amt = st.getAmount();

        RentBill bestBill = null;
        Contract bestCt = null;
        Customer bestCust = null;
        int bestScore = 0;
        String bestReason = "未找到匹配待收租金单";

        // 先统计金额精确命中的待收单数量（用于区分唯一命中 vs 多单同额）
        int exactAmtCount = 0;
        for (RentBill b : candidates) {
            if (remaining(b).compareTo(amt) == 0) exactAmtCount++;
        }

        for (RentBill b : candidates) {
            BigDecimal rem = remaining(b);
            if (rem.signum() <= 0 || amt.compareTo(rem) > 0) continue;
            Contract c = ctMap.get(b.getContractId());
            Customer cust = c != null ? custMap.get(c.getCustomerId()) : null;
            String ctNo = c != null ? norm(c.getNo()) : "";
            String custName = cust != null ? norm(cust.getName()) : "";

            boolean exactAmt = rem.compareTo(amt) == 0;
            boolean hitContract = !ctNo.isEmpty() && (!memo.isEmpty() && memo.contains(ctNo));
            boolean hitCustomer = !custName.isEmpty()
                    && ((!cp.isEmpty() && (cp.contains(custName) || custName.contains(cp)))
                    || (!memo.isEmpty() && memo.contains(custName)));

            int score = 0;
            String reason = null;
            if (hitContract && exactAmt) {
                score = 99;
                reason = "摘要命中合同号 " + c.getNo() + " + 金额精确一致";
            } else if (hitCustomer && exactAmt) {
                score = 95;
                reason = "对方户名命中客户「" + cust.getName() + "」+ 金额精确一致";
            } else if (hitContract) {
                score = 90;
                reason = "摘要命中合同号 " + c.getNo() + "（部分收款，剩余应收 ¥" + rem + "）";
            } else if (hitCustomer) {
                score = 85;
                reason = "对方户名命中客户「" + cust.getName() + "」（部分收款，剩余应收 ¥" + rem + "）";
            } else if (exactAmt && exactAmtCount == 1) {
                score = 75;
                reason = "金额唯一精确匹配（可能为代付/个人卡转账，请核对付款人）";
            }
            if (score > bestScore) {
                bestScore = score;
                bestBill = b;
                bestCt = c;
                bestCust = cust;
                bestReason = reason;
            }
        }

        if (bestBill != null && bestScore >= 75) {
            st.setMatchStatus(bestScore >= 90 ? "已匹配" : "待确认");
            st.setMatchType("RENT_BILL");
            st.setMatchedBizId(bestBill.getId());
            st.setMatchedBizNo(bestBill.getBillNo());
            st.setMatchedCustomer(bestCust != null ? bestCust.getName()
                    : (bestCt != null ? bestCt.getNo() : null));
            st.setConfidence(bestScore);
            st.setMatchReason(bestReason);
        } else {
            st.setMatchStatus("未匹配");
            st.setMatchType("NONE");
            st.setConfidence(0);
            st.setMatchReason(bestReason);
        }
    }

    // ============ CSV / Excel 解析 ============

    private List<AutomationDtos.ManualStatementInput> parseCsv(byte[] bytes) throws Exception {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.contains("\uFFFD")) {
            text = new String(bytes, "GBK");
        }
        List<String[]> table = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                table.add(splitCsvLine(line));
            }
        }
        return mapRows(table);
    }

    private List<AutomationDtos.ManualStatementInput> parseExcel(byte[] bytes) throws Exception {
        List<String[]> table = new ArrayList<>();
        DataFormatter df = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                int last = Math.max(0, row.getLastCellNum());
                String[] cells = new String[last];
                boolean any = false;
                for (int i = 0; i < last; i++) {
                    Cell c = row.getCell(i);
                    String v = c == null ? "" : df.formatCellValue(c).trim();
                    cells[i] = v;
                    if (!v.isEmpty()) any = true;
                }
                if (any) table.add(cells);
            }
        }
        return mapRows(table);
    }

    private List<AutomationDtos.ManualStatementInput> mapRows(List<String[]> table) {
        if (table.size() < 2) {
            throw new BizException(400, "流水表至少需要表头行和 1 条数据行");
        }
        String[] header = table.get(0);
        int dateIdx = findCol(header, "日期", "时间", "date");
        int cpIdx = findCol(header, "对方", "户名", "付款人", "客户", "counterparty", "name");
        int amtIdx = findCol(header, "贷方", "收入", "转入", "收款", "金额", "amount");
        int dirIdx = findCol(header, "方向", "收支", "借贷", "direction");
        int serialIdx = findCol(header, "流水", "凭证号", "交易号", "serial");
        int memoIdx = findCol(header, "摘要", "备注", "附言", "用途", "memo");
        if (amtIdx < 0) {
            throw new BizException(400, "未在表头找到「金额/收入/贷方」列，请检查模板");
        }

        List<AutomationDtos.ManualStatementInput> out = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            String[] r = table.get(i);
            BigDecimal amt = parseMoney(cell(r, amtIdx));
            if (amt == null || amt.signum() == 0) continue;
            AutomationDtos.ManualStatementInput in = new AutomationDtos.ManualStatementInput();
            in.setTxDate(parseDate(cell(r, dateIdx)));
            in.setCounterpartyName(cell(r, cpIdx));
            String dirVal = cell(r, dirIdx);
            if (amt.signum() < 0 || dirVal.contains("支") || dirVal.contains("借") || dirVal.contains("付") || "OUT".equalsIgnoreCase(dirVal)) {
                in.setDirection("OUT");
                in.setAmount(amt.abs());
            } else {
                in.setDirection("IN");
                in.setAmount(amt.abs());
            }
            in.setBankSerial(cell(r, serialIdx));
            in.setMemo(cell(r, memoIdx));
            out.add(in);
        }
        return out;
    }

    private static int findCol(String[] header, String... hints) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].toLowerCase(Locale.ROOT);
            for (String hint : hints) {
                if (h.contains(hint.toLowerCase(Locale.ROOT))) return i;
            }
        }
        return -1;
    }

    private static String cell(String[] r, int idx) {
        if (idx < 0 || idx >= r.length || r[idx] == null) return "";
        return r[idx].trim().replace("\uFEFF", "");
    }

    private static BigDecimal parseMoney(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String clean = s.replace(",", "").replace("¥", "").replace("￥", "").replace(" ", "").trim();
        try {
            return new BigDecimal(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return LocalDate.now();
        String clean = s.trim().split(" ")[0].replace("/", "-").replace(".", "-");
        try {
            return LocalDate.parse(clean);
        } catch (Exception e) {
            if (clean.length() == 8 && clean.matches("\\d{8}")) {
                return LocalDate.of(Integer.parseInt(clean.substring(0, 4)),
                        Integer.parseInt(clean.substring(4, 6)),
                        Integer.parseInt(clean.substring(6, 8)));
            }
            return LocalDate.now();
        }
    }

    private static String[] splitCsvLine(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuote = !inQuote;
            } else if (ch == ',' && !inQuote) {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString().trim());
        return parts.toArray(new String[0]);
    }

    private List<RentBill> loadCandidateBills() {
        List<RentBill> list = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .eq(RentBill::getBillKind, "正常")
                .in(RentBill::getStatus, Arrays.asList("逾期", "待收", "部分已收")));
        list.sort(Comparator.comparing(b -> b.getDueDate() == null ? LocalDate.MAX : b.getDueDate()));
        return list;
    }

    private Map<Long, Contract> loadContractMap() {
        Map<Long, Contract> m = new HashMap<>();
        for (Contract c : contractMapper.selectList(new LambdaQueryWrapper<Contract>().eq(Contract::getIsDeleted, 0))) {
            m.put(c.getId(), c);
        }
        return m;
    }

    private Map<Long, Customer> loadCustomerMap() {
        Map<Long, Customer> m = new HashMap<>();
        for (Customer c : customerMapper.selectList(new LambdaQueryWrapper<Customer>().eq(Customer::getIsDeleted, 0))) {
            m.put(c.getId(), c);
        }
        return m;
    }

    private BankStatement requireStatement(Long id) {
        BankStatement st = statementMapper.selectById(id);
        if (st == null || Integer.valueOf(1).equals(st.getIsDeleted())) {
            throw new BizException(404, "银行流水不存在: id=" + id);
        }
        return st;
    }

    private static BigDecimal remaining(RentBill b) {
        BigDecimal amt = b.getAmount() == null ? BigDecimal.ZERO : b.getAmount();
        BigDecimal rec = b.getReceivedAmount() == null ? BigDecimal.ZERO : b.getReceivedAmount();
        return amt.subtract(rec).setScale(2, RoundingMode.HALF_UP);
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
