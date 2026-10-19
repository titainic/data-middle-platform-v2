package tech.qiantong.qdata.acceptance;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.*;
import tech.qiantong.qdata.common.config.AniviaConfig;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.config.interceptor.UploadedFileInterceptor;
import tech.qiantong.qdata.config.web.UploadedFileController;
import tech.qiantong.qdata.module.da.service.asset.*;
import tech.qiantong.qdata.security.config.SecurityConfig;
import tech.qiantong.qdata.security.config.SpaRequestMatcher;
import tech.qiantong.qdata.security.config.properties.PermitAllUrlProperties;
import tech.qiantong.qdata.security.filter.JwtAuthenticationTokenFilter;
import tech.qiantong.qdata.security.handle.*;
import tech.qiantong.qdata.security.web.service.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real SecurityConfig, JWT filter, file authorizer, interceptor and controller.
 * Only token lookup and database/project collaborators are fixtures; no remote services. */
class AvatarReadSecurityTest {
    private static final String AVATAR = "/profile/avatar/2026/10/19/current.png";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1sAAAAASUVORK5CYII=");
    @TempDir Path root;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private String previousProfile;
    private JdbcTemplate jdbc;

    @BeforeEach void start() throws Exception {
        previousProfile = AniviaConfig.getProfile();
        new AniviaConfig().setProfile(root.toString());
        Files.createDirectories(root.resolve("avatar/2026/10/19"));
        Files.createDirectories(root.resolve("etl"));
        Files.createDirectories(root.resolve("export"));
        Files.write(root.resolve(AVATAR.substring("/profile/".length())), PNG);
        Files.write(root.resolve("avatar/unreferenced.png"), PNG);
        Files.writeString(root.resolve("avatar/active.html"), "private-active-content");
        Files.writeString(root.resolve("etl/private.png"), "private-etl");
        Files.writeString(root.resolve("export/private.csv"), "private-export");
        // Even a misleading avatar URL must be checked by its real filesystem identity.
        Files.createSymbolicLink(root.resolve("avatar/alias.png"), root.resolve("etl/private.png"));

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // Infrastructure mocks are existing singletons: do not autowire their production fields.
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("tokenService", mock(TokenService.class));
            factory.registerSingleton("logoutSuccessHandler", mock(LogoutSuccessHandlerImpl.class));
            factory.registerSingleton("userDetailsService", mock(UserDetailsService.class));
            factory.registerSingleton("jdbcTemplate", mock(JdbcTemplate.class));
            factory.registerSingleton("permissions", mock(ProjectPermissionService.class));
        });
        context.register(Fixture.class);
        context.refresh();
        ReflectionTestUtils.setField(context.getBean(UploadedFileAccessService.class), "profile", root.toString());
        jdbc = context.getBean(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(AVATAR))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach void stop() {
        if (context != null) context.close();
        new AniviaConfig().setProfile(previousProfile);
    }

    @Test void anonymousImgGetsCurrentAvatarWithoutTokenOrCookie() throws Exception {
        mvc.perform(get(AVATAR).header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG)).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        verify(jdbc).queryForObject(contains("FROM system_user"), eq(Long.class), eq(AVATAR));
        verifyNoInteractions(context.getBean(ProjectPermissionService.class));
    }

    @Test void cookieDoesNotChangePublicAvatarRead() throws Exception {
        mvc.perform(get(AVATAR).cookie(new jakarta.servlet.http.Cookie("Admin-Token", "fixture")))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));
    }

    @Test void onlyGetIsPublicAndUploadRemainsAuthenticated() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT,
                HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, AVATAR)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/system/user/profile/avatar")).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(jdbc);
    }

    @Test void otherUploadedFilesRemain401EvenForHtmlNavigation() throws Exception {
        for (String path : List.of("/profile/etl/private.png", "/profile/export/private.csv",
                "/profile/private.png", "/profile/avatar-other/private.png")) {
            for (String accept : List.of("image/*", "text/html", "application/json")) {
                mvc.perform(get(path).header("Accept", accept)).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value(401)).andExpect(forwardedUrl(null));
            }
        }
        verifyNoInteractions(jdbc);
    }

    @Test void publicPrefixDoesNotExposeUnreferencedImagesOrActiveContent() throws Exception {
        for (String path : List.of("/profile/avatar/unreferenced.png", "/profile/avatar/active.html",
                "/profile/avatar/alias.png")) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    @Test void missingAvatarReturns404AndTraversalCannotReadPrivateFile() throws Exception {
        mvc.perform(get("/profile/avatar/missing.png")).andExpect(status().isNotFound());
        mvc.perform(get(URI.create("/profile/avatar/%2e%2e/etl/private.png")))
                .andExpect(status().is4xxClientError());
    }

    @Test void authenticatedUsersStillNeedOwnershipForOtherUploadedFiles() throws Exception {
        SysUser user = new SysUser(); user.setUserId(42L); user.setUserName("fixture");
        LoginUser login = new LoginUser(42L, 1L, user, Set.of());
        when(context.getBean(TokenService.class).getLoginUser(any())).thenReturn(login);
        mvc.perform(get("/profile/etl/private.png")).andExpect(status().isForbidden());
        mvc.perform(get("/profile/export/private.csv")).andExpect(status().isForbidden());
        mvc.perform(get(AVATAR)).andExpect(status().isOk()).andExpect(content().bytes(PNG));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, SpaRequestMatcher.class, AuthenticationEntryPointImpl.class,
            JwtAuthenticationTokenFilter.class, UploadedFileInterceptor.class, UploadedFileController.class})
    static class Fixture {
        @Bean WebMvcConfigurer uploadConfigurer(UploadedFileInterceptor interceptor) {
            return new WebMvcConfigurer() {
                @Override public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor).addPathPatterns("/profile/**");
                }
            };
        }
        @Bean PermitAllUrlProperties permitAllUrl() { return new PermitAllUrlProperties(); }
        @Bean CorsFilter corsFilter() { return new CorsFilter(new UrlBasedCorsConfigurationSource()); }
        @Bean UploadedFileAccessService fileAccess(JdbcTemplate jdbc, ProjectPermissionService permissions) {
            return new UploadedFileAccessService(jdbc, mock(AssetProjectReadAccess.class), permissions);
        }
    }
}
