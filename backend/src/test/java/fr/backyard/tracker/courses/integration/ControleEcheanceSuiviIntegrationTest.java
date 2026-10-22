package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 7.2, planificateur activé : CA7 (suivi et délai de grâce à travers cache et planificateur),
 * CA17 (lecture des seules Courses EN_COURS) et CA18 (aucun verrou ni lecture de Passages entre deux échéances).
 * Boucles de 60 min (grâce 3 min), départ t0 = 14:00:00Z, tick réel d'une seconde sur le Clock de test.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.abandon-automatique.actif=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@DirtiesContext
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class ControleEcheanceSuiviIntegrationTest {

    static final Instant T0 = DecorAbandon.T0;
    static final Duration DELAI = Duration.ofSeconds(8);

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
    DepotCourses depotCourses;

    DecorAbandon decor;
    JournalApplicatif journal;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
    }

    @AfterEach
    void nettoyer() {
        if (journal != null) {
            journal.fermer();
        }
    }

    private void a(long millisecondesApresT0) {
        horloge.fixer(T0.plusMillis(millisecondesApresT0));
    }

    /** Course de 60 min démarrée à t0 : Alice et Bruno inscrits, Léo affecté ; {@code autres} inscrits en plus. */
    private String courseDemarree(String nom, int autres) throws Exception {
        String course = decor.creerCourse(nom, 60, 5);
        assertThat(decor.affecter(course, decor.idLeo).statusCode()).isEqualTo(200);
        assertThat(decor.sinscrire(decor.alice, course).statusCode()).isEqualTo(201);
        assertThat(decor.sinscrire(decor.bruno, course).statusCode()).isEqualTo(201);
        for (int i = 0; i < autres; i++) {
            UUID compte = decor.insererCompte("Autre" + i, "COUREUR");
            decor.insererInscription(UUID.fromString(course), compte, 100 + i, "EN_COURSE");
        }
        horloge.fixer(T0);
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + course + "/demarrage",
                decor.patron.entetes(), null, null).statusCode()).isEqualTo(200);
        return course;
    }

    private JsonNode suivi(String course) throws Exception {
        HttpResponse<String> reponse = decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of(), null,
                null);
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        return decor.json.readTree(reponse.body());
    }

    private void scannerAlice(String course) throws Exception {
        a(10 * 60_000L);
        HttpResponse<String> scan = decor.scanner(decor.leo, course, decor.jeton(course, decor.idAlice),
                UUID.randomUUID(), 0);
        assertThat(scan.statusCode()).as(scan.body()).isEqualTo(201);
    }

    private boolean terminee(String course) {
        return "TERMINEE".equals(decor.statutCourse(course));
    }

    @Test
    @DisplayName("CA7 : boucleEnAttenteDeDecision et Boucle courante recalculées malgré une lecture en cache ; à 11:03:01 le planificateur tranche, 1 s plus tard le suivi montre B en abandon HORS_DELAI, A vainqueur, Course TERMINEE")
    void ca7_suivi_frais_pendant_la_grace_puis_decision_visible() throws Exception {
        String x = courseDemarree("Backyard cache et grace", 0);
        scannerAlice(x);
        a(3_599_700);
        JsonNode avant = suivi(x);
        assertThat(avant.get("boucleCourante").asInt()).isEqualTo(1);
        assertThat(avant.get("boucleEnAttenteDeDecision").isNull()).isTrue();

        a(3_600_300);
        JsonNode pendantLaGrace = suivi(x);
        assertThat(pendantLaGrace.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(pendantLaGrace.get("boucleEnAttenteDeDecision").asInt()).isEqualTo(1);

        a(3_630_000);
        JsonNode apresTrenteSecondes = suivi(x);
        assertThat(apresTrenteSecondes.get("boucleEnAttenteDeDecision").asInt()).isEqualTo(1);
        assertThat(apresTrenteSecondes.get("coureursEnCourse")).hasSize(2);

        a(3_781_000);
        assertThat(DecorAbandon.attendre(() -> terminee(x), DELAI)).as("décision du planificateur").isTrue();
        a(3_782_200);
        JsonNode apres = suivi(x);
        assertThat(apres.get("statut").asString()).isEqualTo("TERMINEE");
        assertThat(apres.get("abandons")).hasSize(1);
        assertThat(apres.get("abandons").get(0).get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(apres.get("abandons").get(0).get("motifAbandon").asString()).isEqualTo("HORS_DELAI");
        assertThat(apres.get("abandons").get(0).get("boucleAbandon").asInt()).isEqualTo(1);
        assertThat(apres.get("vainqueurs")).hasSize(1);
        assertThat(apres.get("vainqueurs").get(0).get("pseudo").asString()).isEqualTo("Alice");
        assertThat(apres.get("boucleEnAttenteDeDecision").isNull()).isTrue();
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idAlice)).isEqualTo("VAINQUEUR:-:-");
    }

    @Test
    @DisplayName("CA17 : 3 Courses EN_PREPARATION, 2 TERMINEE et 2 EN_COURS : enCours() ne renvoie que les 2 EN_COURS par une requête filtrée sur le statut ; une Course passée TERMINEE n'est plus renvoyée")
    void ca17_seules_les_courses_en_cours_sont_lues() {
        for (int i = 0; i < 3; i++) {
            decor.insererCourse("Prep" + i, "EN_PREPARATION");
        }
        for (int i = 0; i < 2; i++) {
            UUID terminee = decor.insererCourse("Terminee" + i, "TERMINEE");
            decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(T0), terminee);
        }
        UUID premiere = decor.insererCourse("EnCours0", "EN_COURS");
        UUID seconde = decor.insererCourse("EnCours1", "EN_COURS");
        for (UUID id : List.of(premiere, seconde)) {
            decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(T0), id);
        }
        a(10_000);
        journal = JournalApplicatif.demarrer().niveau("org.hibernate.SQL", Level.DEBUG);

        List<Course> lues = depotCourses.enCours();

        assertThat(lues).extracting(Course::id).containsExactlyInAnyOrder(premiere, seconde);
        assertThat(lues).allMatch(course -> course.statut() == StatutCourse.EN_COURS);
        assertThat(requetesSql()).anySatisfy(sql -> assertThat(sql).contains("from course").containsPattern(
                "where\\s+\\w+\\.statut\\s*=\\s*\\?"));
        decor.jdbc.update("update course set statut = 'TERMINEE' where id = ?", seconde);
        assertThat(depotCourses.enCours()).extracting(Course::id).containsExactly(premiere);
    }

    @Test
    @DisplayName("CA18 : 30 Inscriptions, 10 ticks du planificateur dans la Boucle 1 : aucun verrou FOR UPDATE ni lecture de Passage ; à l'échéance de 7.1, A vainqueur, B ABANDON HORS_DELAI Boucle 1, Course TERMINEE")
    void ca18_aucun_verrou_ni_lecture_de_passages_entre_deux_echeances() throws Exception {
        String x = courseDemarree("Backyard allegee", 28);
        scannerAlice(x);
        a(30 * 60_000L);
        Thread.sleep(1_500);
        journal = JournalApplicatif.demarrer().niveau("org.hibernate.SQL", Level.DEBUG);

        Thread.sleep(10_500);

        List<String> sql = requetesSql();
        assertThat(sql).as("le planificateur lit les Courses en cours à chaque tick").isNotEmpty();
        assertThat(sql).noneMatch(requete -> requete.toLowerCase(Locale.ROOT).contains("for update"));
        assertThat(sql).noneMatch(requete -> requete.toLowerCase(Locale.ROOT).contains("from passage"));
        assertThat(sql).noneMatch(requete -> requete.toLowerCase(Locale.ROOT).contains("from inscription"));

        a(3_781_000);
        assertThat(DecorAbandon.attendre(() -> terminee(x), DELAI)).isTrue();
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idAlice)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
    }

    private List<String> requetesSql() {
        return journal.evenements().stream().filter(e -> "org.hibernate.SQL".equals(e.getLoggerName()))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
