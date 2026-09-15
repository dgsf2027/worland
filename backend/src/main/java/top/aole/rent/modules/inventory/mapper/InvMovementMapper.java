package top.aole.rent.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.inventory.domain.InvMovement;

@Mapper
public interface InvMovementMapper extends BaseMapper<InvMovement> {
}
