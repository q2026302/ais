package com.gs.ais.integration;

import com.gs.ais.config.FavoriteUniquenessMigrationRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Problem 4.1 / 4.4: the work library must be protected by a real database-level
 * unique index on {@code (user_id, message_id)} — not by an application check or a
 * row lock — and an upgrade that already contains duplicates must heal itself
 * instead of aborting startup.
 *
 * <p>Runs against the same in-memory H2 schema as the rest of the suite; the
 * migration runner is exercised both on the schema Hibernate produced (index must
 * be present) and on a deliberately broken copy (index dropped, duplicates
 * inserted) to prove the self-healing path.
 */
@SpringBootTest(properties = "feishu.enabled=false")
class FavoriteUniquenessMigrationIntegrationTests {

    private static final long TEST_USER_ID = 9_120_001L;
    private static final long TEST_MESSAGE_ID = 9_120_002L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FavoriteUniquenessMigrationRunner migrationRunner;

    @Test
    void schemaCarriesARealUniqueIndexOnUserAndMessage() {
        assertTrue(indexPresent(), "the live favorites table must have the unique index");

        jdbcTemplate.update("insert into favorites (user_id, message_id) values (?, ?)",
                TEST_USER_ID, TEST_MESSAGE_ID);
        try {
            // The database itself must refuse the second, identical row.
            assertThrows(DataIntegrityViolationException.class, () ->
                    jdbcTemplate.update("insert into favorites (user_id, message_id) values (?, ?)",
                            TEST_USER_ID, TEST_MESSAGE_ID));
            assertEquals(1, countRows(TEST_USER_ID, TEST_MESSAGE_ID));
        } finally {
            jdbcTemplate.update("delete from favorites where user_id = ?", TEST_USER_ID);
        }
    }

    @Test
    void runnerCollapsesLegacyDuplicatesAndRecreatesTheIndex() {
        jdbcTemplate.execute("drop index " + FavoriteUniquenessMigrationRunner.UNIQUE_INDEX_NAME);
        jdbcTemplate.update("insert into favorites (user_id, message_id) values (?, ?)", TEST_USER_ID, TEST_MESSAGE_ID);
        jdbcTemplate.update("insert into favorites (user_id, message_id) values (?, ?)", TEST_USER_ID, TEST_MESSAGE_ID);
        jdbcTemplate.update("insert into favorites (user_id, message_id) values (?, ?)", TEST_USER_ID, TEST_MESSAGE_ID);
        try {
            List<Long> inserted = favoriteIds();
            assertEquals(3, inserted.size());
            long expectedSurvivor = inserted.stream().min(Long::compare).orElseThrow();

            // Must not throw, must keep exactly the earliest save, must restore the index.
            migrationRunner.run();

            assertEquals(List.of(expectedSurvivor), favoriteIds());
            assertTrue(indexPresent(), "the runner must recreate the unique index");
            assertThrows(DataIntegrityViolationException.class, () ->
                    jdbcTemplate.update("insert into favorites (user_id, message_id) values (?, ?)",
                            TEST_USER_ID, TEST_MESSAGE_ID));
        } finally {
            jdbcTemplate.update("delete from favorites where user_id = ?", TEST_USER_ID);
            // Leave the shared schema exactly as the other test classes expect it.
            migrationRunner.run();
        }
    }

    private List<Long> favoriteIds() {
        return jdbcTemplate.queryForList("select id from favorites where user_id = ? order by id",
                Long.class, TEST_USER_ID);
    }

    private int countRows(long userId, long messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from favorites where user_id = ? and message_id = ?",
                Integer.class, userId, messageId);
        return count == null ? 0 : count;
    }

    private boolean indexPresent() {
        return Boolean.TRUE.equals(jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection -> {
                    var metaData = connection.getMetaData();
                    for (String table : List.of("favorites", "FAVORITES")) {
                        try (var indexes = metaData.getIndexInfo(connection.getCatalog(), null, table, false, false)) {
                            while (indexes.next()) {
                                String name = indexes.getString("INDEX_NAME");
                                if (FavoriteUniquenessMigrationRunner.UNIQUE_INDEX_NAME.equalsIgnoreCase(name)) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                }));
    }
}
