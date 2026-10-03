package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 2.4, CA13 : changeset 0006-affectation-benevole sur base vierge (colonnes, clé primaire
 * composite, clé étrangère en cascade, absence de clé vers compte), puis redémarrage du contexte avec les
 * affectations relues à l'identique.
 */
@Testcontainers
class AffectationBenevolesSchemaRedemarrageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String CHEMIN = "/api/administration/courses";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, 'empreinte-de-test', 'BENEVOLE', ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, 'Autre', ?, 'EN_PREPARATION', 400, 60, 0, 10, 5)";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA13 : base vierge migrée (0002 à 0006), colonnes et clé primaire composite, doublon et course inexistante refusés, benevole_id libre, cascade, ddl-auto=validate, affectations relues après redémarrage")
    void ca13_schema_contraintes_et_redemarrage() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        UUID leo = UUID.randomUUID();
        UUID marc = UUID.randomUUID();
        String idCourse;
        String fiche;
        try (ConfigurableApplicationContext premier = demarrer()) {
            assertThat(premier.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            JdbcTemplate jdbc = jdbc();
            jdbc.update(INSERTION_COMPTE, leo, "Léo", "léo", Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
            jdbc.update(INSERTION_COMPTE, marc, "Marc", "marc", Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
            ClientHttp api = new ClientHttp(Integer.parseInt(premier.getEnvironment().getProperty("local.server.port")));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> creation = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                    "{\"nom\":\"Backyard des Crêtes\",\"date\":\"" + LocalDate.now().plusDays(30)
                            + "\",\"distanceBoucleMetres\":6706,\"dureeBoucleMinutes\":60,"
                            + "\"denivelePositifBoucleMetres\":120,\"nombreMaxParticipants\":50,"
                            + "\"nombreMaxBoucles\":24}");
            assertThat(creation.statusCode()).isEqualTo(201);
            idCourse = json.readTree(creation.body()).get("id").asString();
            HttpResponse<String> affectation = api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles",
                    patron.entetes(), "application/json",
                    "{\"benevoleIds\":[\"" + leo + "\",\"" + marc + "\"]}");
            assertThat(affectation.statusCode()).isEqualTo(200);
            fiche = affectation.body();
        }

        try (ConfigurableApplicationContext second = demarrer()) {
            assertThat(second.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            ClientHttp api = new ClientHttp(Integer.parseInt(second.getEnvironment().getProperty("local.server.port")));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> relue = api.requete("GET", CHEMIN + "/" + idCourse, patron.enteteLecture(), null, null);
            assertThat(relue.statusCode()).isEqualTo(200);
            JsonNode attendue = json.readTree(fiche);
            assertThat(json.readTree(relue.body())).isEqualTo(attendue);
            assertThat(attendue.get("benevoleIds")).hasSize(2);
        }

        verifierSchema(jdbc());
        verifierContraintes(jdbc(), UUID.fromString(idCourse), leo);
    }

    private void verifierSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole");
        List<Map<String, Object>> colonnes = jdbc.queryForList("select column_name, data_type, is_nullable "
                + "from information_schema.columns where table_name = 'affectation_benevole'");
        assertThat(colonnes).extracting(c -> c.get("column_name") + ":" + c.get("data_type") + ":"
                + c.get("is_nullable")).containsExactlyInAnyOrder("course_id:uuid:NO", "benevole_id:uuid:NO");
        assertThat(jdbc.queryForList("select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid "
                + "and a.attnum = any(i.indkey) where i.indrelid = 'affectation_benevole'::regclass "
                + "and i.indisprimary order by a.attname", String.class)).containsExactly("benevole_id", "course_id");
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid = 'affectation_benevole'::regclass",
                String.class)).containsExactlyInAnyOrder("pk_affectation_benevole", "fk_affectation_benevole_course");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename = 'affectation_benevole'",
                String.class)).contains("ix_affectation_benevole_benevole");
        assertThat(jdbc.queryForObject("select confdeltype from pg_constraint "
                + "where conname = 'fk_affectation_benevole_course'", String.class)).isEqualTo("c");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype = 'f' "
                + "and conrelid = 'affectation_benevole'::regclass and confrelid = 'compte'::regclass",
                Integer.class)).isZero();
    }

    private void verifierContraintes(JdbcTemplate jdbc, UUID idCourse, UUID leo) {
        assertThatThrownBy(() -> jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)",
                UUID.randomUUID(), leo)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_affectation_benevole_course");
        assertThatThrownBy(() -> jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)",
                idCourse, leo)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_affectation_benevole");
        UUID sansCompte = UUID.randomUUID();
        jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", idCourse, sansCompte);
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where course_id = ?", Integer.class,
                idCourse)).isEqualTo(3);

        UUID autre = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, autre, Date.valueOf(LocalDate.now().plusDays(10)));
        jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", autre, leo);
        jdbc.update("delete from course where id = ?", idCourse);
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where course_id = ?", Integer.class,
                idCourse)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where course_id = ?", Integer.class,
                autre)).isEqualTo(1);
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
