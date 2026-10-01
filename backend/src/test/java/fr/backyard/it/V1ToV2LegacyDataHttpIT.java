package fr.backyard.it;

import fr.backyard.it.support.AbstractApiIT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 6, CA12 (RG8, CL10, reserve R5-4) [IT] : volet HTTP de CA31 (inc. 5) sur une base H2 (mode
 * PostgreSQL) <strong>migree de V1 a V2 par Flyway alors qu'elle contient deja des donnees</strong>, sur laquelle le
 * contexte Spring complet est ensuite demarre. Aucune insertion SQL apres la migration.
 *
 * <p>Les lignes de depart sont celles de {@code V2MigrationIT#ca31_migrationPurgesNamesAndKeepsRaceData}, reprises a
 * l'identique (ce test n'est pas modifie). Base en memoire dediee ({@code legacyv1v2}), distincte de celle des autres
 * IT : la migration V1 -> V2 est faite dans {@code @BeforeAll}, avant le chargement du contexte (qui n'a lieu qu'a la
 * creation de l'instance de test), et la preuve que V2 etait appliquee avant le demarrage est verifiee dans le test.</p>
 */
@Tag("INC-6")
@SpringBootTest(properties = "spring.datasource.url=" + V1ToV2LegacyDataHttpIT.URL)
class V1ToV2LegacyDataHttpIT extends AbstractApiIT {

    static final String URL = "jdbc:h2:mem:legacyv1v2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static long v2AppliedAtMillis;
    private static List<Map<String, Object>> historyBeforeContext;
    private static Long r1;
    private static Long r0;
    private static Long aliceId;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationContext context;

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration");
        return (target == null ? configuration : configuration.target(target)).load();
    }

    @BeforeAll
    static void migrateV1ThenPopulateThenMigrateV2BeforeTheContextStarts() {
        flyway("1").migrate();
        JdbcTemplate seed = new JdbcTemplate(new DriverManagerDataSource(URL, "sa", ""));
        seed.update("INSERT INTO race (name, race_date, status, started_at, loop_distance, loop_duration, "
            + "loop_elevation) VALUES ('R1', DATE '2026-10-03', 'RUNNING', TIMESTAMP WITH TIME ZONE "
            + "'2026-10-03 08:00:00+00', 6706, 3600, 50)");
        seed.update("INSERT INTO race (name, race_date, status, started_at, loop_distance, loop_duration, "
            + "loop_elevation) VALUES ('R0', DATE '2026-09-03', 'FINISHED', TIMESTAMP WITH TIME ZONE "
            + "'2026-09-03 08:00:00+00', 6706, 3600, 50)");
        r1 = seed.queryForObject("SELECT id FROM race WHERE name = 'R1'", Long.class);
        r0 = seed.queryForObject("SELECT id FROM race WHERE name = 'R0'", Long.class);
        seed.update("INSERT INTO runner (race_id, bib, name, qr_token, status) VALUES (?, 1, 'Alice', 'tok-alice', "
            + "'ACTIVE')", r1);
        seed.update("INSERT INTO runner (race_id, bib, name, qr_token, status, dnf_reason, dnf_yard) "
            + "VALUES (?, 3, 'Bob', 'tok-bob', 'DNF', 'TIMEOUT', 4)", r0);
        aliceId = seed.queryForObject("SELECT id FROM runner WHERE qr_token = 'tok-alice'", Long.class);
        seed.update("INSERT INTO passage (runner_id, scanned_at, yard_number, source) VALUES (?, TIMESTAMP WITH TIME "
            + "ZONE '2026-10-03 08:50:00+00', 1, 'SCAN')", aliceId);
        seed.update("INSERT INTO passage (runner_id, scanned_at, yard_number, source) VALUES (?, NULL, 2, 'MANUAL')",
            aliceId);
        // le nom brut est bien present avant V2 : l'assertion « aucun Alice » plus bas n'est donc pas vacueuse
        assertThat(seed.queryForObject("SELECT COUNT(*) FROM runner WHERE name IN ('Alice', 'Bob')", Long.class))
            .isEqualTo(2);

        flyway(null).migrate();
        v2AppliedAtMillis = System.currentTimeMillis();
        historyBeforeContext = seed.queryForList(
            "SELECT \"version\", \"success\" FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL "
                + "ORDER BY \"installed_rank\"");
    }

    @BeforeEach
    void clockInYardTwoOfR1() {
        clock.set(Instant.parse("2026-10-03T09:10:00Z"));
    }

    @Test
    @Tag("INC6-CA12")
    @DisplayName("CA12 - V1 et V2 en succes dans flyway_schema_history, V2 appliquee avant le demarrage du contexte")
    void ca12_v2WasAppliedBeforeTheContextStarted() {
        // given / when : etat relu apres le demarrage du contexte
        List<Map<String, Object>> historyNow = jdbc.queryForList(
            "SELECT \"version\", \"success\" FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL "
                + "ORDER BY \"installed_rank\"");

        // then : la preuve est prise avant le contexte, et le contexte n'a rien migre de plus
        assertThat(historyBeforeContext).extracting(row -> row.get("version")).containsExactly("1", "2");
        assertThat(historyBeforeContext).extracting(row -> row.get("success")).containsOnly(true);
        assertThat(historyNow).isEqualTo(historyBeforeContext);
        assertThat(context.getStartupDate()).isGreaterThanOrEqualTo(v2AppliedAtMillis);
    }

    @Test
    @Tag("INC6-CA12")
    @DisplayName("CA12 - E4 de R1 : « Coureur n°1 » ; E4 de R0 : « Coureur n°3 » en DNF ; aucun Alice ni Bob")
    void ca12_boardsShowNeutralNamesOnMigratedData() throws Exception {
        // when
        String boardR1 = mvc.perform(get("/api/public/races/" + r1 + "/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runners.length()").value(1))
            .andExpect(jsonPath("$.runners[0].name").value("Coureur n°1"))
            .andReturn().getResponse().getContentAsString();
        String boardR0 = mvc.perform(get("/api/public/races/" + r0 + "/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runners.length()").value(1))
            .andExpect(jsonPath("$.runners[0].name").value("Coureur n°3"))
            .andExpect(jsonPath("$.runners[0].status").value("DNF"))
            .andReturn().getResponse().getContentAsString();

        // then
        assertThat(List.of(boardR1, boardR0)).allSatisfy(body -> assertThat(body)
            .doesNotContain("Alice").doesNotContain("Bob"));
    }

    @Test
    @Tag("INC6-CA12")
    @DisplayName("CA12 - E5 du dossard 1 : « Coureur n°1 », sans Alice ; E13 de R1 (ADMIN) : « Coureur n°1 », pseudo null")
    void ca12_publicAndAdminRunnerViewsShowNeutralName() throws Exception {
        // when
        String detail = mvc.perform(get("/api/public/runners/" + aliceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Coureur n°1"))
            .andReturn().getResponse().getContentAsString();
        String runners = mvc.perform(get("/api/admin/races/" + r1 + "/runners").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Coureur n°1"))
            .andExpect(jsonPath("$[0].pseudo").isEmpty())
            .andExpect(jsonPath("$[0].accountId").isEmpty())
            .andReturn().getResponse().getContentAsString();

        // then
        assertThat(List.of(detail, runners)).allSatisfy(body -> assertThat(body)
            .doesNotContain("Alice").doesNotContain("Bob"));
    }

    @Test
    @Tag("INC6-CA12")
    @DisplayName("CA12 - DNF manuel (E17, ADMIN, VOLUNTARY) du coureur migre : 200, DNF, « Coureur n°1 », sans Alice")
    void ca12_manualDnfOnMigratedRunnerKeepsNeutralName() throws Exception {
        // when
        String response = mvc.perform(post("/api/admin/runners/" + aliceId + "/dnf")
                .header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"VOLUNTARY\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DNF"))
            .andExpect(jsonPath("$.name").value("Coureur n°1"))
            .andReturn().getResponse().getContentAsString();

        // then
        assertThat(response).doesNotContain("Alice").doesNotContain("Bob");
    }

    @Test
    @Tag("INC6-CA12")
    @DisplayName("CA12 - en base : aucun nom non nul, qr_token, dossard et 2 passages d'Alice inchanges, Bob inchange")
    void ca12_databaseKeepsRaceDataAndHasNoNameLeft() {
        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runner WHERE name IS NOT NULL", Long.class)).isZero();
        Map<String, Object> alice = jdbc.queryForMap("SELECT bib, qr_token, race_id FROM runner WHERE id = ?", aliceId);
        assertThat(alice).containsEntry("BIB", 1).containsEntry("QR_TOKEN", "tok-alice");
        assertThat(((Number) alice.get("RACE_ID")).longValue()).isEqualTo(r1);
        List<Map<String, Object>> passages = jdbc.queryForList(
            "SELECT yard_number, source, scanned_at FROM passage WHERE runner_id = ? ORDER BY yard_number", aliceId);
        assertThat(passages).hasSize(2);
        assertThat(passages.get(0)).containsEntry("YARD_NUMBER", 1).containsEntry("SOURCE", "SCAN");
        assertThat(passages.get(0).get("SCANNED_AT")).isNotNull();
        assertThat(passages.get(1)).containsEntry("YARD_NUMBER", 2).containsEntry("SOURCE", "MANUAL")
            .containsEntry("SCANNED_AT", null);
        Map<String, Object> bob = jdbc.queryForMap(
            "SELECT bib, status, dnf_reason, dnf_yard, qr_token FROM runner WHERE qr_token = 'tok-bob'");
        assertThat(bob).containsEntry("BIB", 3).containsEntry("STATUS", "DNF").containsEntry("DNF_REASON", "TIMEOUT")
            .containsEntry("DNF_YARD", 4);
    }
}
