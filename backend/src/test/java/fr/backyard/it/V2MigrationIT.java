package fr.backyard.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 5 - RG6, CA31 (partie base) [IT] : une base migree en V1 seulement, avec des coureurs nommes, puis
 * migree en V2 sur H2 en mode PostgreSQL. Base en memoire dediee, sans contexte Spring.
 */
@Tag("INC-5")
class V2MigrationIT {

    private static final String URL = "jdbc:h2:mem:v2migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration");
        return (target == null ? configuration : configuration.target(target)).load();
    }

    @Test
    @DisplayName("CA31 - V2 sur donnees V1 : noms purges, account_id null, dossards, statuts, DNF, tokens et passages inchanges")
    void ca31_migrationPurgesNamesAndKeepsRaceData() {
        flyway("1").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, "sa", ""));
        jdbc.update("INSERT INTO race (name, race_date, status, started_at, loop_distance, loop_duration, "
            + "loop_elevation) VALUES ('R1', DATE '2026-10-03', 'RUNNING', TIMESTAMP WITH TIME ZONE "
            + "'2026-10-03 08:00:00+00', 6706, 3600, 50)");
        jdbc.update("INSERT INTO race (name, race_date, status, started_at, loop_distance, loop_duration, "
            + "loop_elevation) VALUES ('R0', DATE '2026-09-03', 'FINISHED', TIMESTAMP WITH TIME ZONE "
            + "'2026-09-03 08:00:00+00', 6706, 3600, 50)");
        Long r1 = jdbc.queryForObject("SELECT id FROM race WHERE name = 'R1'", Long.class);
        Long r0 = jdbc.queryForObject("SELECT id FROM race WHERE name = 'R0'", Long.class);
        jdbc.update("INSERT INTO runner (race_id, bib, name, qr_token, status) VALUES (?, 1, 'Alice', 'tok-alice', "
            + "'ACTIVE')", r1);
        jdbc.update("INSERT INTO runner (race_id, bib, name, qr_token, status, dnf_reason, dnf_yard) "
            + "VALUES (?, 3, 'Bob', 'tok-bob', 'DNF', 'TIMEOUT', 4)", r0);
        Long alice = jdbc.queryForObject("SELECT id FROM runner WHERE qr_token = 'tok-alice'", Long.class);
        jdbc.update("INSERT INTO passage (runner_id, scanned_at, yard_number, source) VALUES (?, TIMESTAMP WITH TIME "
            + "ZONE '2026-10-03 08:50:00+00', 1, 'SCAN')", alice);
        jdbc.update("INSERT INTO passage (runner_id, scanned_at, yard_number, source) VALUES (?, NULL, 2, 'MANUAL')",
            alice);

        flyway(null).migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runner WHERE name IS NOT NULL", Long.class)).isZero();
        Map<String, Object> aliceRow = jdbc.queryForMap(
            "SELECT bib, status, qr_token, account_id FROM runner WHERE id = ?", alice);
        assertThat(aliceRow).containsEntry("BIB", 1).containsEntry("STATUS", "ACTIVE")
            .containsEntry("QR_TOKEN", "tok-alice").containsEntry("ACCOUNT_ID", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM passage WHERE runner_id = ?", Long.class, alice))
            .isEqualTo(2);
        Map<String, Object> bobRow = jdbc.queryForMap(
            "SELECT bib, status, dnf_reason, dnf_yard, account_id FROM runner WHERE qr_token = 'tok-bob'");
        assertThat(bobRow).containsEntry("BIB", 3).containsEntry("STATUS", "DNF")
            .containsEntry("DNF_REASON", "TIMEOUT").containsEntry("DNF_YARD", 4).containsEntry("ACCOUNT_ID", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account", Long.class)).isZero();
    }
}
