package tech.qiantong.qdata.common.httpClient;

import cn.hutool.core.io.IORuntimeException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.exception.ServiceException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Transport doubles only: no scheduler, database or credentials are used. */
class DsRequestUtilsTest {
    private final DsRequestUtils config = new DsRequestUtils();
    private String previousBaseUrl;
    private String previousToken;

    @BeforeEach void configure() {
        previousBaseUrl = (String) ReflectionTestUtils.getField(DsRequestUtils.class, "baseUrl");
        previousToken = (String) ReflectionTestUtils.getField(DsRequestUtils.class, "token");
        config.setBaseUrl("http://scheduler.invalid");
        config.setToken("offline-test-token");
    }

    @AfterEach void restore() {
        config.setBaseUrl(previousBaseUrl);
        config.setToken(previousToken);
    }

    private HttpRequest outboundRequest() {
        HttpRequest outbound = mock(HttpRequest.class, RETURNS_SELF);
        when(outbound.header(anyString(), nullable(String.class))).thenReturn(outbound);
        return outbound;
    }

    private Map<?, ?> request() {
        return DsRequestUtils.request("/projects", "POST", Map.of("name", "offline"), null, Map.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void blankConfigurationStopsBeforeCreatingAnHttpRequest(String baseUrl) {
        config.setBaseUrl(baseUrl);
        try (var transport = mockStatic(HttpUtil.class)) {
            ServiceException failure = assertThrows(ServiceException.class, this::request);
            assertEquals(400, failure.getCode());
            assertTrue(failure.getMessage().contains("Quartz"));
            transport.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://", "scheduler", "ftp://scheduler.invalid", "http:///projects", "http://bad host"})
    void malformedBaseUrlIsBusinessFailureWithCauseAndNoRequest(String baseUrl) {
        config.setBaseUrl(baseUrl);
        try (var transport = mockStatic(HttpUtil.class)) {
            ServiceException failure = assertThrows(ServiceException.class, this::request);
            assertEquals(400, failure.getCode());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertFalse(failure.getMessage().contains(baseUrl));
            transport.verifyNoInteractions();
        }
    }

    @Test void connectionTimeoutAndIllegalUrlKeepTheOriginalCauseAndNeverRetry() {
        for (RuntimeException cause : new RuntimeException[]{
                new IORuntimeException(new ConnectException("Connection refused")),
                new IORuntimeException(new SocketTimeoutException("Read timed out")),
                new IllegalArgumentException("protocol = http host = null")}) {
            HttpRequest outbound = outboundRequest();
            when(outbound.execute()).thenThrow(cause);
            try (var transport = mockStatic(HttpUtil.class)) {
                transport.when(() -> HttpUtil.createRequest(Method.POST, "http://scheduler.invalid/projects"))
                        .thenReturn(outbound);
                ServiceException failure = assertThrows(ServiceException.class, this::request);
                assertEquals(400, failure.getCode());
                assertSame(cause, failure.getCause());
                assertTrue(failure.getMessage().contains("核对调度端"));
                assertFalse(failure.getMessage().contains(cause.getMessage()));
                verify(outbound).timeout(10_000);
                verify(outbound, times(1)).execute();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 401, 403, 500, 503})
    void non2xxIsRejectedBeforeParsingEvenIfBodyClaimsSuccess(int status) {
        HttpRequest outbound = outboundRequest();
        HttpResponse response = mock(HttpResponse.class);
        when(outbound.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(status);
        when(response.body()).thenReturn("{\"code\":0,\"internal\":\"secret\"}");
        try (var transport = mockStatic(HttpUtil.class)) {
            transport.when(() -> HttpUtil.createRequest(Method.POST, "http://scheduler.invalid/projects"))
                    .thenReturn(outbound);
            ServiceException failure = assertThrows(ServiceException.class, this::request);
            assertEquals(400, failure.getCode());
            assertTrue(failure.getCause().getMessage().contains(String.valueOf(status)));
            assertFalse(failure.getMessage().contains("secret"));
            verify(response, never()).body();
            verify(response).close();
        }
    }

    @Test void successfulJsonIsReturnedAndResponseIsClosed() {
        HttpRequest outbound = outboundRequest();
        HttpResponse response = mock(HttpResponse.class);
        when(outbound.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(201);
        when(response.body()).thenReturn("{\"code\":0}");
        try (var transport = mockStatic(HttpUtil.class)) {
            transport.when(() -> HttpUtil.createRequest(Method.POST, "http://scheduler.invalid/projects"))
                    .thenReturn(outbound);
            assertEquals(0, request().get("code"));
            verify(response).close();
        }
    }

    @Test void invalidJsonIsBusinessFailureAndResponseIsClosed() {
        HttpRequest outbound = outboundRequest();
        HttpResponse response = mock(HttpResponse.class);
        when(outbound.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(200);
        when(response.body()).thenReturn("<html>private upstream error</html>");
        try (var transport = mockStatic(HttpUtil.class)) {
            transport.when(() -> HttpUtil.createRequest(Method.POST, "http://scheduler.invalid/projects"))
                    .thenReturn(outbound);
            ServiceException failure = assertThrows(ServiceException.class, this::request);
            assertNotNull(failure.getCause());
            assertFalse(failure.getMessage().contains("private upstream"));
            verify(response).close();
        }
    }
}
