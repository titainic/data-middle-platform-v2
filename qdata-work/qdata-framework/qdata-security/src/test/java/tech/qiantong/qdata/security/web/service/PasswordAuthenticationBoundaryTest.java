package tech.qiantong.qdata.security.web.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.exception.user.UserPasswordNotMatchException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordAuthenticationBoundaryTest {
    private SysLoginService service(AuthenticationManager manager, TokenService tokens) {
        var service=spy(new SysLoginService());
        ReflectionTestUtils.setField(service,"authenticationManager",manager);
        ReflectionTestUtils.setField(service,"tokenService",tokens);
        doNothing().when(service).loginPreCheck(anyString(),anyString());
        doNothing().when(service).recordLoginInfo(anyLong());
        doNothing().when(service).recordLoginEvent(any(),any(),any());
        return service;
    }
    @Test void onlyProviderVerifiedPrincipalCanReceiveAToken() {
        var manager=mock(AuthenticationManager.class);var tokens=mock(TokenService.class);
        var service=service(manager,tokens);var user=new SysUser();user.setUserId(2L);
        var principal=new LoginUser(2L,1L,user,Set.of());
        when(manager.authenticate(any())).thenAnswer(call->{var input=(UsernamePasswordAuthenticationToken)call.getArgument(0);assertEquals("unit-user",input.getPrincipal());assertEquals("unit-password",input.getCredentials());return new UsernamePasswordAuthenticationToken(principal,null,List.of());});
        when(tokens.createToken(principal)).thenReturn("unit-test-token");
        try(var messages=mockStatic(MessageUtils.class)) {
            assertEquals("unit-test-token",service.login("unit-user","unit-password",null,null).get("token"));
        }
        verify(manager).authenticate(any());verify(tokens).createToken(principal);
    }
    @Test void invalidPasswordCannotGenerateAToken() {
        var manager=mock(AuthenticationManager.class);var tokens=mock(TokenService.class);
        var service=service(manager,tokens);when(manager.authenticate(any())).thenThrow(new BadCredentialsException("Rejected"));
        try(var messages=mockStatic(MessageUtils.class)) {
            assertThrows(UserPasswordNotMatchException.class,()->service.login("unit-user","invalid-password",null,null));
        }
        verify(manager).authenticate(any());verifyNoInteractions(tokens);
    }
}
