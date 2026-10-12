package tech.qiantong.qdata.module.da.controller.admin.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tech.qiantong.qdata.common.constant.CacheConstants;
import tech.qiantong.qdata.common.constant.Constants;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.core.redis.RedisCache;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.config.FilterConfig;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.service.asset.ManagedObjectConfiguration;
import tech.qiantong.qdata.module.da.service.asset.ManagedObjectProjectAuthorizer;
import tech.qiantong.qdata.security.config.SecurityConfig;
import tech.qiantong.qdata.security.config.SpaRequestMatcher;
import tech.qiantong.qdata.security.config.properties.PermitAllUrlProperties;
import tech.qiantong.qdata.security.filter.JwtAuthenticationTokenFilter;
import tech.qiantong.qdata.security.handle.AuthenticationEntryPointImpl;
import tech.qiantong.qdata.security.handle.LogoutSuccessHandlerImpl;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Tomcat, SecurityConfig, JWT/TokenService, MVC and project-authorizer logic.
 * Only infrastructure collaborators are synthetic; no production configuration or network is loaded. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedObjectRealSecurityHttpTest {
    private static final long USER = 927, OTHER = 928, PROJECT = 44, ASSET = 77;
    private static final String ROOT = "/da/managed-objects";
    private static final byte[] CSV = "station,value\nwater,7.1\n".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[64]);
    private ServletWebServerApplicationContext context;
    private Fixtures fixtures;
    private int port;
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll void start() {
        context = application(true, true);
        fixtures = context.getBean(Fixtures.class);
        port = context.getWebServer().getPort();
        assertTrue(port > 0);
        FilterChainProxy security = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        assertTrue(security.getFilterChains().stream().flatMap(chain -> chain.getFilters().stream())
                .anyMatch(JwtAuthenticationTokenFilter.class::isInstance));
        assertEquals(1, context.getBeansOfType(SecurityConfig.class).size());
    }

    @AfterAll void stop() throws IOException {
        if (context != null) context.close();
        if (port > 0) {
            assertThrows(IOException.class, () -> {
                try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 500); }
            }, "the test server must no longer listen after close");
        }
        Path report = Path.of("target", "managed-object-real-http-observations.json");
        Files.createDirectories(report.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(),
                Map.of("listener", "127.0.0.1:" + port, "listenerClosed", true, "requests", observations,
                        "scope", "real project security/JWT/Tomcat; synthetic infrastructure only"));
    }
    @BeforeEach void reset() { fixtures.reset(); }

    @Test void realXssRegistrationPreservesEveryConfiguredUrlPattern() {
        FilterConfig config = new FilterConfig();
        ReflectionTestUtils.setField(config, "excludes", "");
        for (String patterns : List.of("/*", "/da/*,/dm/*,/dpp/*")) {
            ReflectionTestUtils.setField(config, "urlPatterns", patterns);
            assertEquals(Set.of(patterns.split(",")),
                    new HashSet<>(config.xssFilterRegistration().getUrlPatterns()));
        }
        for (String blank : List.of("", " ", "\t")) {
            ReflectionTestUtils.setField(config, "urlPatterns", blank);
            assertThrows(IllegalArgumentException.class, config::xssFilterRegistration);
        }
    }

    @Test void realJwtChainDeniesAbsentAndRevokedCachedSessionsBeforeBodyRead() throws Exception {
        for (String bearer : Arrays.asList(null, signed("missing-cache-record"))) {
            var response = send("POST", uploadPath(PROJECT), bearer, "text/csv", CSV);
            // Existing project entrypoint uses its standard HTTP 200 / business code 401 envelope.
            assertEquals(200, response.statusCode());
            assertEquals(401, new ObjectMapper().readTree(response.body()).path("code").asInt());
            assertEquals(0, fixtures.bytesRead.get());
            assertTrue(fixtures.rows.isEmpty());
            assertEquals(0, fixtures.puts.get());
        }
        assertTrue(fixtures.cacheReads.get() > 0, "real TokenService must query the test Redis seam");
    }

    @Test void validJwtUploadsBindsAndDownloadsWithSafeOriginalHeaders() throws Exception {
        UUID id = upload();
        var bound = send("POST", ROOT + "/" + id + "/bind?assetId=" + ASSET,
                fixtures.token, null, null);
        assertEquals(204, bound.statusCode());
        var response = send("GET", ROOT + "/" + id + "/content", fixtures.token, null, null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(CSV, response.body());
        assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("sandbox; default-src 'none'", response.headers().firstValue("Content-Security-Policy").orElseThrow());
        assertEquals("attachment; filename=\"object-" + id + ".bin\"",
                response.headers().firstValue("Content-Disposition").orElseThrow());
        assertEquals(0, fixtures.partsParsed.get());
    }

    @Test void actualProjectAuthorizerRejectsCrossProjectAndNowDisabledAccount() throws Exception {
        UUID id = upload();
        int writes = fixtures.puts.get();
        assertEquals(403, send("POST", uploadPath(PROJECT + 1), fixtures.token, "text/csv", CSV).statusCode());
        assertEquals(403, send("POST", ROOT + "/" + id + "/bind?assetId=" + ASSET,
                fixtures.otherToken, null, null).statusCode());
        assertEquals(403, send("GET", ROOT + "/" + id + "/content",
                fixtures.otherToken, null, null).statusCode());
        fixtures.userEnabled.set(false); // the token and Redis LoginUser deliberately remain valid
        assertEquals(403, send("POST", uploadPath(PROJECT), fixtures.token, "text/csv", CSV).statusCode());
        assertEquals(403, send("POST", ROOT + "/" + id + "/bind?assetId=" + ASSET,
                fixtures.token, null, null).statusCode());
        assertEquals(403, send("GET", ROOT + "/" + id + "/content", fixtures.token, null, null).statusCode());
        assertEquals(writes, fixtures.puts.get());
        assertEquals(0, fixtures.opens.get());
    }

    @Test void multipartAndEncodedPathsNeverReachServletMultipartParsing() throws Exception {
        for (String path : List.of(ROOT, "/da/%6danaged-objects", "/da/managed-objects;v=1",
                "/da%2fmanaged-objects", "/da//managed-objects")) {
            var response = send("POST", path, fixtures.token, "multipart/form-data; boundary=fixture",
                    "--fixture\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nx\r\n--fixture--\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            assertRejection(response, Set.of(400, 415));
            assertEquals(0, fixtures.partsParsed.get(), path);
            assertEquals(0, fixtures.bytesRead.get(), path);
        }
        assertTrue(fixtures.rows.isEmpty());
    }

    @Test void announcedOversizeAndBindJsonAreRejectedBeforeBodyTransmission() throws Exception {
        assertEquals(413, headerOnlyStatus(uploadPath(PROJECT), "text/csv", ManagedObjectFlow.MAX_BYTES + 1L));
        assertEquals(400, headerOnlyStatus(ROOT + "/" + UUID.randomUUID() + "/bind?assetId=" + ASSET,
                "application/json", ManagedObjectFlow.MAX_BYTES + 1L));
        assertEquals(0, fixtures.bytesRead.get());
        assertEquals(0, fixtures.partsParsed.get());
        assertTrue(fixtures.rows.isEmpty());
    }

    @Test void chunkedOversizeStopsAtLimitPlusOneWithoutStoringAnything() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(uploadPath(PROJECT)))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + fixtures.token)
                .header("Content-Type", "text/csv").POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(new byte[ManagedObjectFlow.MAX_BYTES + 32_768]))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(413, response.statusCode());
        assertTrue(fixtures.sawChunked.get());
        assertEquals(ManagedObjectFlow.MAX_BYTES + 1L, fixtures.bytesRead.get());
        assertTrue(fixtures.rows.isEmpty());
        assertEquals(0, fixtures.puts.get());
        observe("POST", uploadPath(PROJECT) + " [chunked oversize]", response);
        observations.getLast().put("applicationBytesRead", fixtures.bytesRead.get());
    }

    @Test void noPreviewHeadOrCompressedUploadRoute() throws Exception {
        UUID id = upload();
        assertRejection(send("GET", ROOT + "/" + id + "/preview", fixtures.token, null, null), Set.of(404));
        assertEquals(405, send("HEAD", ROOT + "/" + id + "/content", fixtures.token, null, null).statusCode());
        HttpRequest compressed = HttpRequest.newBuilder(uri(uploadPath(PROJECT)))
                .header("Authorization", "Bearer " + fixtures.token).header("Content-Type", "text/csv")
                .header("Content-Encoding", "gzip").POST(HttpRequest.BodyPublishers.ofByteArray(CSV)).build();
        var response = client.send(compressed, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(415, response.statusCode());
        observe("POST", uploadPath(PROJECT) + " [gzip]", response);
        assertEquals(0, fixtures.opens.get());
    }

    @Test void defaultOffHasNoRouteAndEnabledWithoutStoreCannotBoot() throws Exception {
        try (var off = application(false, false)) {
            assertTrue(off.getBeansOfType(ManagedObjectController.class).isEmpty());
            assertTrue(off.getBeansOfType(ManagedObjectIngressFilter.class).isEmpty());
            assertTrue(off.getBeansOfType(ManagedObjectFlow.class).isEmpty());
            Fixtures f = off.getBean(Fixtures.class);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + off.getWebServer().getPort() + ROOT)).header("Authorization", "Bearer " + f.token).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertRejection(response, Set.of(404));
            observe("GET", ROOT + " [feature disabled]", response);
        }
        Exception missing = assertThrows(Exception.class, () -> application(true, false));
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = missing; cause != null; cause = cause.getCause()) chain.append(cause.getMessage());
        assertTrue(chain.toString().contains("ManagedObjectFlow$Store"), chain.toString());
    }

    @Test void enabledXssWithBlankUrlPatternsCannotStartTomcat() {
        Exception blank = assertThrows(Exception.class, () -> application(false, false, " "));
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = blank; cause != null; cause = cause.getCause()) chain.append(cause.getMessage());
        assertTrue(chain.toString().contains("xss.urlPatterns must not be blank"), chain.toString());
    }

    private UUID upload() throws Exception {
        var response = send("POST", uploadPath(PROJECT), fixtures.token, "text/csv; charset=UTF-8", CSV);
        assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        var node = new ObjectMapper().readTree(response.body());
        assertEquals(1, node.size());
        return UUID.fromString(node.path("fileId").asText());
    }
    private static void assertRejection(HttpResponse<byte[]> response, Set<Integer> expected) throws IOException {
        if (expected.contains(response.statusCode())) return;
        // Existing security configuration authenticates ERROR dispatches although its JWT filter
        // skips them: Tomcat/MVC 400/404 can be masked by the project's code=401 envelope.
        assertEquals(200, response.statusCode());
        assertEquals(401, new ObjectMapper().readTree(response.body()).path("code").asInt());
    }
    private static String uploadPath(long project) { return ROOT + "?projectId=" + project + "&purpose=ASSET_FILE"; }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<byte[]> send(String method, String path, String bearer, String type, byte[] body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (type != null) builder.header("Content-Type", type);
        var response = client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        observe(method, path, response);
        return response;
    }
    private void observe(String method, String path, HttpResponse<byte[]> response) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("method", method); record.put("path", path); record.put("httpStatus", response.statusCode());
        record.put("responseBytes", response.body().length);
        try {
            var body = new ObjectMapper().readTree(response.body());
            if (body != null && body.has("code")) record.put("businessCode", body.path("code").asInt());
        } catch (IOException notJson) { /* binary data is deliberately not recorded */ }
        observations.add(record);
    }
    private int headerOnlyStatus(String path, String type, long length) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            String headers = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nAuthorization: Bearer " + fixtures.token + "\r\nContent-Type: " + type
                    + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertNotNull(status);
            int code = Integer.parseInt(status.split(" ")[1]);
            observations.add(new LinkedHashMap<>(Map.of("method", "POST", "path", path,
                    "httpStatus", code, "bodyTransmitted", false)));
            return code;
        }
    }
    private static String signed(String key) { return Jwts.builder().claim(Constants.LOGIN_USER_KEY, key)
            .signWith(SignatureAlgorithm.HS512, SECRET).compact(); }
    private static ServletWebServerApplicationContext application(boolean enabled, boolean store) {
        return application(enabled, store, "/*");
    }
    private static ServletWebServerApplicationContext application(boolean enabled, boolean store, String xssPatterns) {
        SpringApplication app = new SpringApplication(TestApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setRegisterShutdownHook(false);
        return (ServletWebServerApplicationContext) app.run(
                "--spring.config.location=optional:classpath:/managed-object-test-no-config.properties",
                "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR", "--qdata.managed-object.enabled=" + enabled,
                "--fixture.store=" + store, "--token.header=Authorization", "--token.secret=" + SECRET,
                "--token.expireTime=30", "--xss.enabled=true", "--xss.excludes=", "--xss.urlPatterns=" + xssPatterns,
                "--spring.servlet.multipart.max-file-size=3000MB", "--spring.servlet.multipart.max-request-size=3000MB");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            MultipartAutoConfiguration.class, ErrorMvcAutoConfiguration.class, SecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class})
    @Import({SecurityConfig.class, JwtAuthenticationTokenFilter.class, TokenService.class, PermitAllUrlProperties.class,
            AuthenticationEntryPointImpl.class, LogoutSuccessHandlerImpl.class, SpaRequestMatcher.class, FilterConfig.class,
            ManagedObjectController.class, ManagedObjectIngressFilter.class, ManagedObjectConfiguration.class,
            ManagedObjectProjectAuthorizer.class})
    static class TestApplication {
        @Bean Fixtures fixtures() { return new Fixtures(); }
        @Bean UserDetailsService userDetailsService() { return username -> { throw new UsernameNotFoundException("test only"); }; }
        @Bean CorsFilter corsFilter() { return new CorsFilter(new UrlBasedCorsConfigurationSource()); }
        @Bean RedisCache redisCache(Fixtures f) { return f.redis; }
        @Bean RedisTemplate<?, ?> redisTemplate() { return mock(RedisTemplate.class); }
        @Bean JdbcTemplate jdbcTemplate(Fixtures f) { return f.jdbc; }
        @Bean DataResourceAccess resources(Fixtures f) { return f.resources; }
        @Bean ProjectPermissionService projectPermissions(Fixtures f) { return f.permissions; }
        @Bean ManagedObjectFlow.Repository repository(Fixtures f) { return f.repository; }
        @Bean @ConditionalOnProperty(name = "fixture.store", havingValue = "true")
        ManagedObjectFlow.Store store(Fixtures f) { return f.store; }
        @Bean FilterRegistrationBean<Filter> observation(Fixtures f) {
            var registration = new FilterRegistrationBean<Filter>();
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setFilter((request, response, chain) -> chain.doFilter(
                    new HttpServletRequestWrapper((HttpServletRequest) request) {
                        @Override public Collection<Part> getParts() throws IOException, ServletException {
                            f.partsParsed.incrementAndGet(); return super.getParts();
                        }
                        @Override public ServletInputStream getInputStream() throws IOException {
                            if ("chunked".equalsIgnoreCase(getHeader("Transfer-Encoding"))) f.sawChunked.set(true);
                            ServletInputStream input = super.getInputStream();
                            return new ServletInputStream() {
                                @Override public boolean isFinished() { return input.isFinished(); }
                                @Override public boolean isReady() { return input.isReady(); }
                                @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
                                @Override public int read() throws IOException {
                                    int n = input.read(); if (n >= 0) f.bytesRead.incrementAndGet(); return n;
                                }
                                @Override public int read(byte[] b, int off, int len) throws IOException {
                                    int n = input.read(b, off, len); if (n > 0) f.bytesRead.addAndGet(n); return n;
                                }
                            };
                        }
                    }, response));
            return registration;
        }
    }

    static class Fixtures {
        final AtomicBoolean userEnabled = new AtomicBoolean(true), sawChunked = new AtomicBoolean();
        final AtomicInteger puts = new AtomicInteger(), opens = new AtomicInteger(), partsParsed = new AtomicInteger(),
                cacheReads = new AtomicInteger();
        final AtomicLong bytesRead = new AtomicLong();
        final Map<UUID, ManagedObjectFlow.Record> rows = new ConcurrentHashMap<>();
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final RedisCache redis = mock(RedisCache.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final DataResourceAccess resources = mock(DataResourceAccess.class);
        final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
        final String token = signed("fixture-user"), otherToken = signed("fixture-other");
        Fixtures() {
            when(redis.getCacheObject(anyString())).thenAnswer(call -> {
                cacheReads.incrementAndGet();
                String key = call.getArgument(0);
                long id = key.equals(CacheConstants.LOGIN_TOKEN_KEY + "fixture-user") ? USER
                        : key.equals(CacheConstants.LOGIN_TOKEN_KEY + "fixture-other") ? OTHER : 0;
                if (id == 0) return null;
                SysUser user = new SysUser(); user.setUserId(id); user.setUserName("fixture-" + id);
                LoginUser login = new LoginUser(id, 1L, user, Set.of());
                login.setToken(key); login.setExpireTime(System.currentTimeMillis() + 3_600_000);
                return login;
            });
            when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                if (sql.contains("FROM system_user")) return userEnabled.get() ? 1L : 0L;
                if (sql.contains("FROM att_project")) return SecurityUtils.getUserId() == USER ? 1L : 0L;
                throw new AssertionError("unexpected query");
            });
            when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("type", "6")));
            when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(PROJECT));
            when(resources.visibleProjectIds()).thenAnswer(call -> Set.of(SecurityUtils.getUserId() == USER ? PROJECT : PROJECT + 1));
            when(resources.visibleAssetIds()).thenAnswer(call -> SecurityUtils.getUserId() == USER ? Set.of(ASSET) : Set.of());
            when(permissions.has(eq(PROJECT), anyString())).thenAnswer(call -> SecurityUtils.getUserId() == USER);
        }
        void reset() {
            userEnabled.set(true); sawChunked.set(false); puts.set(0); opens.set(0); partsParsed.set(0);
            cacheReads.set(0); bytesRead.set(0); rows.clear(); objects.clear();
        }
        final ManagedObjectFlow.Store store = new ManagedObjectFlow.Store() {
            @Override public void put(String key, byte[] value, String type) { puts.incrementAndGet(); objects.put(key, value.clone()); }
            @Override public InputStream open(String key) { opens.incrementAndGet(); return new ByteArrayInputStream(objects.get(key)); }
        };
        final ManagedObjectFlow.Repository repository = new ManagedObjectFlow.Repository() {
            @Override public void insert(ManagedObjectFlow.Record row) { rows.put(row.id(), row); }
            @Override public Optional<ManagedObjectFlow.Record> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
            @Override public boolean transition(UUID id, ManagedObjectFlow.Status expected, ManagedObjectFlow.Status next) {
                var old = rows.get(id); if (old == null || old.status() != expected) return false;
                rows.put(id, new ManagedObjectFlow.Record(id, old.projectId(), old.uploaderId(), old.purpose(),
                        old.objectKey(), old.mediaType(), old.length(), old.sha256(), next, old.assetId())); return true;
            }
            @Override public boolean bind(UUID id, long userId, long assetId) {
                var old = rows.get(id); if (old == null || old.assetId() != null || old.uploaderId() != userId) return false;
                rows.put(id, new ManagedObjectFlow.Record(id, old.projectId(), old.uploaderId(), old.purpose(),
                        old.objectKey(), old.mediaType(), old.length(), old.sha256(), old.status(), assetId)); return true;
            }
        };
    }
}
