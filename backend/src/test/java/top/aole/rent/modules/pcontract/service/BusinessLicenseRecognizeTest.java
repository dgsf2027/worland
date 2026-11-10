package top.aole.rent.modules.pcontract.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import top.aole.rent.common.exception.BizException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BusinessLicenseRecognizeTest {
    static class Stub extends DocRecognizeService {
        Integer budget;
        String prompt;
        int imageCount;
        @Override
        protected String callVision(String model, String prompt, List<String> images, Integer budget) {
            this.budget = budget;
            this.prompt = prompt;
            this.imageCount = images.size();
            return "{\"companyName\":\"示例仓储有限公司\",\"legalPerson\":\"张三\",\"registeredCapitalWan\":100,"
                    + "\"establishedDate\":\"2024年8月20日\",\"address\":\"示例地址一号\",\"companyProfile\":null}";
        }
    }
    private Stub configured() {
        Stub service = new Stub();
        ReflectionTestUtils.setField(service, "baseUrl", "https://example.invalid");
        ReflectionTestUtils.setField(service, "apiKey", "test-only");
        ReflectionTestUtils.setField(service, "model", "test-model");
        return service;
    }
    @Test
    void 只复核一张图并限制文本输出预算() {
        Stub service = configured();
        MockMultipartFile image = new MockMultipartFile("files", "fields.jpg", "image/jpeg", new byte[]{1});
        String text = String.join("", Collections.nCopies(7000, "甲"));
        Map<String, Object> result = service.recognize(DocRecognizeService.KIND_LICENSE, Collections.singletonList(image), text);
        assertEquals(Integer.valueOf(1024), service.budget);
        assertEquals(1, service.imageCount);
        assertTrue(service.prompt.length() < 6500);
        assertEquals("100", result.get("registeredCapitalWan"));
        assertEquals("2024-08-20", result.get("establishedDate"));
        assertNull(result.get("companyProfile"));
        assertThrows(BizException.class, () -> service.recognize(DocRecognizeService.KIND_LICENSE, Arrays.asList(image, image), null));
    }
    @Test
    void 无效金额日期及超长字段不填入() {
        Map<String, Object> result = DocRecognizeService.parse(DocRecognizeService.KIND_LICENSE,
                "{\"registeredCapitalWan\":-1,\"establishedDate\":\"2024-02-30\",\"legalPerson\":\""
                        + String.join("", Collections.nCopies(70, "甲")) + "\"}");
        assertNull(result.get("registeredCapitalWan"));
        assertNull(result.get("establishedDate"));
        assertNull(result.get("legalPerson"));
    }
}
