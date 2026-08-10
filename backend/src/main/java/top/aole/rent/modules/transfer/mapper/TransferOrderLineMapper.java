package top.aole.rent.modules.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.transfer.domain.TransferOrderLine;

@Mapper
public interface TransferOrderLineMapper extends BaseMapper<TransferOrderLine> {
}
