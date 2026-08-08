package top.aole.rent.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感操作审计留痕(P0-D · M1-15)。红冲/作废/淘汰/退货/投放审批 的越权拦截(DENIED)与放行执行(EXECUTED)。
 *
 * <p>只追加不可编辑(P1-16 高危留痕);身份取网关注入的占位身份(operator_name/role),
 * 切真 SSO 后由门户下发真身份,追溯口径不变。
 */
@Data
@TableName("yc_rent_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 敏感操作:合同作废/供应商淘汰/采购退货/投放审批/资产状态流转 */
    private String action;

    private String targetType;

    private Long targetId;

    /** DENIED(越权拦截)/EXECUTED(放行执行) */
    private String result;

    private Long operatorId;

    private String operatorName;

    private String operatorRole;

    private String detail;

    private LocalDateTime createTime;
}
