package com.gs.ais.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Centralizes all filesystem locations used by the application.
 *
 * Relative configured paths are resolved below {@code app.base-dir}; absolute
 * paths are preserved. This makes the native executable relocatable while still
 * allowing operators to override individual directories.
 */
@Component
public class StoragePaths {

    private static final Logger log = LoggerFactory.getLogger(StoragePaths.class);

    private final Path baseDir;
    private final Path configDir;
    private final Path dataDir;
    private final Path logDir;
    private final Path uploadDir;
    private final Path attachmentDir;

    public StoragePaths(Environment environment) {
        String baseDir = environment.getProperty("app.base-dir", ".");
        String configDir = environment.getProperty("app.config-dir", "");
        String dataDir = environment.getProperty("app.data-dir", "");
        String logDir = environment.getProperty("app.log-dir", "");
        String uploadDir = environment.getProperty("app.upload-dir", "");
        this.baseDir = absolute(Path.of(valueOrDefault(baseDir, ".")));
        this.configDir = resolve(this.baseDir, configDir, this.baseDir.resolve("config"));
        this.dataDir = resolve(this.baseDir, dataDir, this.baseDir.resolve("data"));
        this.logDir = resolve(this.baseDir, logDir, this.baseDir.resolve("logs"));
        // Keep existing development installations using the historical uploads/
        // directory working. Fresh installations default to data/uploads.
        Path legacyUploadDir = this.baseDir.resolve("uploads");
        Path defaultUploadDir = Files.isDirectory(legacyUploadDir)
                ? legacyUploadDir
                : this.dataDir.resolve("uploads");
        this.uploadDir = resolve(this.baseDir, uploadDir, defaultUploadDir);
        this.attachmentDir = this.uploadDir.resolve("attachments").normalize();

        createDirectories(this.configDir, this.dataDir, this.logDir, this.uploadDir, this.attachmentDir);
        log.info("Application directories: base={}, config={}, data={}, logs={}, uploads={}",
                this.baseDir, this.configDir, this.dataDir, this.logDir, this.uploadDir);
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path configDir() {
        return configDir;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path logDir() {
        return logDir;
    }

    public Path uploadDir() {
        return uploadDir;
    }

    public Path attachmentDir() {
        return attachmentDir;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Path resolve(Path base, String configured, Path fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback.toAbsolutePath().normalize();
        }
        Path path = Path.of(configured);
        return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
    }

    private static void createDirectories(Path... directories) {
        for (Path directory : directories) {
            try {
                Files.createDirectories(Objects.requireNonNull(directory));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create application directory: " + directory, e);
            }
        }
    }
}
