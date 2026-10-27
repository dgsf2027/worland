package top.aole.rent.modules.pcontract.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import top.aole.rent.common.exception.BizException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 图片识别:未配置/非图片的拒绝;模型回复(含 markdown 包裹、千分位、百分号、中文日期)的解析与归一化。虚构数据。 */
class DocRecognizeServiceTest {

    private static final byte[] IMG = {1, 2, 3};

    private DocRecognizeService configured(String reply) {
        DocRecognizeService s = new DocRecognizeService() {
            @Override
            protected String callVision(String useModel, String prompt, List<String> dataUrls) {
                return reply;
            }
        };
        ReflectionTestUtils.setField(s, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(s, "apiKey", "k");
        ReflectionTestUtils.setField(s, "model", "m");
        return s;
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("files", "a.png", "image/png", IMG);
    }

    @Test
    void 未配置识别服务_明确报错() {
        DocRecognizeService s = new DocRecognizeService();
        BizException e = assertThrows(BizException.class,
                () -> s.recognize("invoice", Collections.singletonList(png())));
        assertTrue(e.getMessage().contains("未配置"));
    }

    @Test
    void 配置状态_未配置为false_配齐为true() {
        assertTrue(!new DocRecognizeService().configured());
        assertTrue(configured("{}").configured());
        // 只配了 base-url 与 key、没有任何模型名 → 仍视为未配置
        DocRecognizeService half = new DocRecognizeService();
        ReflectionTestUtils.setField(half, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(half, "apiKey", "k");
        assertTrue(!half.configured());
        // 单独配了看图模型也算可用
        ReflectionTestUtils.setField(half, "visionModel", "vision-m");
        assertTrue(half.configured());
    }

    @Test
    void 非图片被拒() {
        DocRecognizeService s = configured("{}");
        MockMultipartFile pdf = new MockMultipartFile("files", "a.pdf", "application/pdf", IMG);
        assertThrows(BizException.class, () -> s.recognize("invoice", Collections.singletonList(pdf)));
    }

    @Test
    void 未知类型被拒() {
        DocRecognizeService s = configured("{}");
        assertThrows(BizException.class, () -> s.recognize("other", Collections.singletonList(png())));
    }

    @Test
    void 发票_解析并归一化() {
        String reply = "```json\n{\"invoiceNo\":\"12345678901234567890\",\"invoiceType\":\"电子发票(增值税专用发票)\","
                + "\"issueDate\":\"2026年10月14日\",\"sellerName\":\"虚构销方\",\"buyerName\":\"虚构购方\","
                + "\"amountExTax\":\"10,000.00\",\"taxAmount\":\"1,300.00\",\"totalAmount\":\"¥11,300.00\",\"taxRate\":\"13%\"}\n```";
        Map<String, Object> r = configured(reply).recognize("invoice", Collections.singletonList(png()));
        assertEquals("12345678901234567890", r.get("invoiceNo"));
        assertEquals("专票", r.get("invoiceType"));
        assertEquals("2026-10-14", r.get("issueDate"));
        assertEquals(0, new BigDecimal("10000.00").compareTo((BigDecimal) r.get("amountExTax")));
        assertEquals(0, new BigDecimal("11300").compareTo((BigDecimal) r.get("totalAmount")));
        assertEquals(0, new BigDecimal("0.13").compareTo((BigDecimal) r.get("taxRate")));
    }

    @Test
    void 回单_缺失字段为空不编造() {
        String reply = "{\"amount\":55000,\"paidDate\":\"2026/1/5\",\"bankSerial\":\"S123\",\"payeeName\":null}";
        Map<String, Object> r = configured(reply).recognize("receipt", Collections.singletonList(png()));
        assertEquals(0, new BigDecimal("55000").compareTo((BigDecimal) r.get("amount")));
        assertEquals("2026-01-05", r.get("paidDate"));
        assertEquals("S123", r.get("bankSerial"));
        assertNull(r.get("payeeName"));
        assertNull(r.get("payerName"));
    }

    @Test
    void 合同_付款节点比例_支持百分数与小数() {
        String reply = "{\"contractNo\":\"T-1\",\"supplierName\":\"虚构供应商\",\"totalAmount\":100000,\"taxRate\":0.13,"
                + "\"terms\":[{\"stageName\":\"首付\",\"ratio\":\"40%\",\"amount\":40000},"
                + "{\"stageName\":\"尾款\",\"ratio\":0.6,\"amount\":null}]}";
        Map<String, Object> r = configured(reply).recognize("contract", Collections.singletonList(png()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> terms = (List<Map<String, Object>>) r.get("terms");
        assertEquals(2, terms.size());
        assertEquals(0, new BigDecimal("0.4").compareTo((BigDecimal) terms.get(0).get("ratio")));
        assertEquals(0, new BigDecimal("0.6").compareTo((BigDecimal) terms.get(1).get("ratio")));
        assertNull(terms.get(1).get("amount"));
    }

    @Test
    void 只有文字没有图片_也能识别() {
        String reply = "{\"invoiceNo\":\"12345678901234567890\",\"totalAmount\":113}";
        Map<String, Object> r = configured(reply).recognize("invoice", null, "发票号码 12345678901234567890 价税合计 113.00");
        assertEquals("12345678901234567890", r.get("invoiceNo"));
        assertEquals(0, new BigDecimal("113").compareTo((BigDecimal) r.get("totalAmount")));
    }

    @Test
    void 文字被带进提示词() {
        final String[] seen = new String[1];
        DocRecognizeService s = new DocRecognizeService() {
            @Override
            protected String callVision(String useModel, String prompt, List<String> dataUrls) {
                seen[0] = prompt;
                return "{}";
            }
        };
        ReflectionTestUtils.setField(s, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(s, "apiKey", "k");
        ReflectionTestUtils.setField(s, "model", "m");
        s.recognize("receipt", Collections.emptyList(), "转账金额 100.00 流水号 ABC");
        assertTrue(seen[0].contains("流水号 ABC"));
    }

    @Test
    void 图片和文字都没有_报错() {
        DocRecognizeService s = configured("{}");
        assertThrows(BizException.class, () -> s.recognize("invoice", Collections.emptyList(), "  "));
    }

    @Test
    void 模型无有效JSON_报错而不是返回空数据() {
        DocRecognizeService s = configured("无法识别");
        assertThrows(BizException.class, () -> s.recognize("invoice", Collections.singletonList(png())));
    }
}
