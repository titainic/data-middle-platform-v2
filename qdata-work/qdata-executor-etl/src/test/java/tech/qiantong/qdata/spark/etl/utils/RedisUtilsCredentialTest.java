package tech.qiantong.qdata.spark.etl.utils;

import com.alibaba.fastjson2.JSONObject;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** URI construction only: no Redis client, connection, heartbeat or Spark session. */
class RedisUtilsCredentialTest {
    private RedisURI uri(JSONObject config) throws Exception {
        Method method = RedisUtils.class.getDeclaredMethod("buildUri", JSONObject.class);
        method.setAccessible(true);
        return (RedisURI) method.invoke(null, config);
    }

    private JSONObject config() {
        JSONObject config = new JSONObject();
        config.put("host", "127.0.0.1");
        config.put("port", 16379);
        config.put("database", 3);
        return config;
    }

    @Test
    void explicitlyConfiguredPasswordIsPreservedWithoutTrimming() throws Exception {
        JSONObject config = config();
        config.put("password", " synthetic-password-with-spaces ");
        RedisURI actual = uri(config);
        assertArrayEquals(" synthetic-password-with-spaces ".toCharArray(), actual.getPassword());
        assertEquals("127.0.0.1", actual.getHost());
        assertEquals(16379, actual.getPort());
        assertEquals(3, actual.getDatabase());
    }

    @Test
    void missingPasswordNeverUsesPackagedCredential() throws Exception {
        assertNull(uri(config()).getPassword());
    }

    @Test
    void explicitNullPasswordKeepsUnauthenticatedRedisCompatible() throws Exception {
        JSONObject config = config();
        config.put("password", null);
        assertNull(uri(config).getPassword());
    }

    @Test
    void explicitEmptyPasswordKeepsUnauthenticatedRedisCompatible() throws Exception {
        JSONObject config = config();
        config.put("password", "");
        assertNull(uri(config).getPassword());
    }

    @Test
    void followingConfigurationDoesNotReusePriorPassword() throws Exception {
        JSONObject first = config();
        first.put("password", "synthetic-first-task-only");
        assertArrayEquals("synthetic-first-task-only".toCharArray(), uri(first).getPassword());
        assertNull(uri(config()).getPassword());
        assertNull(uri(null).getPassword());
    }
}
