package top.aole.rent.common.auth;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminRecoveryToolTest {
    private Connection database() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:recovery-" + UUID.randomUUID() + ";MODE=MySQL");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE yc_rent_auth_user(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(64) UNIQUE,"
                    + "password_hash VARCHAR(255),display_name VARCHAR(64),role VARCHAR(32),status TINYINT,created_at TIMESTAMP)");
        }
        return connection;
    }

    @Test void createsPendingAdminWithoutStoringPlaintextCredentials() throws Exception {
        try (Connection connection = database()) {
            String code = AdminRecoveryTool.create(connection, "test", "123456");
            assertEquals(32, code.length());
            try (Statement query = connection.createStatement(); ResultSet row = query.executeQuery("SELECT * FROM yc_rent_auth_user WHERE username='test'")) {
                assertTrue(row.next());
                assertEquals("老板", row.getString("role"));
                assertEquals(2, row.getInt("status"));
                String stored = row.getString("password_hash");
                assertTrue(stored.length() <= 255);
                assertFalse(stored.contains(code));
                assertTrue(RecoveryCredential.verifyPassword("123456", stored));
                assertTrue(RecoveryCredential.verifyCode(code, stored));
                assertFalse(PasswordHasher.verify("123456", stored));
            }
        }
    }

    @Test void existingAccountIsNeverOverwrittenOrPromoted() throws Exception {
        try (Connection connection = database(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO yc_rent_auth_user(username,password_hash,role,status) VALUES('test','existing-hash','业务',1)");
            assertThrows(SQLException.class, () -> AdminRecoveryTool.create(connection, "test", "123456"));
            try (ResultSet row = statement.executeQuery("SELECT * FROM yc_rent_auth_user WHERE username='test'")) {
                assertTrue(row.next());
                assertEquals("existing-hash", row.getString("password_hash"));
                assertEquals("业务", row.getString("role"));
                assertEquals(1, row.getInt("status"));
            }
        }
    }

    @Test void invalidInputDoesNotCreateAccounts() throws Exception {
        try (Connection connection = database(); Statement statement = connection.createStatement()) {
            assertThrows(IllegalArgumentException.class, () -> AdminRecoveryTool.create(connection, "te'st", "123456"));
            assertThrows(IllegalArgumentException.class, () -> AdminRecoveryTool.create(connection, "test", null));
            assertThrows(IllegalArgumentException.class, () -> AdminRecoveryTool.create(connection, "test", "      "));
            try (ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM yc_rent_auth_user")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
        }
    }

    @Test void malformedAndExpiredCredentialsFailClosed() {
        assertFalse(RecoveryCredential.verifyPassword("123456", "bad$hash$hash"));
        assertFalse(RecoveryCredential.verifyCode("code", null));
        String expired = "1$" + PasswordHasher.hash("123456") + "$" + PasswordHasher.hash("code");
        assertFalse(RecoveryCredential.verifyPassword("123456", expired));
        assertFalse(RecoveryCredential.verifyCode("code", expired));
    }
}
