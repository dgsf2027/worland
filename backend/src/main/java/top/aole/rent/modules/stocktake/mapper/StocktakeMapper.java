package top.aole.rent.modules.stocktake.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.stocktake.domain.Stocktake;

@Mapper
public interface StocktakeMapper extends BaseMapper<Stocktake> {
}
