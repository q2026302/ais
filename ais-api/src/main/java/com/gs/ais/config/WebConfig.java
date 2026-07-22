package com.gs.ais.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SecurityProperties securityProperties;
    private final StoragePaths storagePaths;

    public WebConfig(SecurityProperties securityProperties, StoragePaths storagePaths) {
        this.securityProperties = securityProperties;
        this.storagePaths = storagePaths;
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

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl imageCache = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic();
        registry.addResourceHandler("/api/images/**")
                .addResourceLocations("file:" + storagePaths.uploadDir().toString() + "/")
                .setCacheControl(imageCache);
        registry.addResourceHandler("/api/attachments/**")
                .addResourceLocations("file:" + storagePaths.attachmentDir().toString() + "/")
                .setCacheControl(imageCache);
    }
}
