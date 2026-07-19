package com.gs.ais.service;

import com.gs.ais.persistence.HistoricalMigrationExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalDataMigrationServiceTests {

    @Test
    void appliesAndRecordsMigrationOnce() {
        HistoricalMigrationExecutor executor = mock(HistoricalMigrationExecutor.class);
        when(executor.isApplied(HistoricalDataMigrationService.MIGRATION_VERSION)).thenReturn(false);
        when(executor.migrateDrawResponses()).thenReturn(1);
        when(executor.migrateDrawRequests()).thenReturn(1);
        when(executor.defaultMessageFields()).thenReturn(6);

        HistoricalDataMigrationService service = new HistoricalDataMigrationService(executor);
        HistoricalDataMigrationService.MigrationResult result = service.migrateIfNeeded();

        assertTrue(result.applied());
        assertEquals(1, result.drawResponseRows());
        assertEquals(1, result.drawRequestRows());
        assertEquals(6, result.defaultedRows());
        assertEquals(8, result.affectedRows());
        verify(executor).ensureMigrationTable();
        verify(executor).recordApplied(
                eq(HistoricalDataMigrationService.MIGRATION_VERSION),
                eq("Migrate legacy chat/image messages to typed message mechanism"),
                eq(8));
    }

    @Test
    void skipsMigrationWhenVersionWasAlreadyApplied() {
        HistoricalMigrationExecutor executor = mock(HistoricalMigrationExecutor.class);
        when(executor.isApplied(HistoricalDataMigrationService.MIGRATION_VERSION)).thenReturn(true);

        HistoricalDataMigrationService service = new HistoricalDataMigrationService(executor);
        HistoricalDataMigrationService.MigrationResult result = service.migrateIfNeeded();

        assertFalse(result.applied());
        assertEquals(0, result.affectedRows());
        verify(executor).ensureMigrationTable();
        verify(executor, never()).migrateDrawResponses();
        verify(executor, never()).recordApplied(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
