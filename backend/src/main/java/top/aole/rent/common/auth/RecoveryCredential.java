package top.aole.rent.common.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/** 临时密码和一次性恢复码均以 PBKDF2 保存，30 分钟过期；激活后由普通密码哈希替换。 */
public final class RecoveryCredential {
    public static final int PENDING_STATUS = 2;
    private RecoveryCredential() {}

    public static String newCode() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String encode(String temporaryPassword, String recoveryCode) {
        return (Instant.now().getEpochSecond() + 1800) + "$" + PasswordHasher.hash(temporaryPassword)
                + "$" + PasswordHasher.hash(recoveryCode);
    }

    public static boolean isPending(String stored) {
        return stored != null && stored.split("\\$", -1).length == 3;
    }

    public static boolean verifyPassword(String password, String stored) {
        String[] parts = validParts(stored);
        return parts != null && PasswordHasher.verify(password, parts[1]);
    }

    public static boolean verifyCode(String code, String stored) {
        String[] parts = validParts(stored);
        return parts != null && PasswordHasher.verify(code, parts[2]);
    }

    private static String[] validParts(String stored) {
        if (!isPending(stored)) return null;
        String[] parts = stored.split("\\$", -1);
        try {
            return Long.parseLong(parts[0]) > Instant.now().getEpochSecond() ? parts : null;
        } catch (NumberFormatException e) { return null; }
    }
}
