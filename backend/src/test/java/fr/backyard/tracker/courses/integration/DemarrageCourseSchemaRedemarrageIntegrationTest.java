package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 4.1, CA4 : changeset 0008-demarrage-course sur base vierge (colonne nullable de type
 * timestamptz, aucune contrainte liée au statut), ddl-auto=validate, et Course démarrée relue à l'identique après
 * redémarrage du contexte.
 */
@Testcontainers
class DemarrageCourseSchemaRedemarrageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String CHEMIN = "/api/administration/courses";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, ?, 400, 60, 0, 10, 5, ?)";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA4 : base vierge migrée (0002 à 0008), demarree_le timestamptz nullable, Courses existantes à null, aucune contrainte liée au statut, ddl-auto=validate ; Course démarrée relue avec le même demarreeLe après redémarrage")
    void ca4_schema_et_redemarrage() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        String idCourse;
        JsonNode demarree;
        String jourParis = LocalDate.now(ZoneId.of("Europe/Paris")).toString();
        UUID existante = UUID.randomUUID();
        try (ConfigurableApplicationContext premier = demarrer()) {
            assertThat(premier.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            JdbcTemplate jdbc = jdbc();
            jdbc.update(INSERTION_COURSE, existante, "Existante", Date.valueOf(jourParis), "EN_PREPARATION", null);
            ClientHttp api = new ClientHttp(Integer.parseInt(premier.getEnvironment().getProperty("local.server.port")));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> creation = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                    "{\"nom\":\"Backyard des Crêtes\",\"date\":\"" + jourParis
                            + "\",\"distanceBoucleMetres\":6706,\"dureeBoucleMinutes\":60,"
                            + "\"denivelePositifBoucleMetres\":120,\"nombreMaxParticipants\":50,"
                            + "\"nombreMaxBoucles\":24}");
            assertThat(creation.statusCode()).isEqualTo(201);
            idCourse = json.readTree(creation.body()).get("id").asString();
            assertThat(jdbc.queryForObject("select demarree_le from course where id = ?", Timestamp.class,
                    UUID.fromString(idCourse))).isNull();
            // Une inscription posée en SQL suffit à la règle « au moins un inscrit ».
            jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                    + "values (?, ?, ?, 1, ?, 'EN_COURSE')", UUID.randomUUID(), UUID.fromString(idCourse),
                    UUID.randomUUID(), UUID.randomUUID().toString());
            HttpResponse<String> reponse = api.requete("POST", CHEMIN + "/" + idCourse + "/demarrage",
                    patron.entetes(), null, null);
            assertThat(reponse.statusCode()).isEqualTo(200);
            demarree = json.readTree(reponse.body());
            assertThat(demarree.get("statut").asString()).isEqualTo("EN_COURS");
            assertThat(demarree.get("demarreeLe").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        }

        try (ConfigurableApplicationContext second = demarrer()) {
            assertThat(second.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            ClientHttp api = new ClientHttp(Integer.parseInt(second.getEnvironment().getProperty("local.server.port")));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> relue = api.requete("GET", CHEMIN + "/" + idCourse, patron.enteteLecture(), null,
                    null);
            assertThat(relue.statusCode()).isEqualTo(200);
            assertThat(json.readTree(relue.body())).isEqualTo(demarree);
            assertThat(json.readTree(api.requete("GET", CHEMIN + "/" + existante, patron.enteteLecture(), null, null)
                    .body()).get("demarreeLe").isNull()).isTrue();
        }

        verifierSchema(jdbc());
        verifierAbsenceDeContrainte(jdbc());
    }

    private void verifierSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course");
        Map<String, Object> colonne = jdbc.queryForMap("select data_type, is_nullable, column_default "
                + "from information_schema.columns where table_name = 'course' and column_name = 'demarree_le'");
        assertThat(colonne.get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(colonne.get("is_nullable")).isEqualTo("YES");
        assertThat(colonne.get("column_default")).isNull();
        assertThat(jdbc.queryForList("select 1 from course where nom = 'Existante' and demarree_le is null"))
                .hasSize(1);
    }

    private void verifierAbsenceDeContrainte(JdbcTemplate jdbc) {
        // Aucune contrainte ne lie demarree_le au statut : ces lignes sont acceptées par la base.
        Timestamp heure = Timestamp.from(Instant.parse("2026-10-09T14:03:27Z"));
        jdbc.update(INSERTION_COURSE, UUID.randomUUID(), "En cours sans heure", Date.valueOf("2026-10-09"), "EN_COURS",
                null);
        jdbc.update(INSERTION_COURSE, UUID.randomUUID(), "Terminée sans heure", Date.valueOf("2026-10-09"),
                "TERMINEE", null);
        jdbc.update(INSERTION_COURSE, UUID.randomUUID(), "Préparation avec heure", Date.valueOf("2026-10-09"),
                "EN_PREPARATION", heure);
        List<String> contraintes = jdbc.queryForList(
                "select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'course'::regclass "
                        + "and contype = 'c'", String.class);
        assertThat(contraintes).noneMatch(definition -> definition.contains("demarree_le"));
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    private ConfigurableApplicationContext demarrer() {
        return new SpringApplicationBuilder(BackyardUltraTrackerApplication.class).run(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--backyard.admin-master.pseudo=Patron",
                "--backyard.admin-master.mot-de-passe=" + MOT_DE_PASSE_PATRON).toArray(String[]::new));
    }
}
