package com.gs.ais.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ensures the parent directory for the SQLite database file exists before
 * the DataSource bean is created.
 */
@Component
@Profile("sqlite")
public class SqliteDataDirectoryInitializer implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(SqliteDataDirectoryInitializer.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String jdbcUrl = environment.getProperty("spring.datasource.url", "jdbc:sqlite:./data/ais.db");
        Path dbPath = resolveSqlitePath(jdbcUrl);
        if (dbPath == null) {
            return;
        }
        Path parent = dbPath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
                log.info("SQLite data directory ready: {}", parent.toAbsolutePath());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create SQLite data directory: " + parent, e);
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static Path resolveSqlitePath(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:")) {
            return null;
        }
        String pathPart = jdbcUrl.substring("jdbc:sqlite:".length());
        if (pathPart.startsWith("file:")) {
            String afterFile = pathPart.substring("file:".length());
            int query = afterFile.indexOf('?');
            String name = query >= 0 ? afterFile.substring(0, query) : afterFile;
            if (name.isBlank() || name.contains(":") || "memdb1".equals(name) || afterFile.contains("mode=memory")) {
                return null;
            }
            return Paths.get(name);
        }
        int query = pathPart.indexOf('?');
        String file = query >= 0 ? pathPart.substring(0, query) : pathPart;
        if (file.isBlank() || ":memory:".equals(file)) {
            return null;
        }
        return Paths.get(file);
    }
}
