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
 * V38 adds the Google calendar cache next to the old calendar tables (Phase 4, Track C).
 * It must carry each family's synced calendars over so nothing has to be picked again:
 * one cache calendar per Google calendar a family syncs (two members syncing the same
 * one share it), whose lane an unmarked event goes in (today's member), and a default
 * calendar for new events when the family syncs exactly one enabled calendar. The
 * family's own events and the inbox's links to them stay as they are until the
 * backfill copies them to Google.
 */
class GoogleCalendarCacheMigrationTest {

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

    private static String insert(Connection conn, String sql) throws SQLException {
        String id = UUID.randomUUID().toString();
        conn.createStatement().execute(sql.replace("$id", id));
        return id;
    }

    private static String family(Connection conn, String username) throws SQLException {
        return insert(conn, "INSERT INTO family (id, name, username, password_hash, timezone) "
                + "VALUES ('$id', 'Family', '" + username + "', 'hash', 'America/Los_Angeles')");
    }

    private static String member(Connection conn, String familyId, String name) throws SQLException {
        return insert(conn, "INSERT INTO family_member (id, family_id, name, color) "
                + "VALUES ('$id', '" + familyId + "', '" + name + "', 'CORAL')");
    }

    private static String token(Connection conn, String memberId) throws SQLException {
        return insert(conn, "INSERT INTO google_oauth_token "
                + "(id, member_id, access_token, refresh_token, token_expiry, scope) VALUES ('$id', '"
                + memberId + "', 'enc-access', 'enc-refresh', now(), 'calendar.events.readonly')");
    }

    private static String synced(Connection conn, String tokenId, String memberId, String googleId,
                                 String name, boolean enabled) throws SQLException {
        return insert(conn, "INSERT INTO google_synced_calendar "
                + "(id, token_id, member_id, google_calendar_id, calendar_name, sync_token, enabled) "
                + "VALUES ('$id', '" + tokenId + "', '" + memberId + "', '" + googleId + "', '" + name
                + "', 'old-sync-token', " + enabled + ")");
    }

    private static List<String> strings(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            ResultSet rows = statement.executeQuery();
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    @Test
    void carriesSyncedCalendarsLanesAndTheDefaultOverAndKeepsTheFamilysOwnEvents() throws SQLException {
        String schema = "google_cache_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "37").migrate();

        String home;
        String other;
        String dad;
        String mom;
        String dadToken;
        String weekly;
        try (Connection conn = connect(schema)) {
            home = family(conn, "home");
            dad = member(conn, home, "Dad");
            mom = member(conn, home, "Mom");
            String maya = member(conn, home, "Maya");
            dadToken = token(conn, dad);
            String momToken = token(conn, mom);
            synced(conn, dadToken, dad, "hub@group.calendar.google.com", "Hub", true);
            synced(conn, dadToken, dad, "personal@group.calendar.google.com", "Personal", false);
            // Mom syncs the same family calendar: it must be cached once, with both lanes.
            synced(conn, momToken, mom, "hub@group.calendar.google.com", "Hub", true);

            // A second family syncing two enabled calendars gets no default: there is no
            // single obvious one, so a parent picks it in Family Settings.
            other = family(conn, "other");
            String alex = member(conn, other, "Alex");
            String alexToken = token(conn, alex);
            synced(conn, alexToken, alex, "a@group.calendar.google.com", "A", true);
            synced(conn, alexToken, alex, "b@group.calendar.google.com", "B", true);

            weekly = insert(conn, "INSERT INTO calendar_event "
                    + "(id, title, start_time, end_time, date, member_id, family_id, recurrence_rule) "
                    + "VALUES ('$id', 'Class', '16:00', '17:00', '2026-11-06', '" + maya + "', '" + home
                    + "', 'FREQ=WEEKLY;BYDAY=FR')");
            insert(conn, "INSERT INTO calendar_event "
                    + "(id, title, start_time, end_time, date, end_date, is_all_day, member_id, family_id) "
                    + "VALUES ('$id', 'Trip', '00:00', '00:00', '2026-12-01', '2026-12-03', true, '" + maya
                    + "', '" + home + "')");
            String message = insert(conn, "INSERT INTO inbox_message "
                    + "(id, family_id, message_id, outcome, processed_at) VALUES ('$id', '" + home
                    + "', '<flyer@example.com>', 'EVENTS', now())");
            insert(conn, "INSERT INTO inbox_import "
                    + "(id, family_id, message_id, kind, status, title, calendar_event_id, created_at) "
                    + "VALUES ('$id', '" + home + "', '" + message + "', 'EVENT', 'ADDED', 'Class', '"
                    + weekly + "', now())");
        }

        flyway(schema, "latest").migrate();

        try (Connection conn = connect(schema)) {
            assertThat(strings(conn, "SELECT summary || ':' || enabled FROM google_calendar "
                    + "WHERE family_id = ? ORDER BY summary", UUID.fromString(home)))
                    .containsExactly("Hub:true", "Personal:false");
            assertThat(strings(conn, "SELECT summary FROM google_calendar WHERE family_id = ? ORDER BY summary",
                    UUID.fromString(other))).containsExactly("A", "B");

            // The first run is a full sync: the old tokens were for the old tables.
            assertThat(strings(conn, "SELECT DISTINCT coalesce(sync_token, 'none') || ':' || full_sync_required "
                    + "FROM google_calendar")).containsExactly("none:true");
            // Each calendar is polled with a token that synced it.
            assertThat(strings(conn, "SELECT count(*) FROM google_calendar WHERE sync_token_id IS NULL"))
                    .containsExactly("0");
            assertThat(strings(conn, "SELECT sync_token_id FROM google_calendar WHERE summary = 'Personal'"))
                    .containsExactly(dadToken);

            assertThat(strings(conn, "SELECT c.summary || ':' || m.name FROM google_calendar_member gm "
                    + "JOIN google_calendar c ON c.id = gm.calendar_id "
                    + "JOIN family_member m ON m.id = gm.member_id WHERE c.family_id = ? ORDER BY 1",
                    UUID.fromString(home)))
                    .containsExactly("Hub:Dad", "Hub:Mom", "Personal:Dad");

            assertThat(strings(conn, "SELECT c.summary FROM family f JOIN google_calendar c "
                    + "ON c.id = f.default_google_calendar_id WHERE f.id = ?", UUID.fromString(home)))
                    .containsExactly("Hub");
            assertThat(strings(conn, "SELECT coalesce(default_google_calendar_id::text, 'none') FROM family "
                    + "WHERE id = ?", UUID.fromString(other))).containsExactly("none");

            // The family's own events and the inbox's link stay until the backfill copies them.
            assertThat(strings(conn, "SELECT title || ':' || coalesce(copied_google_event_id, 'not copied') "
                    + "FROM calendar_event ORDER BY title")).containsExactly("Class:not copied", "Trip:not copied");
            assertThat(strings(conn, "SELECT calendar_event_id::text || ':' "
                    + "|| coalesce(google_event_id, 'none') FROM inbox_import"))
                    .containsExactly(weekly + ":none");

            assertThat(strings(conn, "SELECT DISTINCT needs_reauth::text || ':' "
                    + "|| coalesce(google_account_email, 'unknown') FROM google_oauth_token"))
                    .containsExactly("false:unknown");
            assertThat(strings(conn, "SELECT count(*) FROM google_event")).containsExactly("0");
        }
    }
}
