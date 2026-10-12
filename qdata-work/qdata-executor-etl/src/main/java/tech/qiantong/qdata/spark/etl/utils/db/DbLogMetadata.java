/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */
package tech.qiantong.qdata.spark.etl.utils.db;

import com.alibaba.fastjson2.JSONObject;
import tech.qiantong.qdata.common.database.constants.DbType;

/** Only allow connection identifiers in task and application logs. */
public final class DbLogMetadata {

    private DbLogMetadata() {
    }

    public static String describe(JSONObject parameter, String dbType) {
        JSONObject connection = parameter == null ? null : parameter.getJSONObject("connection");
        return "datasourceId=" + safeId(parameter == null ? null : parameter.getString("datasourceId"))
                + ", dbType=" + safeType(dbType)
                + ", table=" + safeTable(connection == null ? null : connection.getString("table"));
    }

    private static String safeId(String value) {
        return value != null && value.matches("[0-9]{1,19}") ? value : "<redacted>";
    }

    private static String safeType(String value) {
        for (DbType type : DbType.values()) {
            if (type.getDb().equals(value)) {
                return value;
            }
        }
        return "<redacted>";
    }

    private static String safeTable(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
                ? value : "<redacted>";
    }
}
