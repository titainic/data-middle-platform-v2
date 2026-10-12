package tech.qiantong.qdata.common.database.core;

import org.junit.jupiter.api.Test;
import com.alibaba.fastjson2.JSON;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionDisplayConfigTest {
    @Test void retainsConnectionLabelsWithoutPasswordsTokensOrUrls() {
        String input="{\"dbname\":\"iot\",\"username\":\"reader\",\"password\":\"unit-secret\",\"url\":\"jdbc:unit?password=unit-secret\",\"accessToken\":\"unit-token\",\"extra\":{\"privateKey\":\"unit-key\"}}";
        var result=JSON.parseObject(ConnectionDisplayConfig.sanitize(input));
        assertEquals(Set.of("dbname","username"),result.keySet());assertEquals("iot",result.getString("dbname"));
        assertFalse(result.toJSONString().contains("unit-secret"));
    }
    @Test void malformedOrNestedValuesDoNotEscapeTheDisplayBoundary() {
        assertEquals("{}",ConnectionDisplayConfig.sanitize("not-json"));
        assertEquals("{}",ConnectionDisplayConfig.sanitize(null));
        assertEquals("{}",ConnectionDisplayConfig.sanitize("{\"username\":{\"password\":\"unit-secret\"}}"));
    }
    @Test void ossConnectionLabelsStayEditableWithoutReturningSecretOrOpaqueConfig() {
        String input="{\"keyId\":\"public-id\",\"bucket\":\"iot\",\"endpoint\":\"host\",\"domain\":\"example\",\"keySecret\":\"unit-secret\",\"config\":\"{\\\"password\\\":\\\"unit-secret\\\"}\"}";
        var result=JSON.parseObject(ConnectionDisplayConfig.sanitize(input));
        assertEquals(Set.of("keyId","bucket","endpoint","domain"),result.keySet());
        assertFalse(result.toJSONString().contains("unit-secret"));
    }
}
