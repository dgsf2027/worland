package top.aole.rent.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.rent.modules.inventory.domain.InvRental;

@Mapper
public interface InvRentalMapper extends BaseMapper<InvRental> {

    /** 同前缀(含已删除)最大的「前缀+3位流水」编号,用于生成下一个编号 */
    @Select("SELECT MAX(rental_no) FROM yc_rent_inv_rental WHERE rental_no REGEXP CONCAT('^', #{prefix}, '[0-9]{3}$')")
    String maxCode(@Param("prefix") String prefix);
}
