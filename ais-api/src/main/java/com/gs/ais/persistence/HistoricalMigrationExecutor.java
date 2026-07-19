package com.gs.ais.persistence;

/**
 * Dialect-specific SQL for one-time historical message migrations.
 * Implementations are selected by Spring profile ({@code sqlite} / {@code mysql}).
 */
public interface HistoricalMigrationExecutor {

    /** Ensures the {@code data_migrations} bookkeeping table exists. */
    void ensureMigrationTable();

    /** @return whether the given migration version has already been recorded */
    boolean isApplied(String version);

    /**
     * Converts legacy assistant image messages into DRAW_RESPONSE when a same-session
     * user parent exists, and copies a draw prompt when missing.
     *
     * @return number of rows updated
     */
    int migrateDrawResponses();

    /**
     * Marks user parents of DRAW_RESPONSE messages as DRAW_REQUEST.
     *
     * @return number of rows updated
     */
    int migrateDrawRequests();

    /**
     * Fills null message_type / status with CHAT / SUCCESS.
     *
     * @return number of rows updated
     */
    int defaultMessageFields();

    /** Records a successful migration version. */
    void recordApplied(String version, String description, int affectedRows);
}
