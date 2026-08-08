package top.aole.rent.modules.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.billing.domain.RentBill;

@Mapper
public interface RentBillMapper extends BaseMapper<RentBill> {
}
