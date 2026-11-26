package top.aole.rent.modules.automation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.automation.domain.NotifyChannel;
import top.aole.rent.modules.automation.mapper.NotifyChannelMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一外推消息引擎（V132）：零额外依赖，原生兼容 企业微信 / 钉钉 / 飞书 / 通用 Webhook。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookNotifyService {

    private final NotifyChannelMapper channelMapper;
    private final ObjectMapper objectMapper;

    /**
     * 按目标角色广播消息给所有已启用的匹配通道（通道 target_role = 目标角色 或 '全员'）。
     * 严禁向 LP 通道发送成本类消息。
     */
    public int pushToRole(String targetRole, String title, String markdownContent) {
        List<NotifyChannel> channels = channelMapper.selectList(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getIsDeleted, 0)
                .eq(NotifyChannel::getEnabled, 1));
        int ok = 0;
        for (NotifyChannel ch : channels) {
            if ("LP".equalsIgnoreCase(ch.getTargetRole())) {
                continue;
            }
            boolean match = "全员".equals(targetRole)
                    || "全员".equals(ch.getTargetRole())
                    || (targetRole != null && targetRole.equals(ch.getTargetRole()));
            if (!match) {
                continue;
            }
            if (sendSingle(ch, title, markdownContent) == null) {
                ok++;
            }
        }
        return ok;
    }

    /** 向单个通道发送消息；成功返回 null，失败返回错误信息。 */
    public String sendSingle(NotifyChannel ch, String title, String markdownContent) {
        if (ch == null || ch.getWebhookUrl() == null || ch.getWebhookUrl().trim().isEmpty()) {
            return "Webhook 地址为空";
        }
        String err = null;
        try {
            String targetUrl = buildSignedUrl(ch);
            String payload = buildPayload(ch, title, markdownContent);
            postJson(targetUrl, payload);
            ch.setLastSentAt(LocalDateTime.now());
            ch.setLastError(null);
            channelMapper.updateById(ch);
        } catch (Exception e) {
            err = e.getMessage();
            log.warn("[外推通道] 发送失败 channel={} type={} err={}", ch.getName(), ch.getChannelType(), err);
            ch.setLastError(err != null && err.length() > 480 ? err.substring(0, 480) : err);
            channelMapper.updateById(ch);
        }
        return err;
    }

    /** 构造各平台标准 JSON 载荷（企微/钉钉/飞书/通用）。 */
    public String buildPayload(NotifyChannel ch, String title, String markdownContent) throws Exception {
        String type = ch.getChannelType() == null ? "generic" : ch.getChannelType().toLowerCase();
        Map<String, Object> root = new LinkedHashMap<>();
        if ("wecom".equals(type)) {
            root.put("msgtype", "markdown");
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("content", "**" + title + "**\n" + markdownContent);
            root.put("markdown", md);
        } else if ("dingtalk".equals(type)) {
            root.put("msgtype", "markdown");
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("title", title);
            md.put("text", "### " + title + "\n" + markdownContent);
            root.put("markdown", md);
        } else if ("feishu".equals(type)) {
            if (ch.getSecret() != null && !ch.getSecret().trim().isEmpty()) {
                long ts = System.currentTimeMillis() / 1000L;
                root.put("timestamp", String.valueOf(ts));
                root.put("sign", feishuSign(ch.getSecret().trim(), ts));
            }
            root.put("msg_type", "text");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("text", "【" + title + "】\n" + markdownContent.replace("**", ""));
            root.put("content", content);
        } else {
            root.put("title", title);
            root.put("content", markdownContent);
            root.put("targetRole", ch.getTargetRole());
            root.put("sentAt", LocalDateTime.now().toString());
        }
        return objectMapper.writeValueAsString(root);
    }

    private String buildSignedUrl(NotifyChannel ch) throws Exception {
        String url = ch.getWebhookUrl().trim();
        if ("dingtalk".equalsIgnoreCase(ch.getChannelType())
                && ch.getSecret() != null && !ch.getSecret().trim().isEmpty()) {
            long ts = System.currentTimeMillis();
            String stringToSign = ts + "\n" + ch.getSecret().trim();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ch.getSecret().trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), "UTF-8");
            String sep = url.contains("?") ? "&" : "?";
            return url + sep + "timestamp=" + ts + "&sign=" + sign;
        }
        return url;
    }

    private String feishuSign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }

    protected void postJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = is == null ? "" : readStream(is);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + body);
        }
    }

    private static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = is.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        return out.toString("UTF-8");
    }
}
