package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.OctetsDeLogo;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 7.2 (RG10, fin des N+1) : CA19 et CA20, serveur réel. Le nombre d'instructions SQL de
 * GET /api/coureur/courses et GET /api/coureur/inscriptions ne dépend ni du nombre de Courses ni du nombre
 * d'Inscriptions (au plus 4, sessions comprises) ; le corps reste celui du contrat (ordre, complétude, inscription).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class ListesCoureurRequetesConstantesIntegrationTest {

    static final int MAXIMUM_REQUETES = 4;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder encodeur;

    @Autowired
    InitialiserAdminMaster initialiserAdminMaster;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @Autowired
    CoursesIntegrationTest.HorlogeMutable horloge;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    DecorAbandon decor;
    UUID autre;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
        autre = decor.insererCompte("Autre", "COUREUR");
    }

    private UUID courseOuverte(int rang, boolean avecInscrit, boolean avecLogo) {
        UUID id = decor.insererCourse("Course " + (char) ('A' + rang), "EN_PREPARATION");
        decor.jdbc.update("update course set date_course = ? where id = ?",
                Date.valueOf(LocalDate.parse("2026-11-01").plusDays(rang % 4)), id);
        if (avecInscrit) {
            decor.insererInscription(id, autre, 1, "EN_COURSE");
        }
        if (avecLogo) {
            byte[] octets = OctetsDeLogo.png();
            decor.jdbc.update("insert into logo_course (course_id, contenu, type_mime, taille_octets, empreinte) "
                    + "values (?, ?, 'image/png', ?, ?)", id, octets, octets.length, OctetsDeLogo.sha256(octets));
        }
        return id;
    }

    private long instructions(String chemin) throws Exception {
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        decor.api.requete("GET", chemin, decor.alice.enteteLecture(), null, null);
        long avant = statistiques.getPrepareStatementCount();
        HttpResponse<String> reponse = decor.api.requete("GET", chemin, decor.alice.enteteLecture(), null, null);
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    private JsonNode liste(String chemin) throws Exception {
        HttpResponse<String> reponse = decor.api.requete("GET", chemin, decor.alice.enteteLecture(), null, null);
        assertThat(reponse.statusCode()).isEqualTo(200);
        return decor.json.readTree(reponse.body());
    }

    @Test
    @DisplayName("CA19 : 1 puis 10 Courses ouvertes dont 5 avec Inscrits et logo : même nombre d'instructions SQL (<= 4), ordre, complétude et inscription du coureur conformes")
    void ca19_courses_ouvertes_en_nombre_constant_de_requetes() throws Exception {
        courseOuverte(0, true, true);
        long pourUne = instructions("/api/coureur/courses");

        List<UUID> ajoutees = new ArrayList<>();
        for (int i = 1; i < 10; i++) {
            ajoutees.add(courseOuverte(i, i % 2 == 1, i % 2 == 1));
        }
        UUID complete = ajoutees.get(0);
        decor.jdbc.update("update course set nombre_max_participants = 1 where id = ?", complete);
        UUID miennes = ajoutees.get(2);
        UUID monInscription = decor.insererInscription(miennes, decor.idAlice, 2, "EN_COURSE");
        long pourDix = instructions("/api/coureur/courses");

        assertThat(pourUne).isLessThanOrEqualTo(MAXIMUM_REQUETES);
        assertThat(pourDix).as("indépendant du nombre de Courses").isEqualTo(pourUne);
        JsonNode corps = liste("/api/coureur/courses");
        assertThat(corps).hasSize(10);
        List<String> attendu = decor.jdbc.queryForList(
                "select id::text from course order by date_course desc, lower(nom), id::text", String.class);
        List<String> obtenu = new ArrayList<>();
        corps.forEach(c -> obtenu.add(c.get("id").asString()));
        assertThat(obtenu).as("date décroissante, nom sans casse").isEqualTo(attendu);
        for (JsonNode ligne : corps) {
            UUID id = UUID.fromString(ligne.get("id").asString());
            assertThat(ligne.get("complete").asBoolean()).as(ligne.get("nom").asString()).isEqualTo(id.equals(complete));
            if (id.equals(miennes)) {
                assertThat(ligne.get("monInscription").get("id").asString()).isEqualTo(monInscription.toString());
                assertThat(ligne.get("monInscription").get("dossard").asInt()).isEqualTo(2);
            } else {
                assertThat(ligne.get("monInscription") == null || ligne.get("monInscription").isNull()).isTrue();
            }
            boolean avecLogo = decor.jdbc.queryForObject("select count(*) from logo_course where course_id = ?",
                    Integer.class, id) > 0;
            assertThat(ligne.get("logoUrl") != null && !ligne.get("logoUrl").isNull()).isEqualTo(avecLogo);
        }
    }

    @Test
    @DisplayName("CA20 : 1 puis 8 Inscriptions du coureur : même nombre d'instructions SQL (<= 4), dossards et jetons QR conformes")
    void ca20_mes_inscriptions_en_nombre_constant_de_requetes() throws Exception {
        UUID premiere = courseOuverte(0, true, true);
        decor.insererInscription(premiere, decor.idAlice, 2, "EN_COURSE");
        long pourUne = instructions("/api/coureur/inscriptions");

        for (int i = 1; i < 8; i++) {
            UUID course = courseOuverte(i, i % 2 == 0, i % 2 == 0);
            decor.insererInscription(course, decor.idAlice, 10 + i, "EN_COURSE");
        }
        long pourHuit = instructions("/api/coureur/inscriptions");

        assertThat(pourUne).isLessThanOrEqualTo(MAXIMUM_REQUETES);
        assertThat(pourHuit).as("indépendant du nombre d'Inscriptions").isEqualTo(pourUne);
        JsonNode corps = liste("/api/coureur/inscriptions");
        assertThat(corps).hasSize(8);
        for (JsonNode ligne : corps) {
            String attenduDossard = decor.jdbc.queryForObject(
                    "select dossard::text from inscription where id = ?", String.class,
                    UUID.fromString(ligne.get("id").asString()));
            String jeton = decor.jdbc.queryForObject("select jeton_qr from inscription where id = ?", String.class,
                    UUID.fromString(ligne.get("id").asString()));
            assertThat(ligne.get("dossard").asString()).isEqualTo(attenduDossard);
            assertThat(ligne.get("jetonQr").asString()).isEqualTo(jeton);
            assertThat(ligne.get("courseNom").asString()).isEqualTo(decor.jdbc.queryForObject(
                    "select nom from course where id = ?", String.class, UUID.fromString(ligne.get("courseId").asString())));
        }
    }
}
