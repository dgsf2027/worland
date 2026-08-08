package top.aole.rent.modules.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.asset.domain.Asset;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {
}
