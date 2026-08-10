package top.aole.rent.modules.imports.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入中心返回体(M5-07)。预览(映射/去重/转义标记)+ 提交结果 + 作业列表 + 模板字段。
 */
public class ImportDtos {

    /** 目标字段模板(前端做列映射下拉) */
    @Data
    public static class TemplateResp {
        private String targetType;
        private String label;
        private List<Field> fields = new ArrayList<>();
        private String dedupKeyLabel;
    }

    @Data
    public static class Field {
        private String key;
        private String label;
        private boolean required;

        public Field(String key, String label, boolean required) {
            this.key = key;
            this.label = label;
            this.required = required;
        }
    }

    /** 预览结果:逐行 ok/dup/err + 转义计数 */
    @Data
    public static class PreviewResp {
        private Long jobId;
        private String no;
        private String targetType;
        private String fileName;
        private Long fileSize;
        private int totalRows;
        private int okRows;
        private int dupRows;
        private int errRows;
        private int escapedCells;
        private List<String> headers = new ArrayList<>();
        private List<RowResult> rows = new ArrayList<>();
        private String status;
    }

    @Data
    public static class RowResult {
        private int rowNo;
        /** ok / dup(唯一键冲突) / err(校验失败·含越权拒) */
        private String status;
        private String message;
        /** 是否含公式注入被转义的单元格 */
        private boolean escaped;
        private Map<String, String> data = new LinkedHashMap<>();
    }

    /** 提交入库结果 */
    @Data
    public static class CommitResp {
        private Long jobId;
        private int imported;
        private int skippedDup;
        private int failed;
        private List<String> failures = new ArrayList<>();
    }

    /** 作业列表行 */
    @Data
    public static class JobRow {
        private Long id;
        private String no;
        private String targetType;
        private String fileName;
        private Integer totalRows;
        private Integer okRows;
        private Integer dupRows;
        private Integer errRows;
        private Integer escapedCells;
        private Integer importedRows;
        private String status;
        private String operatorName;
        private LocalDateTime createTime;
        private LocalDateTime committedAt;
    }
}
