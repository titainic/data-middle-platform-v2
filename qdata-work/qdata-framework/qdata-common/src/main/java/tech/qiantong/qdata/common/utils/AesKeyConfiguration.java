package tech.qiantong.qdata.common.utils;

import java.nio.charset.StandardCharsets;

public final class AesKeyConfiguration {

    private AesKeyConfiguration() {
    }

    public static String requireKey() {
        String key = System.getProperty("qdata.aes.key");
        if (key == null || key.isBlank()) {
            key = System.getenv("QDATA_AES_KEY");
        }
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length != 16) {
            throw new IllegalStateException("QDATA_AES_KEY must contain exactly 16 UTF-8 bytes");
        }
        return key;
    }
}
