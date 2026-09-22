package top.aole.rent.modules.contract.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.aole.rent.modules.contract.domain.RentSchedule;

@Mapper
public interface RentScheduleMapper extends BaseMapper<RentSchedule> {

    /**
     * 物理删除可重排的计划行(未生成收租单的期次,含历史逻辑删除行)。
     *
     * <p>计划行是按合同要素算出来的派生数据,重排时会用同样的 (contract_id, period_no) 重新插入;
     * 逻辑删除会把行留在表里继续占着唯一键 uk_schedule_period,再插入就报 Duplicate entry,
     * 所以这里必须物理删。已生成收租单的期次(plan_status=已生成单 且未删)一律保留。
     */
    @Delete("DELETE FROM yc_rent_rent_schedule WHERE contract_id = #{contractId} "
            + "AND NOT (is_deleted = 0 AND plan_status = '已生成单' AND period_no <= #{keepUntilPeriod})")
    int hardDeleteReplaceable(@Param("contractId") Long contractId, @Param("keepUntilPeriod") int keepUntilPeriod);

    /** 物理删除单个未出单的计划行。 */
    @Delete("DELETE FROM yc_rent_rent_schedule WHERE id = #{id} AND NOT (is_deleted = 0 AND plan_status = '已生成单')")
    int hardDeleteById(@Param("id") Long id);

    /** 物理删除某期次起的计划行(含历史逻辑删除行),供续租追加期次前清场。 */
    @Delete("DELETE FROM yc_rent_rent_schedule WHERE contract_id = #{contractId} AND period_no >= #{fromPeriod} "
            + "AND NOT (is_deleted = 0 AND plan_status = '已生成单')")
    int hardDeleteFrom(@Param("contractId") Long contractId, @Param("fromPeriod") int fromPeriod);
}
