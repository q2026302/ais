package com.gs.ais.controller;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@RestController
public class VersionController implements InitializingBean {

    private String version = "unknown";
    private String commit = "unknown";
    private String buildTime = "unknown";

    @GetMapping("/api/version")
    public Map<String, String> getVersion() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("version", version);
        info.put("commit", commit);
        info.put("buildTime", buildTime);
        return info;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        try {
            Properties props = new Properties();
            props.load(new ClassPathResource("version.properties").getInputStream());
            this.version = props.getProperty("app.version", "unknown");
            this.commit = props.getProperty("app.commit", "unknown");
            this.buildTime = props.getProperty("app.build.time", "unknown");
        } catch (Exception ignored) {
        }
    }
}
