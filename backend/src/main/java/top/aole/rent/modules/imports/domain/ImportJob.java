package top.aole.rent.modules.imports.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入作业(M5-07 · DESIGN §二 导入中心)。Excel 映射/预览/去重 → 走正常单据同一校验+事件流。
 * preview_json 存逐行结果(ok/dup/err + 转义标记);确认后逐行调 service.create 入库。
 */
@Data
@TableName("yc_rent_import_job")
public class ImportJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    /** supplier / customer / asset */
    private String targetType;

    private String fileName;

    private Long fileSize;

    private Integer totalRows;

    private String mappingJson;

    private Integer okRows;

    private Integer dupRows;

    private Integer errRows;

    private Integer escapedCells;

    private Integer importedRows;

    /** 待确认(预览)/已导入/已作废 */
    private String status;

    private String previewJson;

    private Long projectId;

    private Long operatorId;

    private String operatorName;

    private String operatorRole;

    private LocalDateTime committedAt;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
