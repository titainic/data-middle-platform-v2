package tech.qiantong.qdata.module.da.service.datasource.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONNull;
import java.util.List;
import java.util.Set;

/** Keeps stored credentials on edit without exposing them through datasource detail responses. */
final class DatasourceSecretConfig {
    private static final Set<String> SECRETS = Set.of("password", "keySecret");

    private DatasourceSecretConfig() { }

    static void rejectClearOnCreate(List<String> clearSecrets) {
        if (clearSecrets != null && !clearSecrets.isEmpty()) {
            throw new IllegalArgumentException("Credentials can only be cleared when editing a datasource");
        }
    }

    static String merge(String stored, String submitted, List<String> clearSecrets) {
        if (stored != null && !stored.isBlank() && !JSONUtil.isJsonObj(stored)) {
            throw new IllegalArgumentException("Stored datasource configuration is invalid");
        }
        if ((stored == null || stored.isBlank()) && (submitted == null || submitted.isBlank())) {
            throw new IllegalArgumentException("Datasource configuration is required");
        }
        if (submitted != null && !submitted.isBlank() && !JSONUtil.isJsonObj(submitted)) {
            throw new IllegalArgumentException("Datasource configuration is invalid");
        }
        JSONObject merged = stored == null || stored.isBlank() ? new JSONObject() : JSONUtil.parseObj(stored);
        JSONObject changes = submitted == null || submitted.isBlank() ? new JSONObject() : JSONUtil.parseObj(submitted);
        List<String> clears = clearSecrets == null ? List.of() : clearSecrets;
        for (String key : clears) {
            if (key == null || !SECRETS.contains(key)) {
                throw new IllegalArgumentException("Unknown credential field");
            }
            Object replacement = changes.get(key);
            if (!empty(replacement)) {
                throw new IllegalArgumentException("A credential cannot be replaced and cleared together");
            }
        }
        for (var entry : changes.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!SECRETS.contains(key)) {
                merged.set(key, value);
            } else if (!empty(value) && !clears.contains(key)) {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("Credential value must be a string");
                }
                merged.set(key, value);
            }
        }
        for (String key : clears) merged.remove(key);
        return merged.toString();
    }

    private static boolean empty(Object value) {
        return value == null || value == JSONNull.NULL || value instanceof String text && text.isBlank();
    }
}
