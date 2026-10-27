package tech.qiantong.qdata.ai.core.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.database.utils.AesEncryptUtil;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.ai.dal.dataobject.model.AiModelDO;
import tech.qiantong.qdata.module.ai.service.model.IAiModelService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatModelServiceImplTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static String previousAesKey;
    private static String encryptedApiKey;
    private final ChatModelServiceImpl service = new ChatModelServiceImpl();
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private String origin;

    @BeforeAll
    static void prepareOfflineKey() throws Exception {
        previousAesKey = System.getProperty("qdata.aes.key");
        System.setProperty("qdata.aes.key", UUID.randomUUID().toString().substring(0, 16));
        encryptedApiKey = AesEncryptUtil.encrypt("offline-test-key");
    }

    @AfterAll
    static void restoreAesKey() {
        if (previousAesKey == null) {
            System.clearProperty("qdata.aes.key");
        } else {
            System.setProperty("qdata.aes.key", previousAesKey);
        }
    }

    @BeforeEach
    void startLocalStub() throws Exception {
        // 只监听回环地址，不启动业务服务、不使用真实凭据或外部模型。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
            boolean streaming = body.path("stream").asBoolean();
            boolean dashScope = exchange.getRequestURI().getPath().contains("/services/aigc/");
            String response;
            if (dashScope) {
                response = """
                        {"request_id":"offline-request","output":{"choices":[{"index":0,
                        "finish_reason":"stop","message":{"role":"assistant","content":"offline-ok"}}]},
                        "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
                        """;
            } else {
                String messageField = streaming ? "delta" : "message";
                response = """
                        {"id":"offline-request","object":"chat.completion","created":1,"model":"offline-model",
                        "choices":[{"index":0,"finish_reason":"stop","%s":{"role":"assistant","content":"offline-ok"}}],
                        "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """.formatted(messageField);
            }
            if (streaming) {
                response = "data: " + response.replace("\n", "") + "\n\ndata: [DONE]\n\n";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopLocalStub() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({
            "TongYi, '/', /api/v1/services/aigc/text-generation/generation, false",
            "TongYi, '/', /api/v1/services/aigc/text-generation/generation, true",
            "TongYi, /gateway, /gateway/api/v1/services/aigc/text-generation/generation, false",
            "TongYi, /gateway/, /gateway/api/v1/services/aigc/text-generation/generation, true",
            "DeepSeek, '/', /chat/completions, false",
            "DeepSeek, '/', /chat/completions, true",
            "DeepSeek, /gateway/v1, /gateway/v1/chat/completions, false",
            "DeepSeek, /gateway/v1/, /gateway/v1/chat/completions, true"
    })
    void configuredUrlReceivesSyncAndStreamRequests(String platform, String basePath,
                                                     String expectedPath, boolean streaming) throws Exception {
        ChatModel model = service.getChatModel(platform, origin + basePath, encryptedApiKey, "offline-model");
        verifyRequest(model, platform, expectedPath, streaming);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TongYi", "DeepSeek"})
    void storedModelUsesConfiguredUrl(String platform) throws Exception {
        IAiModelService models = mock(IAiModelService.class);
        AiModelDO storedModel = new AiModelDO();
        storedModel.setPlatform(platform);
        storedModel.setApiUrl(origin + "/stored");
        storedModel.setApiKey(encryptedApiKey);
        storedModel.setName("offline-model");
        when(models.getAiModelById(123L)).thenReturn(storedModel);
        ReflectionTestUtils.setField(service, "aiModelService", models);
        String path = platform.equals("TongYi")
                ? "/stored/api/v1/services/aigc/text-generation/generation" : "/stored/chat/completions";
        verifyRequest(service.getChatModel(123L), platform, path, false);
    }

    static Stream<Object[]> missingUrls() {
        return Stream.of("TongYi", "DeepSeek").flatMap(platform ->
                Stream.of(null, "", " \t ").map(url -> new Object[]{platform, url}));
    }

    @ParameterizedTest
    @MethodSource("missingUrls")
    void missingUrlFailsBeforeCreatingModel(String platform, String url) {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.getChatModel(platform, url, encryptedApiKey, "offline-model"));
        assertEquals("ai.error.field.required", error.getI18nCode());
        assertTrue(requests.isEmpty());
    }

    private void verifyRequest(ChatModel model, String platform, String expectedPath, boolean streaming)
            throws Exception {
        Prompt prompt = new Prompt("offline-prompt");
        List<ChatResponse> responses = streaming
                ? model.stream(prompt).collectList().block(Duration.ofSeconds(10))
                : List.of(model.call(prompt));
        assertNotNull(responses);
        assertTrue(responses.stream().anyMatch(response -> response.getResult() != null
                && "offline-ok".equals(response.getResult().getOutput().getText())));
        Request request = requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(request, "配置的本机 URL 必须收到真实 SDK 请求");
        assertEquals("POST", request.method());
        assertEquals(expectedPath, request.path());
        assertEquals("offline-model", request.body().path("model").asText());
        assertEquals(streaming, request.body().path("stream").asBoolean());
        assertTrue(requests.isEmpty(), "单次调用只应发送一次请求");
        System.out.printf("BIN-213 %s %s -> POST %s%s model=offline-model response=offline-ok%n",
                platform, streaming ? "stream" : "sync", origin, request.path());
    }

    private record Request(String method, String path, JsonNode body) {
    }
}
