package top.aole.rent.modules.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.customer.domain.Customer;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
