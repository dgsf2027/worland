package top.aole.rent.common.auth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** 部署环境的一次性运维命令，非 HTTP 接口、非启动任务。只创建新账号，绝不覆盖已有账号。 */
public final class AdminRecoveryTool {
    private AdminRecoveryTool() {}

    public static void main(String[] args) {
        try {
            String username = args.length == 1 ? args[0] : "test";
            String url = requiredEnv("DB_URL");
            String dbUser = requiredEnv("DB_USERNAME");
            String dbPassword = requiredEnv("DB_PASSWORD");
            // 临时密码只走 stdin，不出现在命令行参数、环境变量或日志中。
            String temporaryPassword = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            try (Connection connection = DriverManager.getConnection(url, dbUser, dbPassword)) {
                String code = create(connection, username, temporaryPassword);
                System.out.println("已创建待激活管理员：" + username);
                System.out.println("一次性恢复码（30 分钟内有效，仅在此显示）：" + code);
                System.out.println("请在登录页使用临时密码登录，输入恢复码并设置新密码后再管理权限。");
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("建号失败：检查数据库连接和账号名是否已存在；现有账号不会被覆盖。SQLState=" + e.getSQLState());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("建号失败：请检查命令输入及部署环境。");
            System.exit(1);
        }
    }

    public static String create(Connection connection, String username, String temporaryPassword) throws SQLException {
        if (username == null || !username.matches("^[A-Za-z0-9_\\-]{3,32}$")) {
            throw new IllegalArgumentException("账号需 3–32 位字母、数字、下划线或横线");
        }
        if (temporaryPassword == null || temporaryPassword.length() < 6 || temporaryPassword.length() > 128
                || temporaryPassword.trim().length() < 6) {
            throw new IllegalArgumentException("临时密码需 6–128 位，必须通过标准输入传入");
        }
        String code = RecoveryCredential.newCode();
        String hash = RecoveryCredential.encode(temporaryPassword, code);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO yc_rent_auth_user(username,password_hash,display_name,role,status,created_at) "
                        + "VALUES(?,?,?,'老板',2,CURRENT_TIMESTAMP)")) {
            insert.setString(1, username);
            insert.setString(2, hash);
            insert.setString(3, "恢复管理员 " + username);
            insert.executeUpdate();
        }
        return code;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("部署环境缺少 " + name);
        return value;
    }
}
