package com.gs.ais.service;

import com.gs.ais.persistence.HistoricalMigrationExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoricalDataMigrationService {

    static final String MIGRATION_VERSION = "20260713-message-image-mechanism-v1";
    private static final Logger log = LoggerFactory.getLogger(HistoricalDataMigrationService.class);

    private final HistoricalMigrationExecutor executor;

    public HistoricalDataMigrationService(HistoricalMigrationExecutor executor) {
        this.executor = executor;
    }

    /**
     * Migrates legacy messages once and records the migration version.
     *
     * <p>Legacy assistant image messages are converted to DRAW_RESPONSE when a
     * same-session user parent exists. Their parent messages become DRAW_REQUEST.
     * Remaining messages with null type/status receive the conservative CHAT/SUCCESS
     * defaults. Existing non-null values are never overwritten.</p>
     */
    @Transactional
    public MigrationResult migrateIfNeeded() {
        executor.ensureMigrationTable();
        if (executor.isApplied(MIGRATION_VERSION)) {
            log.info("Historical data migration {} already applied", MIGRATION_VERSION);
            return new MigrationResult(MIGRATION_VERSION, false, 0, 0, 0);
        }

        int responseRows = executor.migrateDrawResponses();
        int requestRows = executor.migrateDrawRequests();
        int defaultRows = executor.defaultMessageFields();

        int affectedRows = responseRows + requestRows + defaultRows;
        executor.recordApplied(
                MIGRATION_VERSION,
                "Migrate legacy chat/image messages to typed message mechanism",
                affectedRows);

        log.info(
                "Applied historical data migration {}: draw responses={}, draw requests={}, defaulted messages={}",
                MIGRATION_VERSION, responseRows, requestRows, defaultRows);
        return new MigrationResult(MIGRATION_VERSION, true, responseRows, requestRows, defaultRows);
    }

    public record MigrationResult(
            String version,
            boolean applied,
            int drawResponseRows,
            int drawRequestRows,
            int defaultedRows) {

        public int affectedRows() {
            return drawResponseRows + drawRequestRows + defaultedRows;
        }
    }
}
