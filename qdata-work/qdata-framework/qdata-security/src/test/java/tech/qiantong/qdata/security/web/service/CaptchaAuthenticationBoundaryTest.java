package tech.qiantong.qdata.security.web.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.constant.CacheConstants;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.core.redis.RedisCache;
import tech.qiantong.qdata.common.exception.user.CaptchaException;
import tech.qiantong.qdata.common.exception.user.CaptchaExpireException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.module.system.service.ISysConfigService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CaptchaAuthenticationBoundaryTest {
    private SysLoginService service;
    private ISysConfigService configuration;
    private RedisCache cache;
    private AuthenticationManager authentication;
    private TokenService tokens;
    private LoginUser principal;
    private final String captchaKey = CacheConstants.CAPTCHA_CODE_KEY + "captcha-test-id";

    @BeforeEach
    void setUp() {
        service = spy(new SysLoginService());
        configuration = mock(ISysConfigService.class);
        cache = mock(RedisCache.class);
        authentication = mock(AuthenticationManager.class);
        tokens = mock(TokenService.class);
        ReflectionTestUtils.setField(service, "configService", configuration);
        ReflectionTestUtils.setField(service, "redisCache", cache);
        ReflectionTestUtils.setField(service, "authenticationManager", authentication);
        ReflectionTestUtils.setField(service, "tokenService", tokens);
        doNothing().when(service).loginPreCheck(anyString(), anyString());
        doNothing().when(service).recordLoginInfo(anyLong());
        doNothing().when(service).recordLoginEvent(any(), any(), any());
        SysUser user = new SysUser();
        user.setUserId(42L);
        principal = new LoginUser(42L, 1L, user, Set.of());
        when(authentication.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(tokens.createToken(principal)).thenReturn("captcha-unit-token");
        when(configuration.selectCaptchaEnabled()).thenReturn(true);
    }

    private MockedStatic<MessageUtils> messages() {
        return mockStatic(MessageUtils.class);
    }

    @Test
    void validCaptchaAllowsOnlyProviderVerifiedPrincipalToReceiveToken() {
        when(cache.consumeCaptcha(captchaKey)).thenReturn("AbC42");
        try (var ignored = messages()) {
            assertEquals("captcha-unit-token", service.login("unit-user", "unit-password", "abc42", "captcha-test-id").get("token"));
        }
        var order = inOrder(cache, authentication, tokens);
        order.verify(cache).consumeCaptcha(captchaKey);
        order.verify(authentication).authenticate(any());
        order.verify(tokens).createToken(principal);
    }

    @Test
    void wrongCaptchaConsumesChallengeAndNeverAuthenticatesOrIssuesToken() {
        when(cache.consumeCaptcha(captchaKey)).thenReturn("correct");
        try (var ignored = messages()) {
            assertThrows(CaptchaException.class,
                    () -> service.login("unit-user", "unit-password", "wrong", "captcha-test-id"));
        }
        verify(cache).consumeCaptcha(captchaKey);
        verifyNoInteractions(authentication, tokens);
        verify(service, never()).recordLoginInfo(anyLong());
    }

    @Test
    void expiredChallengeNeverAuthenticatesOrIssuesToken() {
        when(cache.consumeCaptcha(captchaKey)).thenReturn(null);
        try (var ignored = messages()) {
            assertThrows(CaptchaExpireException.class,
                    () -> service.login("unit-user", "unit-password", "old-code", "captcha-test-id"));
        }
        verifyNoInteractions(authentication, tokens);
        verify(service, never()).recordLoginInfo(anyLong());
    }

    @Test
    void missingCaptchaAnswerRejectsBeforePasswordAuthentication() {
        when(cache.consumeCaptcha(captchaKey)).thenReturn("answer");
        try (var ignored = messages()) {
            assertThrows(CaptchaException.class,
                    () -> service.login("unit-user", "unit-password", null, "captcha-test-id"));
        }
        verifyNoInteractions(authentication, tokens);
    }

    @Test
    void consumedChallengeCannotBeReplayedToCreateASecondToken() {
        AtomicReference<String> challenge = new AtomicReference<>("answer");
        when(cache.consumeCaptcha(captchaKey)).thenAnswer(call -> challenge.getAndSet(null));
        try (var ignored = messages()) {
            assertEquals("captcha-unit-token", service.login("unit-user", "unit-password", "answer", "captcha-test-id").get("token"));
            assertThrows(CaptchaExpireException.class,
                    () -> service.login("unit-user", "unit-password", "answer", "captcha-test-id"));
        }
        verify(authentication, times(1)).authenticate(any());
        verify(tokens, times(1)).createToken(principal);
    }

    @Test
    void disabledCaptchaPreservesPasswordAuthenticationWithoutAccessingRedis() {
        when(configuration.selectCaptchaEnabled()).thenReturn(false);
        try (var ignored = messages()) {
            assertEquals("captcha-unit-token", service.login("unit-user", "unit-password", null, null).get("token"));
        }
        verifyNoInteractions(cache);
        verify(authentication).authenticate(any());
        verify(tokens).createToken(principal);
    }
}
