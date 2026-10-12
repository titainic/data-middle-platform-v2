package tech.qiantong.qdata.security.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.security.handle.AuthenticationEntryPointImpl;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class HtmlAuthenticationBoundaryTest {
    @RestController static class PrivateApi {
        final AtomicInteger calls=new AtomicInteger();
        @GetMapping("/private/data") String data() { calls.incrementAndGet();return "private-test-data"; }
    }
    private MockMvc mvc(PrivateApi api) {
        var entry=new AuthenticationEntryPointImpl();ReflectionTestUtils.setField(entry,"spaNavigation",new SpaRequestMatcher());
        var chain=new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE,
                new AnonymousAuthenticationFilter("unit-test"),new ExceptionTranslationFilter(entry),
                new AuthorizationFilter(AuthenticatedAuthorizationManager.authenticated()));
        return MockMvcBuilders.standaloneSetup(api).addFilters(new FilterChainProxy(chain)).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void spoofingHtmlAcceptNeverInvokesTheProtectedApi() throws Exception {
        var api=new PrivateApi();var result=mvc(api).perform(get("/private/data").accept("text/html")).andReturn();
        assertEquals("/index",result.getResponse().getForwardedUrl());assertEquals(0,api.calls.get());
        assertFalse(result.getResponse().getContentAsString().contains("private-test-data"));
    }
    @Test void jsonClientsReceiveAuthenticationFailureWithoutData() throws Exception {
        var api=new PrivateApi();
        try(var messages=mockStatic(MessageUtils.class)) {
            var result=mvc(api).perform(get("/private/data").accept("application/json")).andReturn();
            assertEquals(0,api.calls.get());assertNull(result.getResponse().getForwardedUrl());
            assertTrue(result.getResponse().getContentAsString().contains("\"code\":401"));
        }
    }
}
