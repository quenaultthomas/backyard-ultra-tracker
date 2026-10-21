package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai.Resultat;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 4.10 : vainqueurs partagés et nombre max de boucles (CA4, CA5, CA7). Le planificateur est
 * désactivé par le socle des tests : le cas d'usage est appelé directement à l'instant du Clock de test. Le contrat
 * vient de la spec. Course X : Boucle de 1 min, 5 Boucles max, t0 = 14:00:00Z ; décision de la Boucle 1 = 14:01:15.000Z (fin 14:01:00 + grâce 15 s).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class VainqueursPartagesIntegrationTest {

    static final Instant DECISION_BOUCLE_1 = DecorAbandon.T0.plusSeconds(60 + DecorAbandon.GRACE_SECONDES);
    static final Instant DECISION_BOUCLE_2 = DecorAbandon.T0.plusSeconds(120 + DecorAbandon.GRACE_SECONDES);
    static final Resultat AUCUNE_DECISION = new Resultat(0, 0);
    static final List<String> CHAMPS = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur", "demarreeLe",
            "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle", "boucleMaxTerminee", "boucleEnAttenteDeDecision",
            "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "nombreAyantBoucle", "nombreEnBoucle", "inscritsEnCourse",
            "nombreAbandons", "inscritsEnAbandon", "nombreVainqueurs", "inscritsVainqueurs");
    static final List<String> CHAMPS_VAINQUEUR = List.of("inscriptionId", "dossard", "pseudo", "statut",
            "nombreBouclesTerminees", "nombreBouclesCorrigees");

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
    AbandonnerCoureursHorsDelai controler;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    DecorAbandon decor;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
    }

    @AfterEach
    void nettoyer() {
        decor.arreterJournal();
    }

    private Resultat controler(String course) {
        return controler.executer(UUID.fromString(course));
    }

    private void assertEtats(String course, String bruno, String chloe, String tom, String statutCourse) {
        assertThat(decor.etat(course, decor.idBruno)).as("Bruno").isEqualTo(bruno);
        assertThat(decor.etat(course, decor.idChloe)).as("Chloé").isEqualTo(chloe);
        assertThat(decor.etat(course, decor.idTom)).as("Tom").isEqualTo(tom);
        assertThat(decor.statutCourse(course)).as("statut de la Course").isEqualTo(statutCourse);
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(decor.json.readTree(reponse.body()).get("code").asString()).isEqualTo(code);
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : X (Bruno {}, Chloé {}, Tom {}) à 14:01:15.000Z : les trois VAINQUEUR sans motif ni Boucle, Course TERMINEE, demarree_le et Passages inchangés, Course Y non touchée ; 14:01:14.999Z : rien")
    void ca4_personne_n_a_boucle_trois_vainqueurs() throws Exception {
        String x = decor.courseXDemarree();
        String y = decor.courseYDemarree();
        Object demarreeLe = decor.jdbc.queryForObject("select demarree_le from course where id = ?", Timestamp.class,
                UUID.fromString(x));
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");
        decor.horloge.fixer(DECISION_BOUCLE_1.minusMillis(1));

        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);
        assertEtats(x, "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURS");

        decor.horloge.fixer(DECISION_BOUCLE_1);
        assertThat(controler(x)).isEqualTo(new Resultat(0, 3));
        assertThat(controler(y)).isEqualTo(AUCUNE_DECISION);

        assertEtats(x, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "VAINQUEUR:-:-", "TERMINEE");
        assertThat(decor.jdbc.queryForObject("select count(*) from inscription where statut = 'VAINQUEUR' "
                + "and (motif_abandon is not null or boucle_abandon is not null)", Integer.class)).isZero();
        assertThat(decor.etat(y, decor.idAlice)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(y)).isEqualTo("EN_COURS");
        assertThat(decor.jdbc.queryForObject("select demarree_le from course where id = ?", Timestamp.class,
                UUID.fromString(x))).isEqualTo(demarreeLe);
        assertThat(decor.jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
    }

    @Test
    @DisplayName("CA4 : un second appel, puis des appels plus tardifs, ne modifient rien : aucune écriture Hibernate, état identique")
    void ca4_second_appel_sans_ecriture() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DECISION_BOUCLE_1);
        assertThat(controler(x)).isEqualTo(new Resultat(0, 3));
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long misesAJour = statistiques.getEntityUpdateCount();
        long insertions = statistiques.getEntityInsertCount();
        long suppressions = statistiques.getEntityDeleteCount();
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList("select * from course order by id");
        decor.demarrerJournal();

        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);
        decor.horloge.fixer(DECISION_BOUCLE_1.plusMillis(1));
        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);
        decor.horloge.fixer(DECISION_BOUCLE_1.plusSeconds(600));
        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);

        assertThat(statistiques.getEntityUpdateCount()).isEqualTo(misesAJour);
        assertThat(statistiques.getEntityInsertCount()).isEqualTo(insertions);
        assertThat(statistiques.getEntityDeleteCount()).isEqualTo(suppressions);
        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(decor.lignesFinDeCourse()).isEmpty();
        assertThat(decor.lignesAbandonAutomatique()).isEmpty();
    }

    @Test
    @DisplayName("CA4 : W (M = 2) avec Bruno {1, 2}, Chloé {1, 2}, Tom {1} à 14:02:15.000Z : Bruno et Chloé VAINQUEUR, Tom ABANDON HORS_DELAI Boucle 2, Course TERMINEE ; à 14:01:15.000Z (k < M) rien")
    void ca4_derniere_boucle_autorisee() throws Exception {
        String w = decor.courseDemarreeAvecMaxBoucles("Course W", 2);
        decor.poserPassage(w, decor.idBruno, 1);
        decor.poserPassage(w, decor.idChloe, 1);
        decor.poserPassage(w, decor.idTom, 1);
        decor.horloge.fixer(DECISION_BOUCLE_1);
        assertThat(controler(w)).isEqualTo(AUCUNE_DECISION);
        assertEtats(w, "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURS");
        decor.poserPassage(w, decor.idBruno, 2);
        decor.poserPassage(w, decor.idChloe, 2);
        decor.horloge.fixer(DECISION_BOUCLE_2);

        assertThat(controler(w)).isEqualTo(new Resultat(1, 2));

        assertEtats(w, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:2", "TERMINEE");
    }

    @Test
    @DisplayName("CA4 : rattrapage à 14:03:00.000Z (Bruno {1}, Chloé {1}, Tom {}) : Tom ABANDON Boucle 1, Bruno et Chloé VAINQUEUR, la Boucle 3 n'est pas examinée")
    void ca4_rattrapage() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(180));

        assertThat(controler(x)).isEqualTo(new Resultat(1, 2));

        assertEtats(x, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "TERMINEE");
    }

    @Test
    @DisplayName("CA4 : cohabitation abandon puis partage à 14:03:00.000Z (Bruno {1, 2}, Chloé {1, 2}, Tom {1}) : Tom ABANDON Boucle 2, Bruno et Chloé VAINQUEUR")
    void ca4_abandon_puis_partage() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.poserPassage(x, decor.idChloe, 2);
        decor.poserPassage(x, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(195));

        assertThat(controler(x)).isEqualTo(new Resultat(1, 2));

        assertEtats(x, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:2", "TERMINEE");
    }

    @Test
    @DisplayName("CA4 : Z (M = 1) à 14:01:15.000Z : {1}, {1}, {} -> deux vainqueurs et Tom ABANDON Boucle 1 ; {1}, {}, {} -> Bruno unique ; {}, {}, {} -> trois vainqueurs")
    void ca4_une_seule_boucle_autorisee() throws Exception {
        String deux = decor.courseDemarreeAvecMaxBoucles("Z deux", 1);
        decor.poserPassage(deux, decor.idBruno, 1);
        decor.poserPassage(deux, decor.idChloe, 1);
        String un = decor.courseDemarreeAvecMaxBoucles("Z un", 1);
        decor.poserPassage(un, decor.idBruno, 1);
        String aucun = decor.courseDemarreeAvecMaxBoucles("Z aucun", 1);
        decor.horloge.fixer(DECISION_BOUCLE_1);

        assertThat(controler(deux)).isEqualTo(new Resultat(1, 2));
        assertEtats(deux, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "TERMINEE");
        assertThat(controler(un)).isEqualTo(new Resultat(2, 1));
        assertEtats(un, "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "ABANDON:HORS_DELAI:1", "TERMINEE");
        assertThat(controler(aucun)).isEqualTo(new Resultat(0, 3));
        assertEtats(aucun, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "VAINQUEUR:-:-", "TERMINEE");
    }

    @Test
    @DisplayName("CA4 : Course EN_COURS sans Inscription EN_COURSE (état posé par SQL : depuis 4.11 l'abandon manuel du dernier coureur termine la Course) : aucune décision du contrôle, la Course reste EN_COURS")
    void ca4_aucune_inscription_en_course() throws Exception {
        String x = decor.courseXDemarree();
        decor.jdbc.update("update inscription set statut = 'ABANDON', motif_abandon = 'HORS_DELAI', "
                + "boucle_abandon = 1 where course_id = ?", UUID.fromString(x));
        decor.horloge.fixer(DECISION_BOUCLE_2);

        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);

        assertEtats(x, "ABANDON:HORS_DELAI:1", "ABANDON:HORS_DELAI:1", "ABANDON:HORS_DELAI:1", "EN_COURS");
    }

    @Test
    @DisplayName("CA4 : journal : une ligne « Fin de course ... 3 vainqueurs partagés. » (cas {}, {}, {}) ; une ligne « ... 2 vainqueurs partagés. » plus « Abandon automatique ... boucle 2, 1 inscription(s) » (cas W) ; « vainqueur unique. » ; sans pseudo, dossard, jeton ni nom de Course ; rien au second appel")
    void ca4_journal() throws Exception {
        String x = decor.courseXDemarree();
        String w = decor.courseDemarreeAvecMaxBoucles("Course W journal", 2);
        for (UUID compte : List.of(decor.idBruno, decor.idChloe)) {
            decor.poserPassage(w, compte, 1);
            decor.poserPassage(w, compte, 2);
        }
        decor.poserPassage(w, decor.idTom, 1);
        String unique = decor.courseDemarreeAvecMaxBoucles("Course unique journal", 5);
        decor.poserPassage(unique, decor.idBruno, 1);
        decor.horloge.fixer(DECISION_BOUCLE_2);
        decor.demarrerJournal();

        controler(x);
        controler(w);
        controler(unique);

        assertThat(decor.lignesFinDeCourse()).containsExactlyInAnyOrder(
                "Fin de course : course " + x + ", boucle 1, 3 vainqueurs partagés.",
                "Fin de course : course " + w + ", boucle 2, 2 vainqueurs partagés.",
                "Fin de course : course " + unique + ", boucle 1, vainqueur unique.");
        assertThat(decor.lignesAbandonAutomatique()).containsExactlyInAnyOrder(
                "Abandon automatique : course " + w + ", boucle 2, 1 inscription(s) en abandon.",
                "Abandon automatique : course " + unique + ", boucle 1, 2 inscription(s) en abandon.");
        List<String> jetons = decor.jdbc.queryForList("select jeton_qr from inscription", String.class);
        for (String ligne : decor.lignesFinDeCourse()) {
            assertThat(ligne).doesNotContain("Chloé", "Bruno", "Tom", "Course W", "Course unique", DecorAbandon.NOM_X)
                    .doesNotContain(jetons);
        }

        controler(x);
        controler(w);
        controler(unique);

        assertThat(decor.lignesFinDeCourse()).hasSize(3);
        assertThat(decor.lignesAbandonAutomatique()).hasSize(2);
    }

    @Test
    @DisplayName("CA4 : aucune migration ajoutée en 4.10 : databasechangelog se termine toujours par 0010")
    void ca4_aucune_migration() throws Exception {
        assertThat(decor.jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(AbandonAutomatiqueIntegrationTest.CHANGESETS);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : deux exécutions simultanées pour la même Course (Bruno {}, Chloé {}, Tom {}) à 14:01:15.000Z : trois vainqueurs une seule fois, une seule ligne « Fin de course », aucune exception")
    void ca5_deux_executions_simultanees() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DECISION_BOUCLE_1);
        decor.demarrerJournal();
        CyclicBarrier depart = new CyclicBarrier(2);
        Callable<Resultat> execution = () -> {
            depart.await(10, TimeUnit.SECONDS);
            return controler(x);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Resultat> premiere = pool.submit(execution);
            Future<Resultat> seconde = pool.submit(execution);

            List<Resultat> resultats = List.of(premiere.get(20, TimeUnit.SECONDS), seconde.get(20, TimeUnit.SECONDS));
            assertThat(resultats).containsExactlyInAnyOrder(new Resultat(0, 3), AUCUNE_DECISION);
        } finally {
            pool.shutdownNow();
        }

        assertEtats(x, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "VAINQUEUR:-:-", "TERMINEE");
        assertThat(decor.lignesFinDeCourse()).containsExactly(
                "Fin de course : course " + x + ", boucle 1, 3 vainqueurs partagés.");
    }

    @Test
    @DisplayName("CA5 : 20 essais de scan de Chloé (Boucle 1, âge 15500 ms (instant réel dans la Boucle 1)) simultané au contrôle à 14:01:15.300Z : soit 409 COURSE_TERMINEE (trois VAINQUEUR, aucun Passage de Chloé), soit 201 (Passage Boucle 1, puis Chloé vainqueur unique, Bruno et Tom ABANDON Boucle 1), jamais d'état mixte ni de 500")
    void ca5_scan_simultane_au_controle() throws Exception {
        String x = decor.courseXDemarree();
        String jeton = decor.jeton(x, decor.idChloe);
        UUID course = UUID.fromString(x);
        decor.horloge.fixer(DECISION_BOUCLE_1.plusMillis(300));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int essai = 1; essai <= 20; essai++) {
                decor.jdbc.update("delete from passage where inscription_id in "
                        + "(select id from inscription where course_id = ?)", course);
                decor.jdbc.update("update inscription set statut = 'EN_COURSE', motif_abandon = null, "
                        + "boucle_abandon = null where course_id = ?", course);
                decor.jdbc.update("update course set statut = 'EN_COURS' where id = ?", course);
                CyclicBarrier depart = new CyclicBarrier(2);
                Future<Resultat> controle = pool.submit(() -> {
                    depart.await(10, TimeUnit.SECONDS);
                    return controler(x);
                });
                Future<HttpResponse<String>> scan = pool.submit(() -> {
                    depart.await(10, TimeUnit.SECONDS);
                    return decor.scanner(decor.leo, x, jeton, UUID.randomUUID(), 15_500);
                });

                controle.get(20, TimeUnit.SECONDS);
                HttpResponse<String> reponse = scan.get(20, TimeUnit.SECONDS);

                String contexte = "essai " + essai + " : " + reponse.statusCode() + " " + reponse.body();
                int passagesChloe = decor.compterPassages(x, decor.idChloe);
                if (reponse.statusCode() == 201) {
                    assertThat(passagesChloe).as(contexte).isEqualTo(1);
                    controler(x); // contrôle suivant : Chloé est seule sur la Boucle 1
                    assertEtats(x, "ABANDON:HORS_DELAI:1", "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "TERMINEE");
                } else {
                    assertThat(reponse.statusCode()).as(contexte).isEqualTo(409);
                    assertThat(decor.json.readTree(reponse.body()).get("code").asString()).as(contexte)
                            .isEqualTo("COURSE_TERMINEE");
                    assertThat(passagesChloe).as(contexte).isZero();
                    assertEtats(x, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "VAINQUEUR:-:-", "TERMINEE");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- CA7

    /** X terminée : Bruno {1}, Chloé {1}, Tom {}, personne sur la Boucle 2. */
    private String courseTermineeDeuxVainqueurs() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(130 + DecorAbandon.GRACE_SECONDES));
        assertThat(controler(x)).isEqualTo(new Resultat(1, 2));
        return x;
    }

    @Test
    @DisplayName("CA7 : Patron lit le pilotage de X terminée à deux vainqueurs : 200, no-store, 24 champs, TERMINEE, inscritsEnCourse vide, 1 abandon, 3 inscrits, 2 vainqueurs de 6 champs triés par dossard à 1 Boucle chacun ; Nadia obtient le même corps")
    void ca7_pilotage_deux_vainqueurs() throws Exception {
        String x = courseTermineeDeuxVainqueurs();

        HttpResponse<String> reponse = decor.pilotage(decor.patron, x);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(24).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("statut").asString()).isEqualTo("TERMINEE");
        assertThat(corps.get("boucleCourante").isNull()).isTrue();
        assertThat(corps.get("inscritsEnCourse")).isEmpty();
        assertThat(corps.get("nombreAbandons").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(corps.get("inscritsEnAbandon")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Tom");
        assertThat(corps.get("nombreVainqueurs").asInt()).isEqualTo(2);
        JsonNode vainqueurs = corps.get("inscritsVainqueurs");
        assertThat(vainqueurs).hasSize(2);
        for (JsonNode vainqueur : vainqueurs) {
            assertThat(vainqueur.propertyNames()).hasSize(6).containsExactlyInAnyOrderElementsOf(CHAMPS_VAINQUEUR);
            assertThat(vainqueur.get("statut").asString()).isEqualTo("VAINQUEUR");
            assertThat(vainqueur.get("nombreBouclesTerminees").asInt()).isEqualTo(1);
        }
        assertThat(vainqueurs).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2);
        assertThat(vainqueurs).extracting(n -> n.get("pseudo").asString()).containsExactly("Bruno", "Chloé");
        assertThat(vainqueurs.get(0).get("inscriptionId").asString())
                .isEqualTo(decor.idInscription(x, decor.idBruno));

        HttpResponse<String> parNadia = decor.pilotage(decor.nadia, x);
        assertThat(parNadia.statusCode()).isEqualTo(200);
        assertThat(decor.json.readTree(parNadia.body()).get("inscritsVainqueurs")).isEqualTo(vainqueurs);
    }

    @Test
    @DisplayName("CA7 : trois vainqueurs aux nombreBouclesTerminees 2, 2, 1 posés par SQL : tri par dossard, valeurs distinctes conservées")
    void ca7_vainqueurs_a_boucles_differentes() throws Exception {
        UUID course = decor.insererCourse("Trois vainqueurs", "TERMINEE");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), course);
        int[] boucles = {1, 2, 3};
        UUID[] comptes = {decor.idTom, decor.idBruno, decor.idChloe};
        int[] passages = {1, 2, 2};
        for (int i = 0; i < 3; i++) {
            UUID inscription = decor.insererInscription(course, comptes[i], boucles[i], "VAINQUEUR");
            for (int boucle = 1; boucle <= passages[i]; boucle++) {
                decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), inscription, boucle,
                        Timestamp.from(DecorAbandon.T0.plusSeconds(60L * (boucle - 1) + 5)), "SCAN");
            }
        }

        JsonNode corps = decor.json.readTree(decor.pilotage(decor.patron, course.toString()).body());

        assertThat(corps.get("nombreVainqueurs").asInt()).isEqualTo(3);
        assertThat(corps.get("inscritsVainqueurs")).extracting(n -> n.get("dossard").asInt())
                .containsExactly(1, 2, 3);
        assertThat(corps.get("inscritsVainqueurs")).extracting(n -> n.get("nombreBouclesTerminees").asInt())
                .containsExactly(1, 2, 2);
        assertThat(corps.get("inscritsVainqueurs")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Tom", "Bruno", "Chloé");
    }

    @Test
    @DisplayName("CA7 : aucun jeton, identifiant de Compte ni de Passage dans le corps du pilotage")
    void ca7_aucune_fuite() throws Exception {
        String x = courseTermineeDeuxVainqueurs();

        String corps = decor.pilotage(decor.patron, x).body();

        assertThat(corps).doesNotContain(decor.jdbc.queryForList("select jeton_qr from inscription", String.class))
                .doesNotContain(decor.jdbc.queryForList("select id::text from passage", String.class))
                .doesNotContain("jetonQr").doesNotContain("compteId");
        for (UUID compte : List.of(decor.idAlice, decor.idBruno, decor.idChloe, decor.idTom, decor.idLeo)) {
            assertThat(corps).doesNotContain(compte.toString());
        }
    }

    @Test
    @DisplayName("CA7 : anonyme 401, Léo et Alice 403 ACCES_REFUSE, Course EN_PREPARATION 409 COURSE_NON_DEMARREE, Course inconnue 404 COURSE_INTROUVABLE")
    void ca7_erreurs() throws Exception {
        String x = courseTermineeDeuxVainqueurs();
        String preparation = decor.creerCourse("En préparation", 1, 5);

        HttpResponse<String> anonyme = decor.api.requete("GET", DecorAbandon.CHEMIN + "/" + x + "/pilotage", Map.of(),
                null, null);

        assertErreur(anonyme, 401, "NON_AUTHENTIFIE");
        for (var session : List.of(decor.leo, decor.alice)) {
            assertErreur(decor.pilotage(session, x), 403, "ACCES_REFUSE");
        }
        assertErreur(decor.pilotage(decor.patron, preparation), 409, "COURSE_NON_DEMARREE");
        assertErreur(decor.pilotage(decor.patron, "00000000-0000-0000-0000-000000000000"), 404,
                "COURSE_INTROUVABLE");
    }

    @Test
    @DisplayName("CA7 : le nombre de requêtes SQL du pilotage est identique avec 2 puis 10 vainqueurs (pas de N+1)")
    void ca7_nombre_de_requetes_constant() throws Exception {
        UUID petite = courseTermineeAvecVainqueurs("Petite", 2);
        UUID grande = courseTermineeAvecVainqueurs("Grande", 10);
        assertThat(decor.pilotage(decor.patron, petite.toString()).statusCode()).isEqualTo(200); // réchauffe

        long avecPeu = requetesPourUnPilotage(petite);
        long avecBeaucoup = requetesPourUnPilotage(grande);

        assertThat(avecPeu).isPositive();
        assertThat(avecBeaucoup).isEqualTo(avecPeu);
        JsonNode corps = decor.json.readTree(decor.pilotage(decor.patron, grande.toString()).body());
        assertThat(corps.get("inscritsVainqueurs")).hasSize(10);
        assertThat(corps.get("nombreVainqueurs").asInt()).isEqualTo(10);
    }

    private long requetesPourUnPilotage(UUID idCourse) throws Exception {
        var statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();
        assertThat(decor.pilotage(decor.patron, idCourse.toString()).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    private UUID courseTermineeAvecVainqueurs(String nom, int vainqueurs) {
        UUID course = decor.insererCourse(nom, "TERMINEE");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), course);
        for (int i = 1; i <= vainqueurs; i++) {
            UUID compte = decor.insererCompte("V" + nom.charAt(0) + i, "COUREUR");
            UUID inscription = decor.insererInscription(course, compte, i, "VAINQUEUR");
            decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), inscription, 1,
                    Timestamp.from(DecorAbandon.T0.plusSeconds(5)), "SCAN");
        }
        return course;
    }

    @Test
    @DisplayName("CA7 : le GET ne modifie aucune ligne et ne produit aucune ligne INFO")
    void ca7_lecture_seule_et_silencieuse() throws Exception {
        String x = courseTermineeDeuxVainqueurs();
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList("select * from course order by id");
        decor.demarrerJournal();

        assertThat(decor.pilotage(decor.patron, x).statusCode()).isEqualTo(200);

        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(decor.jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(decor.evenements()).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(
                ch.qos.logback.classic.Level.INFO)).isTrue());
    }

    @Test
    @DisplayName("CA7 : Course terminée à vainqueurs partagés : scan neuf d'un vainqueur puis d'un abandonné -> 409 COURSE_TERMINEE sans propriété d'extension et sans Passage créé ; rejeu d'un identifiantScan antérieur -> 200")
    void ca7_scan_refuse_apres_la_fin() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
        UUID scanBruno = UUID.randomUUID();
        HttpResponse<String> creation = decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), scanBruno, 0);
        assertThat(creation.statusCode()).as(creation.body()).isEqualTo(201);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idChloe), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(201);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(130 + DecorAbandon.GRACE_SECONDES)); // personne sur la Boucle 2 : Bruno et Chloé partagés
        assertThat(controler(x)).isEqualTo(new Resultat(1, 2));
        assertEtats(x, "VAINQUEUR:-:-", "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "TERMINEE");
        int passagesAvant = decor.jdbc.queryForObject("select count(*) from passage", Integer.class);

        for (UUID compte : List.of(decor.idBruno, decor.idChloe, decor.idTom)) {
            HttpResponse<String> refus = decor.scanner(decor.leo, x, decor.jeton(x, compte), UUID.randomUUID(), 0);

            assertThat(refus.statusCode()).as(refus.body()).isEqualTo(409);
            assertThat(refus.headers().firstValue("content-type")).hasValueSatisfying(
                    c -> assertThat(c).contains("application/problem+json"));
            JsonNode corps = decor.json.readTree(refus.body());
            assertThat(corps.get("code").asString()).isEqualTo("COURSE_TERMINEE");
            assertThat(corps.get("detail").asString()).isEqualTo("La course est terminée.");
            assertThat(corps.propertyNames()).doesNotContain("dossard", "pseudo", "statut");
        }
        assertThat(decor.jdbc.queryForObject("select count(*) from passage", Integer.class)).isEqualTo(passagesAvant);

        HttpResponse<String> rejeu = decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), scanBruno, 0);
        assertThat(rejeu.statusCode()).as(rejeu.body()).isEqualTo(200);
        assertThat(decor.json.readTree(rejeu.body())).isEqualTo(decor.json.readTree(creation.body()));
        assertThat(decor.compterPassages(x, decor.idBruno)).isEqualTo(1);
    }
}
