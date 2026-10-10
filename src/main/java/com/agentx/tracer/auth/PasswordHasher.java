package com.agentx.tracer.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 用户密码摘要：SHA-256 + 应用级固定盐（pepper）。
 *
 * <p>数据库仅存 hex 摘要（64 位十六进制串），明文不落库；
 * init.sql 种子账号与存量数据迁移 SQL 按相同规则生成。
 */
public final class PasswordHasher {

    private static final String PEPPER = "agentx-tracer.pwd.v1";

    private PasswordHasher() {
    }

    public static String hash(String rawPassword) {
        String input = PEPPER + (rawPassword == null ? "" : rawPassword);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 算法", e);
        }
    }

    public static boolean matches(String rawPassword, String storedHash) {
        return storedHash != null && storedHash.length() == 64
                && hash(rawPassword).equals(storedHash);
    }
}
