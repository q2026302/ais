package com.gs.ais.persistence;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite-compatible migration SQL. Avoids MySQL multi-table UPDATE JOIN syntax.
 */
@Component
@Profile("sqlite")
public class SqliteHistoricalMigrationExecutor implements HistoricalMigrationExecutor {

    private final JdbcTemplate jdbcTemplate;

    public SqliteHistoricalMigrationExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void ensureMigrationTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS data_migrations (
                    version VARCHAR(100) NOT NULL PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    affected_rows INT NOT NULL DEFAULT 0,
                    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
    }

    @Override
    public boolean isApplied(String version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_migrations WHERE version = ?",
                Integer.class,
                version);
        return count != null && count > 0;
    }

    @Override
    public int migrateDrawResponses() {
        return jdbcTemplate.update("""
                UPDATE messages
                SET message_type = 'DRAW_RESPONSE',
                    status = COALESCE(status, 'SUCCESS'),
                    draw_prompt = COALESCE(
                        NULLIF(TRIM(draw_prompt), ''),
                        (SELECT request.content
                         FROM messages request
                         WHERE request.id = messages.parent_message_id
                           AND request.session_id = messages.session_id
                           AND request.role = 'USER'
                         LIMIT 1)
                    )
                WHERE role = 'ASSISTANT'
                  AND image_url IS NOT NULL
                  AND TRIM(image_url) <> ''
                  AND (message_type IS NULL OR message_type = 'CHAT')
                  AND EXISTS (
                      SELECT 1
                      FROM messages request
                      WHERE request.id = messages.parent_message_id
                        AND request.session_id = messages.session_id
                        AND request.role = 'USER'
                  )
                """);
    }

    @Override
    public int migrateDrawRequests() {
        return jdbcTemplate.update("""
                UPDATE messages
                SET message_type = 'DRAW_REQUEST',
                    status = COALESCE(status, 'SUCCESS'),
                    draw_prompt = COALESCE(NULLIF(TRIM(draw_prompt), ''), content)
                WHERE role = 'USER'
                  AND (message_type IS NULL OR message_type = 'CHAT')
                  AND EXISTS (
                      SELECT 1
                      FROM messages response
                      WHERE response.parent_message_id = messages.id
                        AND response.session_id = messages.session_id
                        AND response.role = 'ASSISTANT'
                        AND response.message_type = 'DRAW_RESPONSE'
                        AND response.image_url IS NOT NULL
                        AND TRIM(response.image_url) <> ''
                  )
                """);
    }

    @Override
    public int defaultMessageFields() {
        return jdbcTemplate.update("""
                UPDATE messages
                SET message_type = COALESCE(message_type, 'CHAT'),
                    status = COALESCE(status, 'SUCCESS')
                WHERE message_type IS NULL OR status IS NULL
                """);
    }

    @Override
    public void recordApplied(String version, String description, int affectedRows) {
        jdbcTemplate.update("""
                INSERT INTO data_migrations (version, description, affected_rows)
                VALUES (?, ?, ?)
                """,
                version,
                description,
                affectedRows);
    }
}
