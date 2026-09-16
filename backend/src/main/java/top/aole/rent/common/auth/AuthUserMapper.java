package top.aole.rent.common.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuthUserMapper extends BaseMapper<AuthUser> {
    /** 管理员变更串行化；锁内重查操作者与最后一个管理员，防止并发互相降级。 */
    @Select("SELECT id, username, display_name, role, status, portal_uid, last_login_at "
            + "FROM yc_rent_auth_user ORDER BY id FOR UPDATE")
    List<AuthUser> lockAccountsForPermissionChange();
}
