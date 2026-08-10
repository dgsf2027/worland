package top.aole.rent.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.ai.domain.entity.LlmCallLog;

@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {
}
