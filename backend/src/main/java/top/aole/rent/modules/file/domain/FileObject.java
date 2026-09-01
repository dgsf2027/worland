package top.aole.rent.modules.file.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件对象(M5-08 · DESIGN §二 · 评审 P1-20)。合同/现场照 · 短时效签名URL + 服务端鉴权代理。
 * storage_key 为 UUID(不可枚举·非顺序);访问必带签名 token 过服务端鉴权(禁公开桶)。
 */
@Data
@TableName("yc_rent_file_object")
public class FileObject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String storageKey;

    /** contract(合同)/site_photo(现场照)/import(导入原件)/asset_bom(BOM附件) */
    private String bizType;

    private Long bizId;

    private String fileName;

    private String contentType;

    private Long fileSize;

    private String storagePath;

    private Long projectId;

    /** 可见角色约束(空=经营角色可见) */
    private String ownerRole;

    private Long uploaderId;

    private String uploaderName;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
