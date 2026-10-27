package top.aole.rent.modules.pcontract.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.net.SSLUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.exception.BizException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合同 / 银行回单 / 进项发票 拍照识别:把图片交给多模态大模型(OpenAI 兼容网关),抽取关键字段。
 *
 * <p>识别结果只用于「预填」,不入库;保存时仍走各登记接口的全部校验。
 * 未配置 LLM(base-url/api-key/model)或模型调用失败时明确报错,由页面回退手工录入——不编造数据。
 * 图片原文不写日志。仅支持图片(jpg/png/webp);PDF 可作为原件存档,但不能识别。
 */
@Slf4j
@Service
public class DocRecognizeService {

    public static final String KIND_CONTRACT = "contract";
    public static final String KIND_RECEIPT = "receipt";
    public static final String KIND_INVOICE = "invoice";
    static final int MAX_FILES = 6;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int MAX_TEXT_CHARS = 30000;
    private static final List<String> IMAGE_TYPES = Arrays.asList("image/jpeg", "image/png", "image/webp");

    @Value("${llm.base-url:}")
    private String baseUrl;
    @Value("${llm.api-key:}")
    private String apiKey;
    @Value("${llm.model:}")
    private String model;
    /** 识别专用模型(须支持看图);为空则用 llm.model */
    @Value("${llm.vision-model:}")
    private String visionModel;
    @Value("${llm.timeout-ms:30000}")
    private int timeoutMs;
    @Value("${llm.trust-all-ssl:true}")
    private boolean trustAllSsl;

    public Map<String, Object> recognize(String kind, List<MultipartFile> files) {
        return recognize(kind, files, null);
    }

    /**
     * @param text 页面从 PDF(有文字层)/txt 里提取的文字,可空;与图片(拍照、扫描版 PDF 的页面图)至少给一项。
     *             文字内容直接交给模型抽取,比 OCR 更准,且纯文字不需要看图模型。
     */
    public Map<String, Object> recognize(String kind, List<MultipartFile> files, String text) {
        String prompt = promptOf(kind);
        if (StrUtil.hasBlank(baseUrl, apiKey) || StrUtil.isBlank(StrUtil.blankToDefault(visionModel, model))) {
            throw new BizException(400, "未配置图片识别服务(LLM_BASE_URL / LLM_API_KEY / LLM_VISION_MODEL),请先手工录入");
        }
        boolean hasText = StrUtil.isNotBlank(text);
        if ((files == null || files.isEmpty()) && !hasText) {
            throw new BizException(400, "请先拍照、选择图片，或选择带文字的 PDF/文本文件");
        }
        if (hasText) {
            String t = text.trim();
            if (t.length() > MAX_TEXT_CHARS) {
                t = t.substring(0, MAX_TEXT_CHARS);
            }
            prompt = prompt + "\n\n以下是从文件中提取的文字内容(可能带有版面错位，按语义理解)：\n" + t;
        }
        if (files == null) {
            files = new ArrayList<>();
        }
        if (files.size() > MAX_FILES) {
            throw new BizException(400, "一次最多识别 " + MAX_FILES + " 张图片");
        }
        List<String> dataUrls = new ArrayList<>();
        for (MultipartFile f : files) {
            String ct = f.getContentType() == null ? "" : f.getContentType().toLowerCase();
            if (!IMAGE_TYPES.contains(ct)) {
                throw new BizException(400, "只能识别图片(jpg/png/webp)：" + f.getOriginalFilename()
                        + "。PDF 请截图后识别，或手工录入");
            }
            if (f.getSize() > MAX_BYTES) {
                throw new BizException(400, "图片过大(>" + (MAX_BYTES >> 20) + "MB)：" + f.getOriginalFilename());
            }
            try {
                dataUrls.add("data:" + ct + ";base64," + Base64.encode(f.getBytes()));
            } catch (IOException e) {
                throw new BizException(400, "读取图片失败：" + f.getOriginalFilename());
            }
        }
        String reply;
        try {
            reply = callVision(StrUtil.blankToDefault(visionModel, model), prompt, dataUrls);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[DocRecognize] 识别调用失败 kind={}: {}", kind, e.getMessage());
            throw new BizException(502, "图片识别服务暂不可用，请稍后重试或手工录入");
        }
        return parse(kind, reply);
    }

