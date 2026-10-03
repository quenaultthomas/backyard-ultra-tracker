package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.courses.OctetsDeLogo;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 2.3, CA18 : changeset 0005-logo-course sur base vierge (colonnes, contraintes, cascade) puis
 * redémarrage du contexte sur la même base (ddl-auto=validate, logo relu octet pour octet avec le même ETag).
 */
@Testcontainers
class LogoCourseSchemaRedemarrageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String CHEMIN = "/api/administration/courses";
    static final String INSERTION_LOGO = "insert into logo_course (course_id, contenu, type_mime, taille_octets, "
            + "empreinte) values (?, ?, ?, ?, ?)";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA18 : base vierge migrée, changesets 0002 à 0006 (2.4 RG14), table logo_course typée (bytea), contraintes refusant les insertions incohérentes, cascade, puis redémarrage avec le logo relu à l'identique")
    void ca18_schema_contraintes_et_redemarrage() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        byte[] png = OctetsDeLogo.png(OctetsDeLogo.motif(500, 11));
        String id;
        String etag;
        try (ConfigurableApplicationContext premier = demarrer()) {
            assertThat(premier.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            ClientHttp api = new ClientHttp(port(premier));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> creation = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                    "{\"nom\":\"Backyard des Crêtes\",\"date\":\"" + LocalDate.now().plusDays(30)
                            + "\",\"distanceBoucleMetres\":6706,\"dureeBoucleMinutes\":60,"
                            + "\"denivelePositifBoucleMetres\":120,\"nombreMaxParticipants\":50,"
                            + "\"nombreMaxBoucles\":24}");
            assertThat(creation.statusCode()).isEqualTo(201);
            id = json.readTree(creation.body()).get("id").asString();
            String frontiere = "----f" + UUID.randomUUID();
            byte[] corps = OctetsDeLogo.concatener(
                    ("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"fichier\"; filename=\"l.png\"\r\n"
                            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8), png,
                    ("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
            Map<String, String> entetes = patron.entetes();
            HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port(premier)
                            + CHEMIN + "/" + id + "/logo"))
                    .header("Content-Type", "multipart/form-data; boundary=" + frontiere)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(corps));
            entetes.forEach(requete::header);
            assertThat(http.send(requete.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            etag = lireEtag(http, port(premier), id);
        }

        try (ConfigurableApplicationContext second = demarrer()) {
            assertThat(second.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            HttpResponse<byte[]> lecture = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                    + port(second) + "/api/courses/" + id + "/logo")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(lecture.statusCode()).isEqualTo(200);
            assertThat(lecture.body()).isEqualTo(png);
            assertThat(lecture.headers().firstValue("etag")).hasValue(etag);
            assertThat(etag).isEqualTo("\"" + OctetsDeLogo.sha256(png) + "\"");
        }

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        verifierSchema(jdbc);
        verifierContraintes(jdbc, UUID.fromString(id));
    }

    private void verifierSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                "0006-affectation-benevole");
        List<Map<String, Object>> colonnes = jdbc.queryForList("select column_name, data_type, "
                + "character_maximum_length, is_nullable from information_schema.columns "
                + "where table_name = 'logo_course' order by column_name");
        assertThat(colonnes).extracting(c -> c.get("column_name") + ":" + c.get("data_type") + ":"
                + c.get("character_maximum_length") + ":" + c.get("is_nullable")).containsExactlyInAnyOrder(
                "course_id:uuid:null:NO", "contenu:bytea:null:NO", "type_mime:character varying:20:NO",
                "taille_octets:integer:null:NO", "empreinte:character varying:64:NO");
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid = 'logo_course'::regclass",
                String.class)).contains("pk_logo_course", "fk_logo_course_course", "ck_logo_course_type_mime",
                "ck_logo_course_taille_coherente");
    }

    private void verifierContraintes(JdbcTemplate jdbc, UUID idCourse) {
        UUID autre = UUID.randomUUID();
        jdbc.update("insert into course (id, nom, date_course, statut, distance_boucle_metres, "
                + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, "
                + "nombre_max_boucles) values (?, 'Autre', ?, 'EN_PREPARATION', 400, 60, 0, 10, 5)", autre,
                Date.valueOf(LocalDate.now().plusDays(10)));
        jdbc.update("delete from logo_course");
        byte[] contenu = OctetsDeLogo.png();
        String empreinte = OctetsDeLogo.sha256(contenu);

        assertThatThrownBy(() -> jdbc.update(INSERTION_LOGO, autre, new byte[0], "image/png", 0, empreinte))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(INSERTION_LOGO, autre, contenu, "image/gif", contenu.length, empreinte))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_logo_course_type_mime");
        assertThatThrownBy(() -> jdbc.update(INSERTION_LOGO, autre, contenu, "image/png", contenu.length + 1,
                empreinte)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_logo_course_taille_coherente");
        assertThatThrownBy(() -> jdbc.update(INSERTION_LOGO, UUID.randomUUID(), contenu, "image/png",
                contenu.length, empreinte)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_logo_course_course");

        assertThat(jdbc.update(INSERTION_LOGO, autre, contenu, "image/png", contenu.length, empreinte)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(INSERTION_LOGO, autre, contenu, "image/png", contenu.length, empreinte))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("pk_logo_course");

        jdbc.update("delete from course where id = ?", autre);
        assertThat(jdbc.queryForObject("select count(*) from logo_course where course_id = ?", Integer.class, autre))
                .isZero();
        assertThat(idCourse).isNotNull();
    }

    private static String lireEtag(HttpClient http, int port, String id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/courses/" + id
                + "/logo")).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).headers().firstValue("etag")
                .orElseThrow();
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

    private static int port(ConfigurableApplicationContext contexte) {
        return Integer.parseInt(contexte.getEnvironment().getProperty("local.server.port"));
    }
}
