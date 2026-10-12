import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tech.qiantong.qdata.ai.controller.admin.chat.ChatConversationController;
import tech.qiantong.qdata.ai.controller.admin.chat.ChatMessageController;
import tech.qiantong.qdata.ai.core.service.AiConversationAccess;
import tech.qiantong.qdata.ai.core.service.IChatConversationService;
import tech.qiantong.qdata.ai.core.service.IChatMessageService;
import tech.qiantong.qdata.ai.core.service.impl.ChatMessageServiceImpl;
import tech.qiantong.qdata.ai.core.vo.AiChatConversationSaveRespVO;
import tech.qiantong.qdata.ai.core.vo.ChatMessageSendReqVO;
import tech.qiantong.qdata.ai.core.vo.ChatMessageSendRespVO;
import tech.qiantong.qdata.ai.core.vo.ChatMessageExportDetailDataReqVO;
import tech.qiantong.qdata.ai.server.config.SecurityConfig;
import tech.qiantong.qdata.ai.server.filter.JwtAuthenticationTokenFilter;
import tech.qiantong.qdata.ai.server.web.AiAccessExceptionHandler;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.core.redis.RedisCache;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.module.ai.controller.admin.chat.vo.AiChatConversationSaveReqVO;
import tech.qiantong.qdata.module.ai.dal.dataobject.chat.AiChatConversationDO;
import tech.qiantong.qdata.module.ai.dal.dataobject.chat.AiChatMessageDO;
import tech.qiantong.qdata.module.ai.service.chat.IAiChatConversationService;
import tech.qiantong.qdata.module.ai.service.chat.IAiChatMessageService;
import tech.qiantong.qdata.security.handle.LogoutSuccessHandlerImpl;
import tech.qiantong.qdata.security.web.exception.GlobalExceptionHandler;
import tech.qiantong.qdata.security.web.service.PermissionService;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class AiChatSecurityHttpProbe {
    private static final State state = new State();
    private static final String testSecret = Base64.getEncoder().encodeToString(new byte[64]);
    private static int assertions;

    public static void main(String[] arguments) throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext(TestConfiguration.class)) {
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            String alice = mainToken(2L, "alice", state.redis);
            String bob = mainToken(3L, "bob", state.redis);
            String administrator = mainToken(1L, "admin", state.redis);
            check(loginUser(2L, "alice").getPermissions().isEmpty(), "Ordinary owner fixture unexpectedly has menu permissions");
            String sendOwned = "{\"conversationId\":10,\"content\":\"{\\\"msg\\\":\\\"hello\\\"}\"}";
            String sendForeign = sendOwned.replace(":10", ":20");
            request(base, "POST", "/chat/message/send-stream", null, sendOwned, 401);
            request(base, "DELETE", "/chat/message/101", null, null, 401);
            request(base, "DELETE", "/chat/message/deleteByConversationId?conversationId=10", null, null, 401);
            request(base, "GET", "/chat/message/list-by-conversation-id?conversationId=10", null, null, 401);
            request(base, "GET", "/chat/conversation/myList", null, null, 401);
            request(base, "GET", "/chat/conversation/10", null, null, 401);
            request(base, "DELETE", "/chat/conversation/10", null, null, 401);
            request(base, "POST", "/chat/message/send-stream", "invalid-token", sendOwned, 401);
            check(state.businessCalls == 0, "Anonymous or invalid-token request reached business services");

            request(base, "POST", "/chat/message/send-stream", alice, sendForeign, 403);
            request(base, "POST", "/chat/message/send-stream", alice, sendOwned.replace(":10", ":999"), 403);
            check(state.llmCalls == 0 && state.mutations == 0, "Rejected send reached LLM or mutation boundary");
            request(base, "POST", "/chat/message/send-stream", alice, sendOwned, 200);
            check(state.lastSender.equals(2L), "Sender was not resolved from the shared main-platform session");
            check(state.llmCalls == 1, "Owner SSE positive path was not exercised");

            int mutations = state.mutations;
            request(base, "DELETE", "/chat/message/201", alice, null, 403);
            request(base, "DELETE", "/chat/message/101,201", alice, null, 403);
            request(base, "DELETE", "/chat/message/999", alice, null, 403);
            request(base, "DELETE", "/chat/message/deleteByConversationId?conversationId=20", alice, null, 403);
            check(state.mutations == mutations, "Foreign or mixed batch mutated message data");
            request(base, "DELETE", "/chat/message/101,102", alice, null, 200);
            check(state.mutations == mutations + 1, "Owned message batch did not reach mutation boundary");
            request(base, "DELETE", "/chat/message/deleteByConversationId?conversationId=10", alice, null, 200);
            check(state.mutations == mutations + 2, "Owner conversation clear did not reach mutation boundary");

            request(base, "GET", "/chat/message/list-by-conversation-id?conversationId=20", alice, null, 403);
            request(base, "GET", "/chat/message/list-by-conversation-id?conversationId=10", alice, null, 200);
            request(base, "GET", "/chat/message/exportDetailData?messageId=201", alice, null, 403);
            check(state.exports == 0, "Foreign export reached data export boundary");
            request(base, "GET", "/chat/message/exportDetailData?messageId=101", alice, null, 200);
            check(state.exports == 1, "Owned export was not exercised");

            request(base, "GET", "/chat/conversation/20", alice, null, 403);
            request(base, "GET", "/chat/conversation/10", alice, null, 200);
            request(base, "GET", "/chat/conversation/20", bob, null, 200);
            request(base, "GET", "/chat/conversation/20", administrator, null, 403);
            mutations = state.mutations;
            request(base, "PUT", "/chat/conversation", alice, "{\"id\":20,\"userId\":2}", 403);
            request(base, "POST", "/chat/conversation/setAssociations", alice, "{\"id\":20,\"associations\":\"[]\"}", 403);
            request(base, "DELETE", "/chat/conversation/10,20", alice, null, 403);
            check(state.mutations == mutations, "Foreign conversation mutation or mixed delete reached database boundary");
            request(base, "PUT", "/chat/conversation", alice, "{\"id\":10,\"userId\":3}", 200);
            check(state.lastConversationOwner.equals(2L), "Owner could transfer the conversation to another identity");
            request(base, "POST", "/chat/conversation", alice, "{\"userId\":3,\"title\":\"owned\"}", 200);
            check(state.lastConversationOwner.equals(2L), "New conversation accepted a caller-supplied owner");

            int calls = state.businessCalls;
            state.redis.values.clear();
            request(base, "GET", "/chat/conversation/myList", bob, null, 401);
            check(state.businessCalls == calls, "Revoked main-platform token reached AI business services");
            verifyServiceGuard(context);
            System.out.println("AI_SECURITY_HTTP_PROBE_PASS assertions=" + assertions);
        }
    }

    private static void verifyServiceGuard(ApplicationContext context) throws Exception {
        var principal = loginUser(2L, "alice");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var implementation = new ChatMessageServiceImpl();
        set(implementation, "conversationAccess", context.getBean(AiConversationAccess.class));
        var request = new ChatMessageSendReqVO();
        request.setConversationId(20L);
        request.setContent("invalid-json");
        denied(() -> implementation.sendChatMessageStream(request, 2L), "Service sender bypassed foreign-owner check");
        denied(() -> implementation.sendChatMessageStream(request, 1L), "Service accepted a spoofed sender");
        var export = new ChatMessageExportDetailDataReqVO();
        export.setMessageId(201L);
        denied(() -> implementation.exportDetailData(null, export), "Service export bypassed foreign-owner check");
        SecurityContextHolder.clearContext();
    }

    private static void denied(Runnable operation, String message) {
        try { operation.run(); throw new AssertionError(message); }
        catch (AccessDeniedException expected) { assertions++; }
    }

    private static void request(String base, String method, String path, String token, String body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).header("Accept", "*/*");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == expected, method + " " + path + " expected=" + expected + " actual=" + response.statusCode() + " body=" + response.body());
        if (expected == 200 && response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
            Integer code = com.alibaba.fastjson2.JSON.parseObject(response.body()).getInteger("code");
            check(code == null || code == 200, method + " " + path + " returned a business error: " + response.body());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static String mainToken(Long id, String name, MemoryRedis redis) throws Exception {
        TokenService main = tokenService(redis);
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(AiChatSecurityHttpProbe.class.getClassLoader(), new Class[]{HttpServletRequest.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("getHeader")) return "User-Agent".equals(arguments[0]) ? "AI-security-probe" : null;
            if (method.getName().equals("getRemoteAddr")) return "127.0.0.1";
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { return main.createToken(loginUser(id, name)); }
        finally { RequestContextHolder.resetRequestAttributes(); }
    }

    private static LoginUser loginUser(Long id, String name) {
        SysUser user = new SysUser(); user.setUserId(id); user.setUserName(name); user.setNickName(name);
        return new LoginUser(id, 1L, user, Set.of());
    }

    private static TokenService tokenService(MemoryRedis redis) throws Exception {
        TokenService service = new TokenService();
        set(service, "header", "Authorization"); set(service, "secret", testSecret);
        set(service, "expireTime", 600); set(service, "redisCache", redis);
        return service;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    public static class MemoryRedis extends RedisCache {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        @Override public <T> void setCacheObject(String key, T value, Integer timeout, TimeUnit unit) { values.put(key, value); }
        @Override @SuppressWarnings("unchecked") public <T> T getCacheObject(String key) { return (T) values.get(key); }
    }

    private static class State {
        final MemoryRedis redis = new MemoryRedis();
        final Map<Long, AiChatConversationDO> conversations = Map.of(10L, AiChatConversationDO.builder().id(10L).userId(2L).build(), 20L, AiChatConversationDO.builder().id(20L).userId(3L).build());
        final Map<Long, AiChatMessageDO> messages = Map.of(101L, AiChatMessageDO.builder().id(101L).conversationId(10L).userId(2L).build(), 102L, AiChatMessageDO.builder().id(102L).conversationId(10L).userId(2L).build(), 201L, AiChatMessageDO.builder().id(201L).conversationId(20L).userId(3L).build());
        volatile int businessCalls, mutations, llmCalls, exports;
        volatile Long lastSender, lastConversationOwner;

        @SuppressWarnings("unchecked") <T> T service(Class<T> type) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> type.getName();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> null;
                    };
                }
                businessCalls++;
                return switch (method.getName()) {
                    case "getAiChatConversationById" -> conversations.get(arguments[0]);
                    case "getAiChatMessageById" -> messages.get(arguments[0]);
                    case "getChatConversationListByUserId" -> conversations.values().stream().filter(value -> value.getUserId().equals(arguments[0])).toList();
                    case "list" -> List.of();
                    case "removeAiChatMessage", "removeAiChatConversation" -> { mutations++; yield 1; }
                    case "remove" -> { mutations++; yield true; }
                    case "updateAiChatConversation" -> { mutations++; lastConversationOwner = ((AiChatConversationSaveReqVO) arguments[0]).getUserId(); yield 1; }
                    case "createAiChatConversation" -> { lastConversationOwner = ((AiChatConversationSaveReqVO) arguments[0]).getUserId(); yield AiChatConversationSaveRespVO.builder().id(30L).code(200L).build(); }
                    case "sendChatMessageStream" -> { llmCalls++; lastSender = (Long) arguments[1]; yield Flux.just(new ChatMessageSendRespVO()); }
                    case "exportDetailData" -> { exports++; yield null; }
                    default -> throw new UnsupportedOperationException("Unexpected test boundary call " + method.getName());
                };
            });
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtAuthenticationTokenFilter.class, ChatMessageController.class, ChatConversationController.class, AiAccessExceptionHandler.class, GlobalExceptionHandler.class})
    public static class TestConfiguration {
        @Bean public static PropertySourcesPlaceholderConfigurer placeholders() { var configuration = new PropertySourcesPlaceholderConfigurer(); var properties = new Properties(); properties.setProperty("token.header", "Authorization"); properties.setProperty("token.secret", testSecret); properties.setProperty("token.expireTime", "600"); configuration.setProperties(properties); return configuration; }
        @Bean public static SpringUtils springUtils() { return new SpringUtils(); }
        @Bean public org.springframework.context.support.StaticMessageSource messageSource() { var source = new org.springframework.context.support.StaticMessageSource(); source.setUseCodeAsDefaultMessage(true); return source; }
        @Bean public ServletWebServerFactory serverFactory() { return new TomcatServletWebServerFactory(0); }
        @Bean public DispatcherServlet dispatcher(ApplicationContext context) { return new DispatcherServlet((WebApplicationContext) context); }
        @Bean public ServletRegistrationBean<DispatcherServlet> servletRegistration(DispatcherServlet servlet) { return new ServletRegistrationBean<>(servlet, "/"); }
        @Bean public FilterRegistrationBean<DelegatingFilterProxy> filterRegistration() { var registration = new FilterRegistrationBean<>(new DelegatingFilterProxy("springSecurityFilterChain")); registration.setAsyncSupported(true); return registration; }
        @Bean public TokenService tokenService() throws Exception { return AiChatSecurityHttpProbe.tokenService(state.redis); }
        @Bean public MemoryRedis redisCache() { return state.redis; }
        @Bean public RedisTemplate<Object, Object> redisTemplate() { var template = new RedisTemplate<Object, Object>(); var factory = (RedisConnectionFactory) Proxy.newProxyInstance(RedisConnectionFactory.class.getClassLoader(), new Class[]{RedisConnectionFactory.class}, (proxy, method, arguments) -> { throw new AssertionError("Probe attempted real Redis access: " + method.getName()); }); template.setConnectionFactory(factory); return template; }
        @Bean public UserDetailsService userDetailsService() { return name -> { throw new UsernameNotFoundException(name); }; }
        @Bean public LogoutSuccessHandlerImpl logoutSuccessHandler() { return new LogoutSuccessHandlerImpl(); }
        @Bean public ProjectPermissionService projectPermissionService() { return new ProjectPermissionService(new JdbcTemplate()); }
        @Bean(name="ss") public PermissionService permissionService() { return new PermissionService(); }
        @Bean public CorsFilter corsFilter() { var source = new UrlBasedCorsConfigurationSource(); var cors = new CorsConfiguration(); cors.setAllowedOrigins(List.of("*")); cors.setAllowedMethods(List.of("*")); cors.setAllowedHeaders(List.of("*")); source.registerCorsConfiguration("/**", cors); return new CorsFilter(source); }
        @Bean public IAiChatConversationService conversations() { return state.service(IAiChatConversationService.class); }
        @Bean public IAiChatMessageService messages() { return state.service(IAiChatMessageService.class); }
        @Bean public IChatConversationService conversationService() { return state.service(IChatConversationService.class); }
        @Bean public IChatMessageService messageService() { return state.service(IChatMessageService.class); }
        @Bean public AiConversationAccess conversationAccess(IAiChatConversationService conversations, IAiChatMessageService messages) { return new AiConversationAccess(conversations, messages); }
    }
}
