package tech.qiantong.qdata.acceptance;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.utils.SecretRedactor;
import com.alibaba.fastjson2.JSON;
import static org.junit.jupiter.api.Assertions.*;
class SecretRedactorTest {
    @Test void nestedCredentialsAreRemovedWithoutHidingBusinessFilters(){String value=SecretRedactor.redact("{\"projectId\":1,\"config\":{\"password\":\"fixture-pass\",\"apiKey\":\"fixture-key\"},\"releaseId\":\"release-visible\"}");assertFalse(value.contains("fixture-pass"));assertFalse(value.contains("fixture-key"));assertEquals("release-visible",JSON.parseObject(value).getString("releaseId"));}
    @Test void namedHeaderDefinitionsAreMasked(){String value=SecretRedactor.redact("[{\"paramName\":\"Authorization\",\"value\":\"Bearer fixture-token\"},{\"paramName\":\"pageSize\",\"value\":20}]");assertFalse(value.contains("fixture-token"));assertTrue(value.contains("20"));}
    @Test void plaintextAndUrlCredentialsAreMasked(){String value=SecretRedactor.redact("Authorization: Bearer fixture-token\npassword='fixture-pass' url=https://user:fixture-url@host/path?api_key=fixture-key&release=visible");for(String secret:java.util.List.of("fixture-token","fixture-pass","fixture-url","fixture-key"))assertFalse(value.contains(secret),secret);assertTrue(value.contains("release=visible"));}
    @Test void encodedJsonBodiesRemainReadableAfterMasking(){String value=SecretRedactor.redact("{\"body\":\"{\\\"token\\\":\\\"fixture-token\\\",\\\"page\\\":1}\"}");String body=JSON.parseObject(value).getString("body");assertEquals(1,JSON.parseObject(body).getIntValue("page"));assertFalse(body.contains("fixture-token"));}
    @Test void ordinaryLogsAndEmptyValuesArePreserved(){assertEquals("Task 52 completed with 192 rows",SecretRedactor.redact("Task 52 completed with 192 rows"));assertNull(SecretRedactor.redact(null));assertEquals("",SecretRedactor.redact(""));}
}