    /** 真调用多模态接口;测试里覆盖。 */
    protected String callVision(String useModel, String prompt, List<String> dataUrls) {
        String url = StrUtil.removeSuffix(baseUrl.trim(), "/") + "/v1/chat/completions";
        JSONArray content = new JSONArray();
        content.add(new JSONObject().set("type", "text").set("text", prompt));
        for (String d : dataUrls) {
            content.add(new JSONObject().set("type", "image_url").set("image_url", new JSONObject().set("url", d)));
        }
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "user").set("content", content));
        String body = new JSONObject().set("model", useModel).set("messages", messages)
                .set("temperature", 0).set("stream", false).toString();
        HttpRequest req = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .timeout(Math.max(timeoutMs, 60000))
                .body(body);
        if (trustAllSsl) {
            req.setSSLSocketFactory(SSLUtil.createSSLContext(null).getSocketFactory());
            req.setHostnameVerifier((h, s) -> true);
        }
        try (HttpResponse resp = req.execute()) {
            if (resp.getStatus() != 200) {
                throw new IllegalStateException("识别模型返回 " + resp.getStatus());
            }
            String text = JSONUtil.parseObj(resp.body()).getByPath("choices.0.message.content", String.class);
            if (StrUtil.isBlank(text)) {
                throw new IllegalStateException("识别模型无内容");
            }
            return text;
        }
    }

    // ---------------- 解析与归一化 ----------------

    static Map<String, Object> parse(String kind, String reply) {
        JSONObject json = extractJson(reply);
        Map<String, Object> out = new LinkedHashMap<>();
        switch (kind) {
            case KIND_CONTRACT:
                out.put("contractNo", str(json, "contractNo"));
                out.put("supplierName", str(json, "supplierName"));
                out.put("mainContractNo", str(json, "mainContractNo"));
                out.put("signedDate", date(json, "signedDate"));
                out.put("totalAmount", num(json, "totalAmount"));
                out.put("taxRate", rate(json, "taxRate"));
                List<Map<String, Object>> terms = new ArrayList<>();
                JSONArray arr = json.getJSONArray("terms");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject t = arr.getJSONObject(i);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("stageName", str(t, "stageName"));
                        m.put("ratio", rate(t, "ratio"));
                        m.put("amount", num(t, "amount"));
                        m.put("conditionText", str(t, "conditionText"));
                        terms.add(m);
                    }
                }
                out.put("terms", terms);
                break;
            case KIND_RECEIPT:
                out.put("amount", num(json, "amount"));
                out.put("paidDate", date(json, "paidDate"));
                out.put("bankSerial", str(json, "bankSerial"));
                out.put("payerName", str(json, "payerName"));
                out.put("payeeName", str(json, "payeeName"));
                out.put("remark", str(json, "remark"));
                break;
            case KIND_INVOICE:
                out.put("invoiceNo", str(json, "invoiceNo"));
                String type = str(json, "invoiceType");
                out.put("invoiceType", type == null ? null : (type.contains("专") ? "专票" : "普通"));
                out.put("issueDate", date(json, "issueDate"));
                out.put("sellerName", str(json, "sellerName"));
                out.put("buyerName", str(json, "buyerName"));
                out.put("amountExTax", num(json, "amountExTax"));
                out.put("taxAmount", num(json, "taxAmount"));
                out.put("totalAmount", num(json, "totalAmount"));
                out.put("taxRate", rate(json, "taxRate"));
                break;
            default:
                throw new BizException(400, "不支持的识别类型: " + kind);
        }
        return out;
    }

    static JSONObject extractJson(String reply) {
        if (reply == null) {
            throw new BizException(502, "识别结果为空，请手工录入");
        }
        int s = reply.indexOf('{');
        int e = reply.lastIndexOf('}');
        if (s < 0 || e <= s) {
            throw new BizException(502, "未能从图片中识别出内容，请换一张更清晰的照片或手工录入");
        }
        try {
            return JSONUtil.parseObj(reply.substring(s, e + 1));
        } catch (Exception ex) {
            throw new BizException(502, "识别结果无法解析，请重试或手工录入");
        }
    }

    private static String str(JSONObject j, String key) {
        Object v = j.get(key);
        if (v == null || JSONUtil.isNull(v)) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static BigDecimal num(JSONObject j, String key) {
        String s = str(j, key);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", "").replace("，", "").replace("¥", "").replace("￥", "")
                    .replace("元", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 税率/比例:支持 0.13、13、13% 三种写法,统一成 0~1 小数。 */
    private static BigDecimal rate(JSONObject j, String key) {
        String s = str(j, key);
        if (s == null) {
            return null;
        }
        boolean pct = s.contains("%");
        BigDecimal v = num(new JSONObject().set(key, s.replace("%", "")), key);
        if (v == null) {
            return null;
        }
        if (pct || v.compareTo(BigDecimal.ONE) > 0) {
            v = v.divide(new BigDecimal("100"));
        }
        return v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0 ? null : v.stripTrailingZeros();
    }

    private static String date(JSONObject j, String key) {
        String s = str(j, key);
        if (s == null) {
            return null;
        }
        String norm = s.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-").replace(".", "-").trim();
        String[] p = norm.split("-");
        if (p.length != 3) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim())).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String promptOf(String kind) {
        String rules = "只输出一个 JSON 对象，不要任何解释或 markdown。图中看不清或没有的字段填 null，绝不要猜测或编造。"
                + "金额为不含千分位逗号的数字(单位元)；日期用 yyyy-MM-dd；比例/税率用 0~1 小数(13% 写 0.13)。";
        if (KIND_CONTRACT.equals(kind)) {
            return "这是一份设备采购合同(可能是多页照片，按顺序)。抽取以下字段：" + rules
                    + "字段：contractNo(合同编号)、supplierName(卖方/供应商全称)、mainContractNo(备注里的主合同/客户合同号，没有填 null)、"
                    + "signedDate(签订日期)、totalAmount(含税总价)、taxRate(税率)、"
                    + "terms(付款条款数组，每项 {stageName 节点名如首付款/进度款/验收款/尾款, ratio 占合同总价比例, amount 该节点金额, conditionText 付款条件原文简述})。";
        }
        if (KIND_RECEIPT.equals(kind)) {
            return "这是一张银行转账电子回单。抽取以下字段：" + rules
                    + "字段：amount(转账金额)、paidDate(交易日期)、bankSerial(交易流水号/电子回单号/业务编号)、"
                    + "payerName(付款人户名)、payeeName(收款人户名)、remark(用途/附言)。";
        }
        if (KIND_INVOICE.equals(kind)) {
            return "这是一张增值税发票(专用或普通)。抽取以下字段：" + rules
                    + "字段：invoiceNo(发票号码，20位数字，不是发票代码)、invoiceType(专票或普通)、issueDate(开票日期)、"
                    + "sellerName(销售方名称)、buyerName(购买方名称)、amountExTax(合计金额，不含税)、"
                    + "taxAmount(合计税额)、totalAmount(价税合计小写金额)、taxRate(税率)。";
        }
        throw new BizException(400, "不支持的识别类型: " + kind);
    }
}
