package com.gs.ais.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class WebConfig {

    private final SecurityProperties securityProperties;

    public WebConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = securityProperties.getCorsAllowedOrigins();
        boolean allowAll = origins == null || origins.isEmpty()
                || origins.stream().anyMatch(origin -> "*".equals(origin));
        if (allowAll) {
            config.addAllowedOriginPattern("*");
            // Browsers reject credentials with wildcard origins.
            config.setAllowCredentials(false);
        } else {
            for (String origin : origins) {
                if (StringUtils.hasText(origin)) {
                    config.addAllowedOriginPattern(origin.trim());
                }
            }
            config.setAllowCredentials(true);
        }
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.addExposedHeader("Content-Disposition");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
