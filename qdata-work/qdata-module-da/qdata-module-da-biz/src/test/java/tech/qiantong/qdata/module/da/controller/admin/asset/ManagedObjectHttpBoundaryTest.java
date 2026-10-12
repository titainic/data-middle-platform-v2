package tech.qiantong.qdata.module.da.controller.admin.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;
import tech.qiantong.qdata.module.da.service.asset.ManagedObjectConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** No real object store, database, credentials, or external network is used. */
class ManagedObjectHttpBoundaryTest {
    private static final long USER = 927;
    private static final long PROJECT = 44;
    private static final long ASSET = 77;
    private static final byte[] CSV = "station,value\nwater,7.1\n".getBytes(StandardCharsets.UTF_8);

    private final MemoryRepository repository = new MemoryRepository();
    private final MemoryStore store = new MemoryStore();
    private final Permissions permissions = new Permissions();
    private final ManagedObjectFlow flow = new ManagedObjectFlow(repository, store, permissions);
    private final ManagedObjectController controller = new ManagedObjectController(flow);
    private MockMvc mvc;

    @BeforeEach void setup() {
        setUser(USER);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ManagedObjectIngressFilter()).build();
    }

    @AfterEach void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test void featureIsAbsentByDefaultAndCannotStartWithoutPrivateStore() {
        for (Class<?> candidate : List.of(ManagedObjectController.class,
                ManagedObjectIngressFilter.class, ManagedObjectConfiguration.class)) {
            ConditionalOnProperty condition = candidate.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(condition);
            assertEquals("qdata.managed-object", condition.prefix());
            assertArrayEquals(new String[]{"enabled"}, condition.name());
            assertEquals("true", condition.havingValue());
            assertFalse(condition.matchIfMissing());
        }
        assertEquals("isAuthenticated()",
                ManagedObjectController.class.getAnnotation(PreAuthorize.class).value());
        new WebApplicationContextRunner()
                .withUserConfiguration(ManagedObjectConfiguration.class,
                        ManagedObjectController.class, ManagedObjectIngressFilter.class)
                .run(context -> {
                    assertTrue(context.getBeansOfType(ManagedObjectController.class).isEmpty());
                    assertTrue(context.getBeansOfType(ManagedObjectIngressFilter.class).isEmpty());
                });
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(ManagedObjectConfiguration.class)
                .withBean(ManagedObjectFlow.Repository.class, () -> repository)
                .withBean(ManagedObjectFlow.Authorizer.class, () -> permissions);
        runner.run(context -> assertTrue(context.getBeansOfType(ManagedObjectFlow.class).isEmpty()));
        runner.withPropertyValues("qdata.managed-object.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("qdata.managed-object.enabled=true")
                .withBean(ManagedObjectFlow.Store.class, () -> store)
                .run(context -> assertEquals(1,
                        context.getBeansOfType(ManagedObjectFlow.class).size()));
    }

    @Test void enabledControllerIsMethodSecurityProxyAndDeniesMissingIdentity() {
        SecurityContextHolder.clearContext();
        new WebApplicationContextRunner()
                .withUserConfiguration(MethodSecurityHarness.class, ManagedObjectController.class)
                .withBean(ManagedObjectFlow.class, () -> flow)
                .withPropertyValues("qdata.managed-object.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ManagedObjectController secured = context.getBean(ManagedObjectController.class);
                    assertTrue(AopUtils.isAopProxy(secured));
                    assertThrows(AuthenticationException.class,
                            () -> secured.original(UUID.randomUUID()));
                    assertEquals(0, store.opens);
                });
    }

    @Test void rawUploadBindAndOriginalDownloadExposeOnlyOpaqueIdAndSafeAttachment() throws Exception {
        var uploaded = mvc.perform(post("/da/managed-objects")
                        .param("projectId", Long.toString(PROJECT))
                        .param("purpose", "ASSET_FILE")
                        .contentType("text/csv; charset=UTF-8").content(CSV))
                .andReturn().getResponse();
        assertEquals(201, uploaded.getStatus());
        String json = uploaded.getContentAsString();
        UUID id = UUID.fromString(new ObjectMapper().readTree(json).path("fileId").asText());
        assertFalse(json.contains("managed/"));
        assertFalse(json.contains("objectKey"));
        assertFalse(json.contains("url"));
        assertEquals("managed/" + id, repository.rows.get(id).objectKey());

        var bound = mvc.perform(post("/da/managed-objects/" + id + "/bind")
                .param("assetId", Long.toString(ASSET))).andReturn().getResponse();
        assertEquals(204, bound.getStatus());
        assertEquals(ASSET, repository.rows.get(id).assetId());

        var downloaded = mvc.perform(get("/da/managed-objects/" + id + "/content"))
                .andReturn().getResponse();
        assertEquals(200, downloaded.getStatus());
        assertArrayEquals(CSV, downloaded.getContentAsByteArray());
        assertEquals("application/octet-stream", downloaded.getContentType());
        assertEquals("private, no-store", downloaded.getHeader("Cache-Control"));
        assertEquals("nosniff", downloaded.getHeader("X-Content-Type-Options"));
        assertEquals("sandbox; default-src 'none'", downloaded.getHeader("Content-Security-Policy"));
        assertEquals("attachment; filename=\"object-" + id + ".bin\"",
                downloaded.getHeader("Content-Disposition"));
        assertFalse(downloaded.getHeader("Content-Disposition").contains("managed/"));

        permissions.export = false;
        int opened = store.opens;
        assertEquals(403, mvc.perform(get("/da/managed-objects/" + id + "/content"))
                .andReturn().getResponse().getStatus());
        assertEquals(opened, store.opens, "revocation must be checked before object-store I/O");
    }

    @Test void currentProjectAndPurposeGateEveryRouteAndPreviewIsUnavailable() throws Exception {
        assertEquals(403, mvc.perform(post("/da/managed-objects")
                .param("projectId", "45").param("purpose", "ASSET_FILE")
                .contentType("text/csv").content(CSV)).andReturn().getResponse().getStatus());
        UUID id = UUID.fromString(new ObjectMapper().readTree(mvc.perform(post("/da/managed-objects")
                .param("projectId", Long.toString(PROJECT)).param("purpose", "ASSET_FILE")
                .contentType("text/csv").content(CSV)).andReturn().getResponse()
                .getContentAsString()).path("fileId").asText());
        assertEquals(404, mvc.perform(get("/da/managed-objects/" + id + "/preview"))
                .andReturn().getResponse().getStatus());
        setUser(USER + 1);
        assertEquals(403, mvc.perform(post("/da/managed-objects/" + id + "/bind")
                .param("assetId", Long.toString(ASSET))).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/da/managed-objects/" + id + "/content"))
                .andReturn().getResponse().getStatus());
        assertEquals(0, store.opens);
    }

    @Test void ingressRejectsMultipartAndOversizedBodiesBeforeHandlerOrParsing() throws Exception {
        var filter = new ManagedObjectIngressFilter();
        var parsed = new AtomicBoolean();
        FilterChain chain = (request, response) -> parsed.set(true);
        MockHttpServletRequest multipart = new MockHttpServletRequest("POST", "/da/managed-objects") {
            @Override public java.util.Collection<jakarta.servlet.http.Part> getParts() {
                throw new AssertionError("multipart parser must not run");
            }
            @Override public ServletInputStream getInputStream() {
                throw new AssertionError("multipart body must not be read");
            }
        };
        multipart.setContentType("multipart/form-data; boundary=fixture");
        var multipartResponse = new MockHttpServletResponse();
        filter.doFilter(multipart, multipartResponse, chain);
        assertEquals(415, multipartResponse.getStatus());
        assertFalse(parsed.get());

        for (String nonCanonical : List.of("/da/%6danaged-objects", "/da/managed-objects;v=1",
                "/da%2fmanaged-objects", "/da//managed-objects")) {
            var encoded = new MockHttpServletRequest("POST", nonCanonical);
            encoded.setContentType("multipart/form-data; boundary=fixture");
            var encodedResponse = new MockHttpServletResponse();
            filter.doFilter(encoded, encodedResponse, chain);
            assertEquals(415, encodedResponse.getStatus(), nonCanonical);
            assertFalse(parsed.get(), nonCanonical);
        }

        var oversized = new MockHttpServletRequest("POST", "/da/managed-objects") {
            @Override public long getContentLengthLong() {
                return ManagedObjectFlow.MAX_BYTES + 1L;
            }
        };
        oversized.setContentType("text/csv");
        oversized.addHeader("Content-Length", ManagedObjectFlow.MAX_BYTES + 1);
        var oversizedResponse = new MockHttpServletResponse();
        filter.doFilter(oversized, oversizedResponse, chain);
        assertEquals(413, oversizedResponse.getStatus());
        assertFalse(parsed.get());

        var compressed = new MockHttpServletRequest("POST", "/da/managed-objects");
        compressed.setContentType("text/csv");
        compressed.addHeader("Content-Encoding", "gzip");
        var compressedResponse = new MockHttpServletResponse();
        filter.doFilter(compressed, compressedResponse, chain);
        assertEquals(415, compressedResponse.getStatus());
        assertFalse(parsed.get());

        var head = new MockHttpServletRequest("HEAD", "/da/managed-objects/" + UUID.randomUUID()
                + "/content");
        var headResponse = new MockHttpServletResponse();
        filter.doFilter(head, headResponse, chain);
        assertEquals(405, headResponse.getStatus());
        assertFalse(parsed.get());

        var bindBody = new MockHttpServletRequest("POST", "/da/managed-objects/"
                + UUID.randomUUID() + "/bind") {
            @Override public long getContentLengthLong() {
                return ManagedObjectFlow.MAX_BYTES + 1L;
            }
        };
        bindBody.setContentType("application/json");
        bindBody.addHeader("Content-Length", ManagedObjectFlow.MAX_BYTES + 1);
        var bindResponse = new MockHttpServletResponse();
        filter.doFilter(bindBody, bindResponse, chain);
        assertEquals(400, bindResponse.getStatus());
        assertFalse(parsed.get());

        var chunkedBind = new MockHttpServletRequest("POST", "/da/managed-objects/"
                + UUID.randomUUID() + "/bind");
        chunkedBind.addHeader("Transfer-Encoding", "chunked");
        var chunkedBindResponse = new MockHttpServletResponse();
        filter.doFilter(chunkedBind, chunkedBindResponse, chain);
        assertEquals(400, chunkedBindResponse.getStatus());
        assertFalse(parsed.get());
    }

    @Test void unknownLengthRawStreamStopsAtLimitPlusOneEvenWhenChunked() throws Exception {
        var input = new EndlessInput();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/da/managed-objects") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() { return input; }
        };
        request.setContentType("text/csv");
        request.addHeader("Transfer-Encoding", "chunked");
        ResponseStatusException oversized = assertThrows(ResponseStatusException.class,
                () -> controller.upload(PROJECT, ManagedObjectFlow.Purpose.ASSET_FILE, request));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, oversized.getStatusCode());
        assertEquals(ManagedObjectFlow.MAX_BYTES + 1, input.consumed);
        assertTrue(repository.rows.isEmpty());
        assertTrue(store.bytes.isEmpty());
    }

    private static void setUser(long id) {
        var login = new LoginUser();
        login.setUserId(id);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    private static final class MemoryRepository implements ManagedObjectFlow.Repository {
        private final Map<UUID, ManagedObjectFlow.Record> rows = new HashMap<>();
        @Override public void insert(ManagedObjectFlow.Record pending) {
            rows.put(pending.id(), pending);
        }
        @Override public Optional<ManagedObjectFlow.Record> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }
        @Override public boolean transition(UUID id, ManagedObjectFlow.Status expected,
                                            ManagedObjectFlow.Status next) {
            var old = rows.get(id);
            if (old == null || old.status() != expected) return false;
            rows.put(id, new ManagedObjectFlow.Record(id, old.projectId(), old.uploaderId(),
                    old.purpose(), old.objectKey(), old.mediaType(), old.length(), old.sha256(),
                    next, old.assetId()));
            return true;
        }
        @Override public boolean bind(UUID id, long userId, long assetId) {
            var old = rows.get(id);
            if (old == null || old.status() != ManagedObjectFlow.Status.READY
                    || old.assetId() != null || old.uploaderId() != userId) return false;
            rows.put(id, new ManagedObjectFlow.Record(id, old.projectId(), old.uploaderId(),
                    old.purpose(), old.objectKey(), old.mediaType(), old.length(), old.sha256(),
                    old.status(), assetId));
            return true;
        }
    }

    private static final class MemoryStore implements ManagedObjectFlow.Store {
        private final Map<String, byte[]> bytes = new HashMap<>();
        private int opens;
        @Override public void put(String key, byte[] value, String mediaType) {
            bytes.put(key, value.clone());
        }
        @Override public InputStream open(String key) throws IOException {
            opens++;
            byte[] value = bytes.get(key);
            if (value == null) throw new IOException("missing test object");
            return new ByteArrayInputStream(value);
        }
    }

    private static final class Permissions implements ManagedObjectFlow.Authorizer {
        private boolean export = true;
        @Override public boolean canUpload(long userId, long projectId,
                                            ManagedObjectFlow.Purpose purpose) {
            return userId == USER && projectId == PROJECT && purpose == ManagedObjectFlow.Purpose.ASSET_FILE;
        }
        @Override public Long projectOfAsset(long assetId, ManagedObjectFlow.Purpose purpose) {
            return assetId == ASSET && purpose == ManagedObjectFlow.Purpose.ASSET_FILE
                    ? PROJECT : null;
        }
        @Override public boolean canBind(long userId, long projectId, long assetId,
                                          ManagedObjectFlow.Purpose purpose) {
            return userId == USER && projectId == PROJECT
                    && assetId == ASSET && purpose == ManagedObjectFlow.Purpose.ASSET_FILE;
        }
        @Override public boolean canReadOwn(long userId, long projectId,
                ManagedObjectFlow.Purpose purpose, ManagedObjectFlow.ReadMode mode) {
            return canUpload(userId, projectId, purpose)
                    && (mode != ManagedObjectFlow.ReadMode.ORIGINAL || export);
        }
        @Override public boolean canReadAsset(long userId, long projectId, long assetId,
                ManagedObjectFlow.Purpose purpose, ManagedObjectFlow.ReadMode mode) {
            return canBind(userId, projectId, assetId, purpose)
                    && (mode != ManagedObjectFlow.ReadMode.ORIGINAL || export);
        }
    }

    private static final class EndlessInput extends ServletInputStream {
        private long consumed;
        @Override public boolean isFinished() { return false; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
        @Override public int read() { consumed++; return 'x'; }
        @Override public int read(byte[] target, int offset, int length) {
            Arrays.fill(target, offset, offset + length, (byte) 'x');
            consumed += length;
            return length;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityHarness { }
}
