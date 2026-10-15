package tech.qiantong.qdata.common.database;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.utils.AesEncryptUtil;
import tech.qiantong.qdata.common.database.exception.DataQueryException;
import static org.junit.jupiter.api.Assertions.*;

class DatasourcePasswordCompatibilityTest {
    private DbQueryProperty property(String password) {
        var json = new com.alibaba.fastjson2.JSONObject();
        json.put("username", "test-user"); json.put("password", password); json.put("dbname", "test");
        return new DbQueryProperty("MySql", "example.invalid", 3306L, json.toJSONString());
    }

    @Test void plaintextIsReadWithoutRequiringAnAesKeyOrTrimmingIt() {
        String before = System.getProperty("qdata.aes.key");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AesEncryptUtil.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            System.setProperty("qdata.aes.key", "invalid-key");
            for (String text : java.util.List.of("ordinary-test-pass", " space-sensitive ", "abc123", "YWJjZA==")) {
                assertEquals(text, property(text).getPassword());
            }
            assertTrue(events.list.isEmpty(), "Plaintext must not enter the AES failure path");
        } finally {
            logger.detachAppender(events); events.stop();
            if (before == null) System.clearProperty("qdata.aes.key"); else System.setProperty("qdata.aes.key", before);
        }
    }

    @Test void legacyCiphertextStillDecryptsAndInvalidKeyDoesNotBecomeThePassword() throws Exception {
        String before = System.getProperty("qdata.aes.key");
        try {
            System.setProperty("qdata.aes.key", "0123456789abcdef");
            String ciphertext = AesEncryptUtil.encrypt(" space-sensitive ");
            assertEquals(" space-sensitive ", property(ciphertext).getPassword());
            System.setProperty("qdata.aes.key", "invalid-key");
            var error = assertThrows(DataQueryException.class, () -> property(ciphertext));
            assertFalse(error.getMessage().contains(ciphertext));
            assertFalse(error.getMessage().contains("space-sensitive"));
        } finally {
            if (before == null) System.clearProperty("qdata.aes.key"); else System.setProperty("qdata.aes.key", before);
        }
    }
}
