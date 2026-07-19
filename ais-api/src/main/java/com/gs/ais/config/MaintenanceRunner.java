package com.gs.ais.config;

import com.gs.ais.service.HistoricalDataMigrationService;
import com.gs.ais.service.OrphanFileCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class MaintenanceRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceRunner.class);

    private final HistoricalDataMigrationService migrationService;
    private final OrphanFileCleanupService cleanupService;
    private final boolean migrationEnabled;
    private final boolean cleanupEnabled;
    private final boolean cleanupDryRun;

    public MaintenanceRunner(
            HistoricalDataMigrationService migrationService,
            OrphanFileCleanupService cleanupService,
            @Value("${maintenance.history-migration.enabled:true}") boolean migrationEnabled,
            @Value("${maintenance.orphan-cleanup.enabled:false}") boolean cleanupEnabled,
            @Value("${maintenance.orphan-cleanup.dry-run:true}") boolean cleanupDryRun) {
        this.migrationService = migrationService;
        this.cleanupService = cleanupService;
        this.migrationEnabled = migrationEnabled;
        this.cleanupEnabled = cleanupEnabled;
        this.cleanupDryRun = cleanupDryRun;
    }

    @Override
    public void run(String... args) {
        if (migrationEnabled) {
            migrationService.migrateIfNeeded();
        } else {
            log.info("Historical data migration is disabled");
        }

        if (cleanupEnabled) {
            cleanupService.cleanup(cleanupDryRun);
        } else {
            log.info("Orphan file cleanup is disabled; enable it explicitly for an audit or cleanup run");
        }
    }
}
