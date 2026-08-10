package top.aole.rent.modules.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.task.domain.Task;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}
