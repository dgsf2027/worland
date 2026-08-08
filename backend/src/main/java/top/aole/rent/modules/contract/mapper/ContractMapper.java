package top.aole.rent.modules.contract.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.contract.domain.Contract;

@Mapper
public interface ContractMapper extends BaseMapper<Contract> {
}
