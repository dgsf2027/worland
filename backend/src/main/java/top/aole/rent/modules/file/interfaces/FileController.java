package top.aole.rent.modules.file.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.file.service.FileStorageService;

import java.nio.charset.StandardCharsets;

/**
 * 对象存储接口(M5-08 · 评审 P1-20)。合同/现场照上传 + 短时效签名URL + 服务端鉴权代理下载。
 * 下载必带签名 token,过期/篡改/越权一律拒(禁公开桶/可枚举 key)。
 */
@Api(tags = "对象存储 · 签名URL(合同/现场照·服务端鉴权代理)")
@RestController
@RequestMapping("/rent/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @ApiOperation("上传文件(bizType=contract/site_photo;ownerRole 可限定可见角色)")
    @PostMapping
    public R<FileStorageService.UploadResp> upload(@RequestParam("file") MultipartFile file,
                                                   @RequestParam String bizType,
                                                   @RequestParam(required = false) Long bizId,
                                                   @RequestParam(required = false) String ownerRole) {
        return R.ok(fileStorageService.upload(file, bizType, bizId, ownerRole));
    }

    @ApiOperation("获取短时效签名URL(先过一次鉴权;URL 内嵌 token·TTL 走 rule)")
    @GetMapping("/{id}/signed-url")
    public R<FileStorageService.SignedUrlResp> signedUrl(@PathVariable Long id) {
        return R.ok(fileStorageService.signedUrl(id));
    }

    @ApiOperation("签名URL下载代理(验签+服务端鉴权;过期/篡改/越权 → 403)")
    @GetMapping("/download")
    public ResponseEntity<ByteArrayResource> download(@RequestParam String token) {
        FileStorageService.DownloadPayload p = fileStorageService.download(token);
        String fn = p.getFileName() == null ? "file" : p.getFileName();
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(fn, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(p.getContentType()))
                .body(new ByteArrayResource(p.getBytes()));
    }
}
