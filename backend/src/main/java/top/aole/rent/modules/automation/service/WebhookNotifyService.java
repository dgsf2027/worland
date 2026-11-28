package top.aole.rent.modules.automation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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
            return "推送目标配置为空";
        }
        String err = null;
        try {
            if ("wecom_app".equalsIgnoreCase(ch.getChannelType())) {
                sendWecomAppMessage(ch, title, markdownContent);
            } else {
                String targetUrl = buildSignedUrl(ch);
                String payload = buildPayload(ch, title, markdownContent);
                String respBody = postJson(targetUrl, payload);
                validatePlatformResponse(ch.getChannelType(), respBody);
            }
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

    /**
     * 通过「企业微信自建应用 API」发送纯文本消息，可直接通过「微信插件」触达老板个人微信（无需安装企业微信 App）。
     * webhookUrl 约定格式：wecom-app://{corpId}/{agentId}/{toUser}，secret 字段存应用 Secret。
     */
    private void sendWecomAppMessage(NotifyChannel ch, String title, String markdownContent) throws Exception {
        String[] parts = parseWecomAppSpec(ch.getWebhookUrl());
        String corpId = parts[0];
        int agentId = Integer.parseInt(parts[1]);
        String toUser = parts[2];
        String appSecret = ch.getSecret() == null ? "" : ch.getSecret().trim();
        if (appSecret.isEmpty()) {
            throw new IllegalArgumentException("企微自建应用 Secret 不能为空");
        }
        String tokenUrl = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid="
                + URLEncoder.encode(corpId, "UTF-8")
                + "&corpsecret=" + URLEncoder.encode(appSecret, "UTF-8");
        String tokenResp = getJson(tokenUrl);
        JsonNode tokenNode = validatePlatformResponse("wecom_app", tokenResp);
        String accessToken = tokenNode != null && tokenNode.hasNonNull("access_token")
                ? tokenNode.get("access_token").asText() : "";
        if (accessToken.isEmpty()) {
            throw new IllegalStateException("获取企微 access_token 失败：" + tokenResp);
        }
        String payload = buildWecomAppPayload(agentId, toUser, title, markdownContent);
        String sendUrl = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token="
                + URLEncoder.encode(accessToken, "UTF-8");
        String sendResp = postJson(sendUrl, payload);
        JsonNode sendNode = validatePlatformResponse("wecom_app", sendResp);
        if (sendNode != null && sendNode.hasNonNull("invaliduser")) {
            String invalid = sendNode.get("invaliduser").asText("").trim();
            if (!invalid.isEmpty() && invalid.equalsIgnoreCase(toUser)) {
                throw new IllegalStateException("接收人账号无效或不在应用可见范围内：" + invalid);
            }
        }
    }

    /** 解析 wecom-app://{corpId}/{agentId}/{toUser}，返回 [corpId, agentId, toUser]。 */
    public String[] parseWecomAppSpec(String spec) {
        String raw = spec == null ? "" : spec.trim();
        if (raw.startsWith("wecom-app://")) {
            raw = raw.substring("wecom-app://".length());
        }
        String[] segs = raw.split("/", -1);
        if (segs.length < 3 || segs[0].trim().isEmpty() || segs[1].trim().isEmpty() || segs[2].trim().isEmpty()) {
            throw new IllegalArgumentException("企微应用参数不完整，格式需为 wecom-app://{CorpID}/{AgentId}/{UserId}");
        }
        try {
            Integer.parseInt(segs[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("企微应用 AgentId 必须为纯数字：" + segs[1]);
        }
        return new String[]{segs[0].trim(), segs[1].trim(), segs[2].trim()};
    }

    /** 构造企微自建应用 text 载荷（个人微信「微信插件」原生兼容 text，不支持 markdown）。 */
    public String buildWecomAppPayload(int agentId, String toUser, String title, String markdownContent) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("touser", toUser);
        root.put("msgtype", "text");
        root.put("agentid", agentId);
        Map<String, Object> text = new LinkedHashMap<>();
        String cleanBody = markdownContent == null ? "" : markdownContent.replace("**", "");
        text.put("content", "【" + title + "】\n" + cleanBody);
        root.put("text", text);
        root.put("safe", 0);
        return objectMapper.writeValueAsString(root);
    }

    /** 校验企微/钉钉/飞书 JSON 业务返回码（这些平台失败时也返回 HTTP 200）。 */
    public JsonNode validatePlatformResponse(String channelType, String respBody) throws Exception {
        if (respBody == null || respBody.trim().isEmpty()) {
            return null;
        }
        String trimmed = respBody.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        JsonNode node = objectMapper.readTree(trimmed);
        if (node.has("errcode") && node.get("errcode").asInt(0) != 0) {
            int ec = node.get("errcode").asInt();
            String em = node.hasNonNull("errmsg") ? node.get("errmsg").asText() : trimmed;
            throw new IllegalStateException("平台拒绝推送 (errcode=" + ec + "): " + em);
        }
        if ("feishu".equalsIgnoreCase(channelType) && node.has("code") && node.get("code").asInt(0) != 0) {
            int ec = node.get("code").asInt();
            String em = node.hasNonNull("msg") ? node.get("msg").asText() : trimmed;
            throw new IllegalStateException("飞书拒绝推送 (code=" + ec + "): " + em);
        }
        return node;
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

    protected String postJson(String urlStr, String jsonBody) throws Exception {
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
        return body;
    }

    protected String getJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = is == null ? "" : readStream(is);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + body);
        }
        return body;
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
