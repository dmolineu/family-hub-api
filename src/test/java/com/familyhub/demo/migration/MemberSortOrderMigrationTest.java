package com.familyhub.demo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V36 gives existing members an order once: parents first, then oldest to youngest,
 * those without a birthday after those with one, and remaining ties in storage order
 * (what the screens showed). Each family counts from 0.
 */
class MemberSortOrderMigrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @BeforeAll
    static void startContainer() {
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(target)
                .load();
    }

    private Connection connect(String schema) throws SQLException {
        Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        conn.createStatement().execute("SET search_path TO \"" + schema + "\"");
        return conn;
    }

    private static String family(Connection conn, String username) throws SQLException {
        String id = UUID.randomUUID().toString();
        conn.createStatement().execute("INSERT INTO family (id, name, username, password_hash) VALUES ('"
                + id + "', 'Family', '" + username + "', 'hash')");
        return id;
    }

    /** A member; {@code born} is "year, month, day" or "NULL, NULL, NULL". */
    private static void member(Connection conn, String familyId, String name, String role, String born)
            throws SQLException {
        conn.createStatement().execute(
                "INSERT INTO family_member (family_id, name, color, role, birth_year, birth_month, birth_day) "
                        + "VALUES ('" + familyId + "', '" + name + "', 'CORAL', '" + role + "', " + born + ")");
    }

    private static List<String> namesInOrder(Connection conn, String familyId) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT name FROM family_member WHERE family_id = ? ORDER BY sort_order")) {
            statement.setObject(1, UUID.fromString(familyId));
            ResultSet rows = statement.executeQuery();
            List<String> names = new ArrayList<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names;
        }
    }

    @Test
    void parentsComeFirstThenOldestToYoungestAndTiesKeepTheirStorageOrder() throws SQLException {
        String schema = "member_order_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "35").migrate();

        String first;
        String second;
        try (Connection conn = connect(schema)) {
            first = family(conn, "first");
            second = family(conn, "second");
            // Interleaved, so a family's rows are not one block of the table.
            member(conn, first, "Ben", "KID", "2012, 5, 1");
            member(conn, second, "Zoe", "KID", "2010, 1, 1");
            member(conn, first, "Ann", "PARENT", "NULL, NULL, NULL");
            member(conn, first, "Eve", "KID", "NULL, NULL, NULL");
            member(conn, first, "Cal", "KID", "2012, 3, 9");
            member(conn, second, "Yan", "PARENT", "NULL, NULL, NULL");
            member(conn, first, "Dan", "PARENT", "NULL, NULL, NULL");
            // An edit writes Ann's row again after Dan's: the parents tie, so storage decides.
            conn.createStatement().execute("UPDATE family_member SET color = 'TEAL' WHERE name = 'Ann'");
        }

        flyway(schema, "latest").migrate();

        try (Connection conn = connect(schema)) {
            assertThat(namesInOrder(conn, first)).containsExactly("Dan", "Ann", "Cal", "Ben", "Eve");
            assertThat(namesInOrder(conn, second)).containsExactly("Yan", "Zoe");

            ResultSet counts = conn.createStatement().executeQuery(
                    "SELECT family_id, min(sort_order), max(sort_order), count(DISTINCT sort_order) "
                            + "FROM family_member GROUP BY family_id");
            while (counts.next()) {
                long size = counts.getString(1).equals(first) ? 5 : 2;
                assertThat(counts.getInt(2)).isZero();
                assertThat(counts.getInt(3)).isEqualTo(size - 1);
                assertThat(counts.getLong(4)).isEqualTo(size);
            }

            ResultSet column = conn.getMetaData().getColumns(null, schema, "family_member", "sort_order");
            assertThat(column.next()).as("family_member.sort_order should exist").isTrue();
            assertThat(column.getString("IS_NULLABLE")).isEqualTo("NO");
        }
    }
}
