package com.gs.ais.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@RestController
public class VersionController {

    private static final DateTimeFormatter BUILD_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final String version;
    private final String commit;
    private final String buildTime;

    public VersionController(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        if (buildProperties != null) {
            this.version = firstNonBlank(buildProperties.getVersion(), "unknown");
            this.commit = firstNonBlank(buildProperties.get("git.commit"), "unknown");
            Instant time = buildProperties.getTime();
            this.buildTime = time != null ? BUILD_TIME_FORMAT.format(time) : "unknown";
            return;
        }

        // Fallback for legacy version.properties (if present on classpath).
        String fallbackVersion = "unknown";
        String fallbackCommit = "unknown";
        String fallbackBuildTime = "unknown";
        try {
            ClassPathResource resource = new ClassPathResource("version.properties");
            if (resource.exists()) {
                Properties props = new Properties();
                try (InputStream in = resource.getInputStream()) {
                    props.load(in);
                }
                fallbackVersion = firstNonBlank(props.getProperty("app.version"), "unknown");
                fallbackCommit = firstNonBlank(props.getProperty("app.commit"), "unknown");
                fallbackBuildTime = firstNonBlank(props.getProperty("app.build.time"), "unknown");
            }
        } catch (Exception ignored) {
            // keep unknowns
        }
        this.version = fallbackVersion;
        this.commit = fallbackCommit;
        this.buildTime = fallbackBuildTime;
    }

    @GetMapping("/api/version")
    public Map<String, String> getVersion() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("version", version);
        info.put("commit", commit);
        info.put("buildTime", buildTime);
        return info;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
