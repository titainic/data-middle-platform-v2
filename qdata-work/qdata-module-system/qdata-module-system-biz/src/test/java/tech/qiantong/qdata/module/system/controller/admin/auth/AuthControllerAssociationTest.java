package tech.qiantong.qdata.module.system.controller.admin.auth;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.module.system.dal.dataobject.auth.RelUserAuthProductDO;
import tech.qiantong.qdata.module.system.enums.auth.AuthProductEnums;
import tech.qiantong.qdata.module.system.service.ISysUserService;
import tech.qiantong.qdata.module.system.service.auth.IRelUserAuthProductService;
import tech.qiantong.qdata.redis.service.IRedisService;
import tech.qiantong.qdata.security.web.service.SysPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;

import java.net.URLDecoder;
import java.util.Set;
import java.util.stream.Collectors;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerAssociationTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void existingIdentityAssociationResolvesByExternalAuthId() {
        var controller = new AuthController();
        var associations = mock(IRelUserAuthProductService.class);
        var users = mock(ISysUserService.class);
        var query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        var association = RelUserAuthProductDO.builder()
                .userId(42L)
                .authId("7001")
                .authProductType(AuthProductEnums.ANIVIA.code)
                .build();
        var user = new SysUser();
        when(associations.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.one()).thenReturn(association);
        when(users.selectUserById(42L)).thenReturn(user);
        ReflectionTestUtils.setField(controller, "userAuthProductService", associations);
        ReflectionTestUtils.setField(controller, "userService", users);

        assertSame(user, ReflectionTestUtils.invokeMethod(controller, "getUserByIdHubId", 7001L));

        var columns = org.mockito.ArgumentCaptor.forClass(SFunction.class);
        var values = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(query, times(2)).eq(columns.capture(), values.capture());
        assertEquals("7001", values.getAllValues().get(0));
        assertEquals("7001", columns.getAllValues().get(0).apply(association));
        assertEquals(AuthProductEnums.ANIVIA.code, values.getAllValues().get(1));
        assertEquals(AuthProductEnums.ANIVIA.code, columns.getAllValues().get(1).apply(association));
        verify(users).selectUserById(42L);
    }

    @Test
    void anonymousLogoutNeverReadsAnotherUsersStoredToken() {
        SecurityContextHolder.clearContext();
        var controller = new AuthController();
        var redis = mock(IRedisService.class);
        ReflectionTestUtils.setField(controller, "redisService", redis);
        try {
            assertThrows(ServiceException.class, controller::loginOut);
            verifyNoInteractions(redis);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void logoutReadsOnlyTheAuthenticatedUsersStoredToken() {
        var controller = new AuthController();
        var redis = mock(IRedisService.class);
        ReflectionTestUtils.setField(controller, "redisService", redis);
        ReflectionTestUtils.setField(controller, "redisPrefix", "test");
        var stoppedBeforeExternalRequest = new IllegalStateException("expected lookup");
        when(redis.get("test:accessToken:42")).thenThrow(stoppedBeforeExternalRequest);
        var loginUser = new LoginUser(42L, 1L, new SysUser(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Set.of()));
        try {
            assertSame(stoppedBeforeExternalRequest,
                    assertThrows(IllegalStateException.class, controller::loginOut));
            verify(redis).get("test:accessToken:42");
            verify(redis, never()).get("test:accessToken:43");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void springOnlyExposesTheCodeExchangeAndAuthenticatedLogoutRoutes() {
        try (var context = new GenericWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.registerBean(AuthController.class);
            context.refresh();
            var mappings = new RequestMappingHandlerMapping();
            mappings.setApplicationContext(context);
            mappings.afterPropertiesSet();
            var routes = mappings.getHandlerMethods().keySet().stream()
                    .flatMap(mapping -> mapping.getPatternValues().stream())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("/oauth2/codeLogin", "/oauth2/sso/logout"), routes);
        }
    }

    @Test
    void refreshedProviderTokensRemainBoundToTheAuthenticatedLocalUser() throws Exception {
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/oauth2/refresh", exchange -> {
            byte[] response = ("{\"code\":200,\"data\":{\"access_token\":\"new-access\","
                    + "\"refresh_token\":\"new-refresh\",\"expires_in\":120,"
                    + "\"refresh_expires_in\":240}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        provider.start();
        try {
            var controller = new AuthController();
            var redis = mock(IRedisService.class);
            ReflectionTestUtils.setField(controller, "redisService", redis);
            ReflectionTestUtils.setField(controller, "redisPrefix", "test");
            ReflectionTestUtils.setField(controller, "serverUrl",
                    "http://127.0.0.1:" + provider.getAddress().getPort());
            ReflectionTestUtils.setField(controller, "clientId", "test-client");
            ReflectionTestUtils.setField(controller, "clientSecret", "test-secret");

            assertEquals("new-access", ReflectionTestUtils.invokeMethod(controller,
                    "refresh", "old-refresh", 42L));
            verify(redis).set("test:accessToken:42", "new-access", 120L);
            verify(redis).set("test:refreshToken:42", "new-refresh", 240L);
        } finally {
            provider.stop(0);
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void codeExchangePreservesFormCharactersAndNeverSignsDisabledOrDeletedUsers() throws Exception {
        var postedForm = new AtomicReference<String>();
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/oauth2/token", exchange -> {
            postedForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"code\":200,\"data\":{\"idHubId\":7001,"
                    + "\"access_token\":\"access\",\"refresh_token\":\"refresh\","
                    + "\"expires_in\":120,\"refresh_expires_in\":240}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        provider.start();
        try (var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.message("user.password.delete")).thenReturn("deleted");
            messages.when(() -> MessageUtils.message("user.blocked")).thenReturn("disabled");
            for (String status : new String[] {"0", "1", "2"}) {
                var controller = new AuthController();
                var associations = mock(IRelUserAuthProductService.class);
                var users = mock(ISysUserService.class);
                var query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
                var redis = mock(IRedisService.class);
                var permissions = mock(SysPermissionService.class);
                var tokens = mock(TokenService.class);
                var user = new SysUser();
                user.setUserId(42L);
                user.setUserName("test-user");
                user.setStatus("2".equals(status) ? "0" : status);
                user.setDelFlag("2".equals(status) ? "2" : "0");
                when(associations.lambdaQuery()).thenReturn(query);
                when(query.eq(any(SFunction.class), any())).thenReturn(query);
                when(query.one()).thenReturn(RelUserAuthProductDO.builder()
                        .userId(42L).authId("7001").authProductType(AuthProductEnums.ANIVIA.code).build());
                when(users.selectUserById(42L)).thenReturn(user);
                when(permissions.getMenuPermission(user)).thenReturn(Set.of());
                when(tokens.createToken(any(LoginUser.class))).thenReturn("session");
                ReflectionTestUtils.setField(controller, "userAuthProductService", associations);
                ReflectionTestUtils.setField(controller, "userService", users);
                ReflectionTestUtils.setField(controller, "redisService", redis);
                ReflectionTestUtils.setField(controller, "permissionService", permissions);
                ReflectionTestUtils.setField(controller, "tokenService", tokens);
                ReflectionTestUtils.setField(controller, "redisPrefix", "test");
                ReflectionTestUtils.setField(controller, "serverUrl",
                        "http://127.0.0.1:" + provider.getAddress().getPort());
                ReflectionTestUtils.setField(controller, "clientId", "test-client");
                ReflectionTestUtils.setField(controller, "clientSecret", "test-secret");

                if ("0".equals(status)) {
                    assertEquals("session", controller.codeLogin("part+one&part=two").get("msg"));
                    verify(tokens).createToken(any(LoginUser.class));
                } else {
                    assertThrows(ServiceException.class,
                            () -> controller.codeLogin("part+one&part=two"));
                    verifyNoInteractions(redis);
                    verifyNoInteractions(tokens);
                }
            }
            Map<String, String> form = Arrays.stream(postedForm.get().split("&"))
                    .map(part -> part.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(part -> part[0], part -> part[1]));
            assertEquals("part+one&part=two",
                    URLDecoder.decode(form.get("code"), StandardCharsets.UTF_8));
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void logoutWithoutAnyProviderTokenFailsLocallyWithoutCallingProvider() {
        var controller = new AuthController();
        var redis = mock(IRedisService.class);
        ReflectionTestUtils.setField(controller, "redisService", redis);
        ReflectionTestUtils.setField(controller, "redisPrefix", "test");
        var loginUser = new LoginUser(42L, 1L, new SysUser(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Set.of()));
        try {
            assertEquals(500, controller.loginOut().get("code"));
            verify(redis).get("test:accessToken:42");
            verify(redis).get("test:refreshToken:42");
            verify(redis, never()).delete(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void providerLogoutFailureIsNotWrappedAsSuccessOrReturnedRaw() throws Exception {
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/oauth2/logout", exchange -> {
            byte[] response = "{\"code\":500,\"msg\":\"provider-detail\",\"data\":{\"access_token\":\"sensitive\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        provider.start();
        var controller = new AuthController();
        var redis = mock(IRedisService.class);
        ReflectionTestUtils.setField(controller, "redisService", redis);
        ReflectionTestUtils.setField(controller, "redisPrefix", "test");
        ReflectionTestUtils.setField(controller, "serverUrl",
                "http://127.0.0.1:" + provider.getAddress().getPort());
        when(redis.get("test:accessToken:42")).thenReturn("access");
        var loginUser = new LoginUser(42L, 1L, new SysUser(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Set.of()));
        try {
            var result = controller.loginOut();
            assertEquals(500, result.get("code"));
            assertEquals("system.error.auth.logoutFailed", result.get("msg"));
            verify(redis, never()).delete(any());
        } finally {
            SecurityContextHolder.clearContext();
            provider.stop(0);
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void associationWhoseLocalUserIsMissingNeverFallsBackToPhoneBinding() {
        var controller = new AuthController();
        var associations = mock(IRelUserAuthProductService.class);
        var users = mock(ISysUserService.class);
        var query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(associations.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.one()).thenReturn(RelUserAuthProductDO.builder()
                .userId(42L).authId("7001").authProductType(AuthProductEnums.ANIVIA.code).build());
        ReflectionTestUtils.setField(controller, "userAuthProductService", associations);
        ReflectionTestUtils.setField(controller, "userService", users);
        try (var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.message("user.not.exists")).thenReturn("missing");
            assertThrows(ServiceException.class,
                    () -> ReflectionTestUtils.invokeMethod(controller, "getUserByIdHubId", 7001L));
        }
        verify(users).selectUserById(42L);
        verify(users, never()).findUserByNameOrPhone(any());
    }

    @Test
    void successfulProviderLogoutClearsOnlyTheAuthenticatedUsersThirdPartyTokens() throws Exception {
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/oauth2/logout", exchange -> {
            byte[] response = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        provider.start();
        var controller = new AuthController();
        var redis = mock(IRedisService.class);
        ReflectionTestUtils.setField(controller, "redisService", redis);
        ReflectionTestUtils.setField(controller, "redisPrefix", "test");
        ReflectionTestUtils.setField(controller, "serverUrl",
                "http://127.0.0.1:" + provider.getAddress().getPort());
        when(redis.get("test:accessToken:42")).thenReturn("access");
        var loginUser = new LoginUser(42L, 1L, new SysUser(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Set.of()));
        try {
            assertEquals(200, controller.loginOut().get("code"));
            verify(redis).delete("test:accessToken:42");
            verify(redis).delete("test:refreshToken:42");
            verify(redis, never()).delete("test:accessToken:43");
        } finally {
            SecurityContextHolder.clearContext();
            provider.stop(0);
        }
    }
}
