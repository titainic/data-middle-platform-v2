package tech.qiantong.qdata.ai.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tech.qiantong.qdata.config.ApplicationConfig;
import tech.qiantong.qdata.config.ThreadPoolConfig;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.system.service.impl.SysLogininforServiceImpl;
import tech.qiantong.qdata.security.handle.LogoutSuccessHandlerImpl;
import tech.qiantong.qdata.security.web.service.PermissionService;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;

import javax.sql.DataSource;
import java.util.List;

@Configuration
@Import({ApplicationConfig.class, ThreadPoolConfig.class, TokenService.class,
        LogoutSuccessHandlerImpl.class, PermissionService.class, ProjectPermissionService.class,
        ProjectDataResourceAccess.class, SysLogininforServiceImpl.class})
public class StandaloneAiConfiguration {
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }

    @Bean("transactionManager")
    @Primary
    public DataSourceTransactionManager transactionManager(DataSource datasource) {
        return new DataSourceTransactionManager(datasource);
    }
}
