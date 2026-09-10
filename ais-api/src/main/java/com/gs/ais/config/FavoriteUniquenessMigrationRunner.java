package com.gs.ais.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Establishes the database-level uniqueness guarantee for the work library:
 * a saved work is unique per {@code (user_id, message_id)} on <em>every</em>
 * supported database.
 *
 * <h2>Why this is not a {@code @UniqueConstraint} on the entity</h2>
 * Hibernate's community {@code SQLiteDialect} does not emit the DDL for a
 * {@code @UniqueConstraint} declared in {@code @Table}: its
 * {@code SQLiteUniqueDelegate.getAlterTableToAddUniqueKeyCommand(...)} returns an
 * empty string, so {@code ddl-auto: update} silently creates no index at all.
 * The same annotation <em>does</em> produce a constraint on H2 and MySQL, which
 * is exactly why the problem stayed invisible to the H2-backed test suite. A
 * second, worse consequence of declaring it on the entity is upgrade safety: on
 * H2/MySQL Hibernate would try to add the constraint during schema update,
 * i.e. before any application-level backfill could run, and a single historical
 * duplicate row would abort startup.
 *
 * <p>Uniqueness is therefore owned here, in one place, with plain SQL:
 * <ol>
 *   <li>collapse any pre-existing duplicate {@code (user_id, message_id)} rows
 *       (keeping the earliest save) so an upgrade can never fail on legacy data;</li>
 *   <li>create a real {@code UNIQUE INDEX} on {@code (user_id, message_id)},
 *       skipping the creation when it already exists.</li>
 * </ol>
 *
 * <p>Implemented as a {@link CommandLineRunner} rather than a
 * {@code @PostConstruct} component so it is guaranteed to run <em>after</em>
 * Hibernate created/updated the schema ({@code favorites} must exist), which a
 * bean that only depends on {@link JdbcTemplate} would not be.
 */
@Component
@Order(10)
public class FavoriteUniquenessMigrationRunner implements CommandLineRunner {

    /** Name of the unique index; stable because {@link #indexExists()} matches on it. */
    public static final String UNIQUE_INDEX_NAME = "uk_favorites_user_message";

    private static final String TABLE = "favorites";
    private static final Logger log = LoggerFactory.getLogger(FavoriteUniquenessMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public FavoriteUniquenessMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        if (!tableExists()) {
            log.warn("Table '{}' does not exist yet; skipping uniqueness migration", TABLE);
            return;
        }

        int removed = deduplicate();
        if (removed > 0) {
            log.warn("Removed {} duplicate favorite row(s); kept the earliest save per (user_id, message_id)", removed);
        }

        ensureUniqueIndex();
    }

    /**
     * Deletes every duplicate {@code (user_id, message_id)} group member except
     * the row with the smallest id. Written with portable SQL on purpose: the
     * groups are read first and deleted by explicit key, which works on SQLite,
     * H2 and MySQL without relying on the "you cannot select from the table you
     * are deleting" derived-table workaround.
     */
    private int deduplicate() {
        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList("""
                select user_id, message_id, min(id) as keep_id
                from favorites
                group by user_id, message_id
                having count(*) > 1
                """);

        int removed = 0;
        for (Map<String, Object> duplicate : duplicates) {
            Long userId = number(duplicate.get("user_id"));
            Long messageId = number(duplicate.get("message_id"));
            Long keepId = number(duplicate.get("keep_id"));
            if (userId == null || messageId == null || keepId == null) {
                continue;
            }
            removed += jdbcTemplate.update(
                    "delete from favorites where user_id = ? and message_id = ? and id <> ?",
                    userId, messageId, keepId);
        }
        return removed;
    }

    /**
     * Creates the unique index when it is missing. On databases whose
     * {@code CREATE INDEX} has no {@code IF NOT EXISTS} (MySQL) the index is only
     * attempted after an explicit existence probe; a create that still races with
     * another starting instance is tolerated as long as the index ends up present.
     */
    private void ensureUniqueIndex() {
        if (indexExists()) {
            log.debug("Unique index {} already present on {}", UNIQUE_INDEX_NAME, TABLE);
            return;
        }

        String dialect = dialectName();
        String statement = supportsIfNotExists(dialect)
                ? "create unique index if not exists " + UNIQUE_INDEX_NAME + " on " + TABLE + " (user_id, message_id)"
                : "create unique index " + UNIQUE_INDEX_NAME + " on " + TABLE + " (user_id, message_id)";
        try {
            jdbcTemplate.execute(statement);
            log.info("Created unique index {} on {}(user_id, message_id) for dialect {}", UNIQUE_INDEX_NAME, TABLE, dialect);
        } catch (DataAccessException e) {
            if (indexExists()) {
                log.info("Unique index {} was created concurrently by another instance on {}", UNIQUE_INDEX_NAME, TABLE);
                return;
            }
            throw new IllegalStateException(
                    "Could not create the unique index " + UNIQUE_INDEX_NAME + " on " + TABLE
                            + "(user_id, message_id); duplicate favorites could be persisted", e);
        }

        if (!indexExists()) {
            throw new IllegalStateException(
                    "Unique index " + UNIQUE_INDEX_NAME + " is still missing after creating it on " + TABLE);
        }
    }

    private boolean indexExists() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String candidate : tableNameCandidates()) {
                try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, candidate, false, false)) {
                    while (indexes.next()) {
                        String name = indexes.getString("INDEX_NAME");
                        if (name != null && name.equalsIgnoreCase(UNIQUE_INDEX_NAME)) {
                            if (indexes.getBoolean("NON_UNIQUE")) {
                                throw new IllegalStateException(
                                        "Index " + UNIQUE_INDEX_NAME + " exists but is not unique");
                            }
                            return true;
                        }
                    }
                } catch (SQLException ignored) {
                    // Try the next identifier casing.
                }
            }
            return false;
        }));
    }

    private boolean tableExists() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String candidate : tableNameCandidates()) {
                try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, candidate, null)) {
                    if (tables.next()) {
                        return true;
                    }
                } catch (SQLException ignored) {
                    // Try the next identifier casing.
                }
            }
            return false;
        }));
    }

    /**
     * Identifier casings to probe. H2 folds unquoted names (upper by default,
     * lower under {@code DATABASE_TO_LOWER=TRUE}) while SQLite and MySQL preserve
     * the name Hibernate created, so the predicates are tried case-insensitively.
     */
    private List<String> tableNameCandidates() {
        return List.of(TABLE, TABLE.toLowerCase(Locale.ROOT), TABLE.toUpperCase(Locale.ROOT));
    }

    private String dialectName() {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT));
    }

    /** SQLite and H2 both accept {@code CREATE [UNIQUE] INDEX IF NOT EXISTS}; MySQL does not. */
    private boolean supportsIfNotExists(String dialect) {
        return !dialect.contains("mysql") && !dialect.contains("mariadb");
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
