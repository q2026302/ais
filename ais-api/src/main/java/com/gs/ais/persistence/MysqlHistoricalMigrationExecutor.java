package com.gs.ais.persistence;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MysqlHistoricalMigrationExecutor implements HistoricalMigrationExecutor {

    private final JdbcTemplate jdbcTemplate;

    public MysqlHistoricalMigrationExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void ensureMigrationTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS data_migrations (
                    version VARCHAR(100) NOT NULL PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    affected_rows INT NOT NULL DEFAULT 0,
                    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
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
                UPDATE messages response
                JOIN messages request
                  ON request.id = response.parent_message_id
                 AND request.session_id = response.session_id
                SET response.message_type = 'DRAW_RESPONSE',
                    response.status = COALESCE(response.status, 'SUCCESS'),
                    response.draw_prompt = COALESCE(NULLIF(TRIM(response.draw_prompt), ''), request.content)
                WHERE response.role = 'ASSISTANT'
                  AND response.image_url IS NOT NULL
                  AND TRIM(response.image_url) <> ''
                  AND (response.message_type IS NULL OR response.message_type = 'CHAT')
                  AND request.role = 'USER'
                """);
    }

    @Override
    public int migrateDrawRequests() {
        return jdbcTemplate.update("""
                UPDATE messages request
                JOIN messages response
                  ON response.parent_message_id = request.id
                 AND response.session_id = request.session_id
                SET request.message_type = 'DRAW_REQUEST',
                    request.status = COALESCE(request.status, 'SUCCESS'),
                    request.draw_prompt = COALESCE(NULLIF(TRIM(request.draw_prompt), ''), request.content)
                WHERE request.role = 'USER'
                  AND (request.message_type IS NULL OR request.message_type = 'CHAT')
                  AND response.role = 'ASSISTANT'
                  AND response.message_type = 'DRAW_RESPONSE'
                  AND response.image_url IS NOT NULL
                  AND TRIM(response.image_url) <> ''
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
