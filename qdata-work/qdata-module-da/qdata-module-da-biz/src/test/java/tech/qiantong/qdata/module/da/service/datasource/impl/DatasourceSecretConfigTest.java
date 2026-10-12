package tech.qiantong.qdata.module.da.service.datasource.impl;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DatasourceSecretConfigTest {
    private static final String STORED = "{\"username\":\"reader\",\"password\":\"synthetic-ciphertext\",\"keySecret\":\"synthetic-oss-secret\",\"dbname\":\"iot\",\"config\":\"{\\\"retries\\\":2}\"}";

    @Test void blankOrOmittedSecretsKeepStoredValuesAndOpaqueSettings() {
        String result = DatasourceSecretConfig.merge(STORED,
                "{\"username\":\"new-reader\",\"password\":\"\",\"keySecret\":null}", List.of());
        var json = JSONUtil.parseObj(result);
        assertEquals("synthetic-ciphertext", json.getStr("password"));
        assertEquals("synthetic-oss-secret", json.getStr("keySecret"));
        assertEquals("new-reader", json.getStr("username"));
        assertEquals("{\"retries\":2}", json.getStr("config"));
        var imported = JSONUtil.parseObj(DatasourceSecretConfig.merge(STORED, null, List.of()));
        assertEquals("synthetic-ciphertext", imported.getStr("password"));
        assertEquals("synthetic-oss-secret", imported.getStr("keySecret"));
    }

    @Test void replacementAndExplicitClearHaveDifferentResults() {
        var replaced = JSONUtil.parseObj(DatasourceSecretConfig.merge(STORED,
                "{\"password\":\"new-synthetic-ciphertext\"}", List.of()));
        assertEquals("new-synthetic-ciphertext", replaced.getStr("password"));
        assertEquals("synthetic-oss-secret", replaced.getStr("keySecret"));
        var cleared = JSONUtil.parseObj(DatasourceSecretConfig.merge(STORED, "{}", List.of("password")));
        assertFalse(cleared.containsKey("password"));
        assertEquals("synthetic-oss-secret", cleared.getStr("keySecret"));
    }

    @Test void invalidOrContradictoryActionsNeverSilentlyDiscardSecrets() {
        assertThrows(IllegalArgumentException.class, () -> DatasourceSecretConfig.merge(STORED,
                "{\"password\":\"replacement\"}", List.of("password")));
        assertThrows(IllegalArgumentException.class, () -> DatasourceSecretConfig.merge(STORED,
                "{}", List.of("unknown")));
        assertThrows(IllegalArgumentException.class, () -> DatasourceSecretConfig.merge(STORED,
                "not-json", List.of()));
        assertThrows(IllegalArgumentException.class, () -> DatasourceSecretConfig.rejectClearOnCreate(List.of("password")));
    }

    @Test void legacyNullConfigurationCanBeInitializedButNotSilentlyKept() {
        var initialized = JSONUtil.parseObj(DatasourceSecretConfig.merge(null,
                "{\"username\":\"synthetic-reader\",\"dbname\":\"iot\"}", List.of()));
        assertEquals("synthetic-reader", initialized.getStr("username"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceSecretConfig.merge(null, null, List.of()));
    }
}
