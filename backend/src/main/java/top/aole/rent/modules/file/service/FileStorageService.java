package top.aole.rent.modules.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 对象存储 + 签名URL 服务(M5-08 · 评审 P1-20)。本地文件系统模拟对象存储:
 * storage_key 用 UUID(不可枚举);<b>访问必带短时效签名 token + 服务端鉴权代理</b>(禁公开桶)。
 *
 * <ul>
 *   <li>签名:token = base64url(fileId:expiry:HMAC-SHA256(secret, fileId:expiry));TTL 走 rule_config file_sign_ttl</li>
 *   <li>过期失效:expiry &lt; now → 410/403;签名被篡改 → 403</li>
 *   <li>服务端鉴权:token 有效后再过行级/角色隔离(ownerRole 约束·成本敏感·非本人角色 403 越权)</li>
 * </ul>
 *
 * <p>占位期注:签名密钥进程内随机生成(重启轮换),真上线走 KMS/OSS STS;抗抵赖口径不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileObjectMapper fileObjectMapper;
    private final RuleConfigService ruleConfigService;

    @Value("${rent.storage.dir:storage}")
    private String storageDir;

    /** 进程内签名密钥(占位期随机;真上线走 KMS)。 */
    private final byte[] signSecret = newSecret();

    @Data
    public static class UploadResp {
        private Long id;
        private String storageKey;
        private String fileName;
        private Long size;
        private String bizType;
        private Long bizId;
    }

    @Data
    public static class FileItemResp {
        private Long id;
        private String fileName;
        private String contentType;
        private Long size;
        private String uploaderName;
        private LocalDateTime createTime;
    }

    @Data
    public static class SignedUrlResp {
        private Long id;
        private String url;
        private long expiresAt;
        private int ttlSeconds;
    }

    /** 下载载荷(经服务端鉴权代理返回) */
    @Data
    public static class DownloadPayload {
        private byte[] bytes;
        private String fileName;
        private String contentType;
    }

    // ============================== 上传 ==============================

    public UploadResp upload(MultipartFile file, String bizType, Long bizId, String ownerRole) {
        CurrentUser u = UserContext.require();
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请上传文件");
        }
        if (bizType == null || bizType.isEmpty()) {
            throw new BizException(400, "bizType 必填(contract/site_photo/import/asset_bom)");
        }
        String key = UUID.randomUUID().toString().replace("-", "");
        Path dir = Paths.get(storageDir, bizType);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(key);
            file.transferTo(target.toAbsolutePath());

            FileObject fo = new FileObject();
            fo.setStorageKey(key);
            fo.setBizType(bizType);
            fo.setBizId(bizId);
            fo.setFileName(file.getOriginalFilename());
            fo.setContentType(file.getContentType());
            fo.setFileSize(file.getSize());
            fo.setStoragePath(bizType + "/" + key);
            fo.setProjectId(u.getProjectId());
            fo.setOwnerRole(ownerRole == null || ownerRole.isEmpty() ? null : ownerRole);
            fo.setUploaderId(u.getUserId());
            fo.setUploaderName(u.getUserName());
            fileObjectMapper.insert(fo);

            UploadResp resp = new UploadResp();
            resp.setId(fo.getId());
            resp.setStorageKey(key);
            resp.setFileName(fo.getFileName());
            resp.setSize(fo.getFileSize());
            resp.setBizType(bizType);
            resp.setBizId(bizId);
            return resp;
        } catch (IOException e) {
            throw new BizException("文件保存失败:" + e.getMessage());
        }
    }

    // ============================== 附件列表 ==============================

    public List<FileItemResp> list(String bizType, Long bizId) {
        if (bizType == null || bizType.trim().isEmpty() || bizId == null) {
            throw new BizException(400, "bizType 和 bizId 必填");
        }
        CurrentUser u = UserContext.require();
        List<FileObject> files = fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getBizType, bizType.trim())
                .eq(FileObject::getBizId, bizId)
                .eq(FileObject::getIsDeleted, 0)
                .orderByDesc(FileObject::getId));
        List<FileItemResp> result = new ArrayList<>();
        for (FileObject fo : files) {
            authorize(fo, u);
            FileItemResp item = new FileItemResp();
            item.setId(fo.getId());
            item.setFileName(fo.getFileName());
            item.setContentType(fo.getContentType());
            item.setSize(fo.getFileSize());
            item.setUploaderName(fo.getUploaderName());
            item.setCreateTime(fo.getCreateTime());
            result.add(item);
        }
        return result;
    }

    // ============================== 签名URL ==============================

    /** 生成短时效签名URL(先过一次鉴权:能签的人才能拿链接)。 */
    public SignedUrlResp signedUrl(Long fileId) {
        FileObject fo = require(fileId);
        authorize(fo, UserContext.require()); // 签发前也鉴权
        int ttl = ruleConfigService.getValue("file_sign_ttl", "seconds", LocalDate.now()).intValue();
        long expiry = Instant.now().getEpochSecond() + ttl;
        String token = sign(fileId, expiry);

        SignedUrlResp resp = new SignedUrlResp();
        resp.setId(fileId);
        resp.setUrl("/api/rent/files/download?token=" + token);
        resp.setExpiresAt(expiry);
        resp.setTtlSeconds(ttl);
        return resp;
    }

    // ============================== 下载(服务端鉴权代理) ==============================

    public DownloadPayload download(String token) {
        long[] parsed = verify(token); // [fileId, expiry] 或抛 403
        Long fileId = parsed[0];
        FileObject fo = require(fileId);
        authorize(fo, UserContext.require()); // token 有效后再过行级/角色隔离

        Path target = Paths.get(storageDir, fo.getStoragePath());
        try {
            DownloadPayload p = new DownloadPayload();
            p.setBytes(Files.readAllBytes(target.toAbsolutePath()));
            p.setFileName(fo.getFileName());
            p.setContentType(fo.getContentType() == null ? "application/octet-stream" : fo.getContentType());
            return p;
        } catch (IOException e) {
            throw new BizException(404, "文件已不存在:" + fo.getStorageKey());
        }
    }

    // ============================== 签名/验签 ==============================

    private String sign(long fileId, long expiry) {
        String payload = fileId + ":" + expiry;
        String sig = hmac(payload);
        String raw = payload + ":" + sig;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 验签:篡改/过期 → 403。返回 [fileId, expiry]。 */
    private long[] verify(String token) {
        if (token == null || token.isEmpty()) {
            throw new BizException(403, "缺少签名 token");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException(403, "签名 token 非法");
        }
        String[] parts = raw.split(":");
        if (parts.length != 3) {
            throw new BizException(403, "签名 token 结构非法");
        }
        long fileId;
        long expiry;
        try {
            fileId = Long.parseLong(parts[0]);
            expiry = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BizException(403, "签名 token 非法");
        }
        String expect = hmac(parts[0] + ":" + parts[1]);
        if (!MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(403, "签名校验失败(token 被篡改)");
        }
        if (Instant.now().getEpochSecond() > expiry) {
            throw new BizException(403, "签名URL已过期(短时效失效),请重新获取");
        }
        return new long[]{fileId, expiry};
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret, "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException("签名失败:" + e.getMessage());
        }
    }

    // ============================== 服务端鉴权(行级/角色隔离) ==============================

    /** 过行级/角色隔离:ownerRole 约束 + 项目隔离。越权 → 403。 */
    private void authorize(FileObject fo, CurrentUser u) {
        // 项目隔离:文件属某项目且当前用户属另一项目 → 越权(占位期项目多为空,预留)
        if (fo.getProjectId() != null && u.getProjectId() != null
                && !fo.getProjectId().equals(u.getProjectId())) {
            throw new BizException(403, "越权:该文件属其它项目,行级隔离拒绝访问");
        }
        // 角色约束:限定可见角色 → 非该角色且非老板 → 越权
        if (fo.getOwnerRole() != null && !fo.getOwnerRole().equals(u.getRole())
                && !"老板".equals(u.getRole())) {
            throw new BizException(403, "越权:该文件限「" + fo.getOwnerRole() + "」角色可见,当前角色无权");
        }
        // 合同类含成本/价条款:仅经营角色可见(GP/LP 不可见成本 · DataScope 口径)
        if ("contract".equals(fo.getBizType()) && !DataScope.canSeeCost(u.getRole())) {
            throw new BizException(403, "越权:合同文件含成本/价条款,当前角色(不可见成本)无权");
        }
    }

    private FileObject require(Long id) {
        FileObject fo = fileObjectMapper.selectById(id);
        if (fo == null || Integer.valueOf(1).equals(fo.getIsDeleted())) {
            throw new BizException(404, "文件不存在: id=" + id);
        }
        return fo;
    }

    private static byte[] newSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
