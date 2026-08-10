package top.aole.rent.modules.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用记录(yc_rent_llm_call_log):透明四件套(推理过程/完整输出/置信分/原始数据)统一落库。
 * 铁律(§4.7 #7):LLM 永不直接算数,只起草综述;每个 AI 结论旁挂 🔬 过程入口指向本表。
 * 脱敏(DESIGN_DOC §十一):input_digest 喂入前已剔成本/分配/身份,库里存的即脱敏版。
 */
@Data
@TableName("yc_rent_llm_call_log")
public class LlmCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接入点场景:月报起草/… */
    private String scene;

    /** 模型名(可配不写死;mock 断路阶段 = mock(路由目标)) */
    private String model;

    /** Prompt 模板指纹(版本追踪) */
    private String promptFingerprint;

    /** 幂等键(接入点+业务key+当日,24h 窗口) */
    private String idempotentKey;

    /** 是否缓存命中 */
    private Integer cacheHit;

    /** 四件套·原始数据(脱敏后 JSON) */
    private String inputDigest;

    /** 四件套·推理过程(规则引擎计算说明) */
    private String reasoning;

    /** 四件套·完整输出(AI 综述全文) */
    private String outputText;

    /** 四件套·置信分(规则真算=数据完备度) */
    private BigDecimal confidence;

    /** 置信分来源:computed=规则真算 / fixed=固定基准 */
    private String confidenceSource;

    private Integer tokensIn;

    private Integer tokensOut;

    private Integer durationMs;

    /** 状态:成功/失败/降级 */
    private String callStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
