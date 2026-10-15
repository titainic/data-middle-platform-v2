package tech.qiantong.qdata.security.web.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import org.springframework.security.authentication.BadCredentialsException;
import tech.qiantong.qdata.common.exception.user.UserPasswordNotMatchException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.module.system.service.ISysConfigService;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CaptchaAuthenticationBoundaryTest {
    private SysLoginService service;
    private ISysConfigService configuration;
    private AuthenticationManager authentication;
    private TokenService tokens;
    private LoginUser principal;

    @BeforeEach
    void setUp() {
        service = spy(new SysLoginService());
        configuration = mock(ISysConfigService.class);
        authentication = mock(AuthenticationManager.class);
        tokens = mock(TokenService.class);
        ReflectionTestUtils.setField(service, "configService", configuration);
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
    }

    private MockedStatic<MessageUtils> messages() {
        return mockStatic(MessageUtils.class);
    }

    @Test
    void missingCaptchaAuthenticatesPasswordWithoutCheckingCaptchaConfiguration() {
        when(configuration.selectCaptchaEnabled()).thenReturn(true);
        try (var ignored = messages()) {
            assertEquals("captcha-unit-token", service.login("unit-user", "unit-password", null, null).get("token"));
        }
        verify(configuration, never()).selectCaptchaEnabled();
        var order = inOrder(authentication, tokens);
        order.verify(authentication).authenticate(any());
        order.verify(tokens).createToken(principal);
    }

    @Test
    void legacyCaptchaFieldsDoNotBlockPasswordAuthentication() {
        try (var ignored = messages()) {
            assertEquals("captcha-unit-token", service.login("unit-user", "unit-password", "stale-code", "expired-id").get("token"));
        }
        verifyNoInteractions(configuration);
        verify(authentication).authenticate(any());
        verify(tokens).createToken(principal);
    }

    @Test
    void wrongPasswordWithoutCaptchaNeverIssuesToken() {
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("Rejected"));
        try (var ignored = messages()) {
            assertThrows(UserPasswordNotMatchException.class,
                    () -> service.login("unit-user", "wrong-password", null, null));
        }
        verify(authentication).authenticate(any());
        verifyNoInteractions(tokens);
        verify(service, never()).recordLoginInfo(anyLong());
    }
}
