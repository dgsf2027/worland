package top.aole.rent.modules.payproof.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public class PayProofDtos {

    @Data
    public static class ProofView {
        private Long proofId;
        private Long fileId;
        private String fileName;
        private String contentType;
        private Long size;
        private String action;
        private String createByName;
        private LocalDateTime createTime;
    }

    /** 历史单据补传回单 */
    @Data
    public static class SupplementRequest {
        private String bizType;
        private Long bizId;
        private List<Long> fileIds;
    }
}
