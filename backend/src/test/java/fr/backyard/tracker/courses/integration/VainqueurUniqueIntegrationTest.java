package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai.Resultat;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
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
 * Contrat de l'incrément 4.9 : vainqueur unique (CA5, CA6, CA8, CA9). Le planificateur est désactivé par le socle des
 * tests : le cas d'usage est appelé directement à l'instant du Clock de test. Le contrat vient de la spec.
 * Course X : Boucle de 1 min, 5 Boucles max, t0 = 14:00:00Z ; fin de la Boucle 1 = 14:01:00.000Z.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class VainqueurUniqueIntegrationTest {

    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant FIN_BOUCLE_1 = DecorAbandon.T0.plusSeconds(60);
    static final Resultat AUCUNE_DECISION = new Resultat(0, false);
    static final List<String> CHAMPS = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur", "demarreeLe",
            "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle", "boucleMaxTerminee",
            "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "nombreAyantBoucle", "nombreEnBoucle", "inscritsEnCourse",
            "nombreAbandons", "inscritsEnAbandon", "nombreVainqueurs", "inscritsVainqueurs");
    static final List<String> CHAMPS_VAINQUEUR = List.of("inscriptionId", "dossard", "pseudo", "statut",
            "nombreBouclesTerminees");

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

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : à 14:01:00.000Z (Bruno {1}, Chloé {}, Tom {}) Bruno est VAINQUEUR sans motif ni Boucle, Chloé et Tom ABANDON HORS_DELAI Boucle 1, Course TERMINEE, demarree_le et Passages inchangés, Course Y non touchée")
    void ca5_vainqueur_unique_persiste() throws Exception {
        String x = decor.courseXDemarree();
        String y = decor.courseYDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");
        Object demarreeLe = decor.jdbc.queryForObject("select demarree_le from course where id = ?", Timestamp.class,
                UUID.fromString(x));
        decor.horloge.fixer(FIN_BOUCLE_1);

        Resultat resultat = controler(x);
        Resultat resultatY = controler(y);

        assertThat(resultat).isEqualTo(new Resultat(2, true));
        assertThat(resultatY).isEqualTo(AUCUNE_DECISION);
        assertEtats(x, "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "ABANDON:HORS_DELAI:1", "TERMINEE");
        assertThat(decor.etat(y, decor.idAlice)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(y)).isEqualTo("EN_COURS");
        assertThat(decor.jdbc.queryForObject("select demarree_le from course where id = ?", Timestamp.class,
                UUID.fromString(x))).isEqualTo(demarreeLe);
        assertThat(decor.jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
    }

    @Test
    @DisplayName("CA5 : un second appel (et un appel plus tardif) ne modifie rien : aucune écriture Hibernate, état identique")
    void ca5_second_appel_sans_ecriture() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(FIN_BOUCLE_1);
        assertThat(controler(x)).isEqualTo(new Resultat(2, true));
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long misesAJour = statistiques.getEntityUpdateCount();
        long insertions = statistiques.getEntityInsertCount();
        long suppressions = statistiques.getEntityDeleteCount();
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList("select * from course order by id");

        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);
        decor.horloge.fixer(FIN_BOUCLE_1.plusMillis(1));
        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);
        decor.horloge.fixer(FIN_BOUCLE_1.plusSeconds(300));
        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);

        assertThat(statistiques.getEntityUpdateCount()).isEqualTo(misesAJour);
        assertThat(statistiques.getEntityInsertCount()).isEqualTo(insertions);
        assertThat(statistiques.getEntityDeleteCount()).isEqualTo(suppressions);
        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
    }

    @Test
    @DisplayName("CA5 : à 14:00:59.999Z rien n'est décidé, à 14:01:00.000Z la décision est prise (instant exact de bascule)")
    void ca5_bascule_exacte() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(FIN_BOUCLE_1.minusMillis(1));

        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);
        assertEtats(x, "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURS");

        decor.horloge.fixer(FIN_BOUCLE_1);
        assertThat(controler(x)).isEqualTo(new Resultat(2, true));
        assertEtats(x, "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "ABANDON:HORS_DELAI:1", "TERMINEE");
    }

    @Test
    @DisplayName("CA5 : rattrapage à 14:03:00.000Z (Bruno {1, 2}, Chloé {1}, Tom {}) : Tom Boucle 1, Chloé Boucle 2, Bruno VAINQUEUR, Course TERMINEE, la Boucle 3 n'est pas examinée")
    void ca5_rattrapage_sur_plusieurs_boucles() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(180));

        assertThat(controler(x)).isEqualTo(new Resultat(2, true));

        assertEtats(x, "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:2", "ABANDON:HORS_DELAI:1", "TERMINEE");
    }

    @Test
    @DisplayName("CA5 : rattrapage d'un seul coup (Bruno {1}, N = 3) : la décision est prise à la Boucle 1, Chloé et Tom abandonnent sur la Boucle 1")
    void ca5_rattrapage_decision_a_la_premiere_boucle() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(180));

        assertThat(controler(x)).isEqualTo(new Resultat(2, true));

        assertEtats(x, "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "ABANDON:HORS_DELAI:1", "TERMINEE");
    }

    @Test
    @DisplayName("CA5 : Bruno {1}, Chloé {1}, Tom {1} puis personne sur la Boucle 2 à 14:02:00.000Z : aucune décision, Course EN_COURS, tous EN_COURSE")
    void ca5_personne_sur_la_boucle_2_aucune_decision() throws Exception {
        String x = decor.courseXDemarree();
        for (UUID compte : List.of(decor.idBruno, decor.idChloe, decor.idTom)) {
            decor.poserPassage(x, compte, 1);
        }
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(120));

        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);

        assertEtats(x, "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURS");
    }

    @Test
    @DisplayName("CA5 : personne n'a de Passage sur la Boucle 1 à 14:01:00.000Z : aucune décision, aucun vainqueur par défaut, Course EN_COURS")
    void ca5_personne_n_a_boucle_aucun_vainqueur_par_defaut() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(FIN_BOUCLE_1);
        decor.demarrerJournal();

        assertThat(controler(x)).isEqualTo(AUCUNE_DECISION);

        assertEtats(x, "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURSE:-:-", "EN_COURS");
        assertThat(decor.lignesFinDeCourse()).isEmpty();
    }

    @Test
    @DisplayName("CA5 : dernière Boucle autorisée (M = 2) à 14:02:00.000Z : {1, 2} et {1} -> vainqueur et abandon Boucle 2 ; {1, 2} et {1, 2} -> aucune décision, Course EN_COURS")
    void ca5_derniere_boucle_autorisee() throws Exception {
        String troisPuisDeux = decor.courseDemarreeAvecMaxBoucles("Dernière boucle trois puis deux", 2);
        decor.poserPassage(troisPuisDeux, decor.idBruno, 1);
        decor.poserPassage(troisPuisDeux, decor.idBruno, 2);
        decor.poserPassage(troisPuisDeux, decor.idChloe, 1);
        decor.poserPassage(troisPuisDeux, decor.idTom, 1);
        decor.poserPassage(troisPuisDeux, decor.idTom, 2);
        String deuxPassants = decor.courseDemarreeAvecMaxBoucles("Dernière boucle deux passants", 2);
        decor.poserPassage(deuxPassants, decor.idBruno, 1);
        decor.poserPassage(deuxPassants, decor.idBruno, 2);
        decor.poserPassage(deuxPassants, decor.idChloe, 1);
        decor.poserPassage(deuxPassants, decor.idChloe, 2);
        decor.poserPassage(deuxPassants, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(120));

        // deux passants sur la Boucle 2 (Bruno et Tom) : pas de vainqueur en 4.9 (vainqueurs partagés : 4.10)
        assertThat(controler(troisPuisDeux)).isEqualTo(new Resultat(1, false));
        assertEtats(troisPuisDeux, "EN_COURSE:-:-", "ABANDON:HORS_DELAI:2", "EN_COURSE:-:-", "EN_COURS");
        assertThat(controler(deuxPassants)).isEqualTo(new Resultat(1, false));
        assertEtats(deuxPassants, "EN_COURSE:-:-", "EN_COURSE:-:-", "ABANDON:HORS_DELAI:2", "EN_COURS");

        String unique = decor.courseDemarreeAvecMaxBoucles("Dernière boucle unique", 2);
        decor.poserPassage(unique, decor.idBruno, 1);
        decor.poserPassage(unique, decor.idBruno, 2);
        decor.poserPassage(unique, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(120)); // le démarrage de la Course a remis l'horloge à t0
        assertThat(controler(unique)).isEqualTo(new Resultat(2, true));
        assertEtats(unique, "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:2", "ABANDON:HORS_DELAI:1", "TERMINEE");
    }

    @Test
    @DisplayName("CA5 : le journal contient exactement une ligne « Fin de course » et une ligne « Abandon automatique » sans pseudo, dossard, jeton ni nom de Course, et aucune ligne au second appel")
    void ca5_journal() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(FIN_BOUCLE_1);
        decor.demarrerJournal();

        controler(x);

        assertThat(decor.lignesFinDeCourse()).containsExactly(
                "Fin de course : course " + x + ", boucle 1, vainqueur unique.");
        assertThat(decor.lignesAbandonAutomatique()).containsExactly(
                "Abandon automatique : course " + x + ", boucle 1, 2 inscription(s) en abandon.");
        List<String> jetons = decor.jdbc.queryForList("select jeton_qr from inscription", String.class);
        for (String ligne : List.of(decor.lignesFinDeCourse().get(0), decor.lignesAbandonAutomatique().get(0))) {
            assertThat(ligne).doesNotContain("Chloé", "Bruno", "Tom", DecorAbandon.NOM_X).doesNotContain(jetons);
        }

        controler(x);

        assertThat(decor.lignesFinDeCourse()).hasSize(1);
        assertThat(decor.lignesAbandonAutomatique()).hasSize(1);
    }

    @Test
    @DisplayName("CA5 : aucune migration ajoutée en 4.9 : databasechangelog se termine toujours par 0010, colonnes motif_abandon et boucle_abandon nulles pour un vainqueur")
    void ca5_aucune_migration() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(FIN_BOUCLE_1);
        controler(x);

        assertThat(decor.jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(AbandonAutomatiqueIntegrationTest.CHANGESETS);
        assertThat(decor.jdbc.queryForObject("select count(*) from inscription where statut = 'VAINQUEUR' "
                + "and (motif_abandon is not null or boucle_abandon is not null)", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : deux exécutions simultanées pour la même Course à 14:01:00.000Z : un seul vainqueur, une seule ligne « Fin de course », aucune exception")
    void ca6_deux_executions_simultanees() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(FIN_BOUCLE_1);
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
            assertThat(resultats).containsExactlyInAnyOrder(new Resultat(2, true), AUCUNE_DECISION);
        } finally {
            pool.shutdownNow();
        }

        assertEtats(x, "VAINQUEUR:-:-", "ABANDON:HORS_DELAI:1", "ABANDON:HORS_DELAI:1", "TERMINEE");
        assertThat(decor.jdbc.queryForObject("select count(*) from inscription where statut = 'VAINQUEUR'",
                Integer.class)).isEqualTo(1);
        assertThat(decor.lignesFinDeCourse()).hasSize(1);
    }

    @Test
    @DisplayName("CA6 : 20 essais de scan de Chloé (Boucle 1, âge 500 ms) simultané au contrôle à 14:01:00.300Z : soit 201 (Bruno et Chloé EN_COURSE, Course EN_COURS), soit 409 COURSE_TERMINEE (Bruno VAINQUEUR, Chloé ABANDON, aucun Passage de Chloé), jamais d'état mixte ni de 500")
    void ca6_scan_simultane_au_controle() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        String jeton = decor.jeton(x, decor.idChloe);
        UUID course = UUID.fromString(x);
        decor.horloge.fixer(FIN_BOUCLE_1.plusMillis(300));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int essai = 1; essai <= 20; essai++) {
                decor.jdbc.update("delete from passage where inscription_id in "
                        + "(select id from inscription where course_id = ? and compte_id <> ?)", course, decor.idBruno);
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
                    return decor.scanner(decor.leo, x, jeton, UUID.randomUUID(), 500);
                });

                controle.get(20, TimeUnit.SECONDS);
                HttpResponse<String> reponse = scan.get(20, TimeUnit.SECONDS);

                String contexte = "essai " + essai + " : " + reponse.statusCode() + " " + reponse.body();
                int passagesChloe = decor.compterPassages(x, decor.idChloe);
                if (reponse.statusCode() == 201) {
                    assertThat(passagesChloe).as(contexte).isEqualTo(1);
                    assertThat(decor.etat(x, decor.idBruno)).as(contexte).isEqualTo("EN_COURSE:-:-");
                    assertThat(decor.etat(x, decor.idChloe)).as(contexte).isEqualTo("EN_COURSE:-:-");
                    assertThat(decor.statutCourse(x)).as(contexte).isEqualTo("EN_COURS");
                } else {
                    assertThat(reponse.statusCode()).as(contexte).isEqualTo(409);
                    assertThat(decor.json.readTree(reponse.body()).get("code").asString()).as(contexte)
                            .isEqualTo("COURSE_TERMINEE");
                    assertThat(passagesChloe).as(contexte).isZero();
                    assertThat(decor.etat(x, decor.idBruno)).as(contexte).isEqualTo("VAINQUEUR:-:-");
                    assertThat(decor.etat(x, decor.idChloe)).as(contexte).isEqualTo("ABANDON:HORS_DELAI:1");
                    assertThat(decor.statutCourse(x)).as(contexte).isEqualTo("TERMINEE");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- CA8

    private String courseTermineeAvecBrunoVainqueur() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(FIN_BOUCLE_1.plusSeconds(10));
        assertThat(controler(x)).isEqualTo(new Resultat(2, true));
        return x;
    }

    @Test
    @DisplayName("CA8 : Patron lit le pilotage de X terminée : 200, no-store, exactement 23 champs (statut TERMINEE, boucleCourante null, inscritsEnCourse vide, 2 abandons, 3 inscrits) plus 1 vainqueur à 5 champs ; Nadia obtient le même corps")
    void ca8_pilotage_course_terminee() throws Exception {
        String x = courseTermineeAvecBrunoVainqueur();

        HttpResponse<String> reponse = decor.pilotage(decor.patron, x);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json").doesNotContain("problem"));
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(23).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("courseId").asString()).isEqualTo(x);
        assertThat(corps.get("statut").asString()).isEqualTo("TERMINEE");
        assertThat(corps.get("boucleCourante").isNull()).isTrue();
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(corps.get("inscritsEnCourse")).isEmpty();
        assertThat(corps.get("nombreAbandons").asInt()).isEqualTo(2);
        assertThat(corps.get("inscritsEnAbandon")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Chloé", "Tom");
        assertThat(corps.get("nombreVainqueurs").asInt()).isEqualTo(1);
        JsonNode vainqueurs = corps.get("inscritsVainqueurs");
        assertThat(vainqueurs).hasSize(1);
        JsonNode bruno = vainqueurs.get(0);
        assertThat(bruno.propertyNames()).hasSize(5).containsExactlyInAnyOrderElementsOf(CHAMPS_VAINQUEUR);
        assertThat(bruno.get("inscriptionId").asString()).isEqualTo(decor.idInscription(x, decor.idBruno));
        assertThat(bruno.get("dossard").asInt()).isEqualTo(1);
        assertThat(bruno.get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(bruno.get("statut").asString()).isEqualTo("VAINQUEUR");
        assertThat(bruno.get("nombreBouclesTerminees").asInt()).isEqualTo(1);

        HttpResponse<String> parNadia = decor.pilotage(decor.nadia, x);
        assertThat(parNadia.statusCode()).isEqualTo(200);
        JsonNode corpsNadia = decor.json.readTree(parNadia.body());
        assertThat(corpsNadia.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corpsNadia.get("inscritsVainqueurs")).isEqualTo(vainqueurs);
    }

    @Test
    @DisplayName("CA8 : aucun jeton, identifiant de Compte ni de Passage dans le corps du pilotage d'une Course terminée")
    void ca8_aucune_fuite() throws Exception {
        String x = courseTermineeAvecBrunoVainqueur();

        String corps = decor.pilotage(decor.patron, x).body();

        assertThat(corps).doesNotContain(decor.jdbc.queryForList("select jeton_qr from inscription", String.class))
                .doesNotContain(decor.jdbc.queryForList("select id::text from passage", String.class))
                .doesNotContain("jetonQr").doesNotContain("compteId");
        for (UUID compte : List.of(decor.idAlice, decor.idBruno, decor.idChloe, decor.idTom, decor.idLeo)) {
            assertThat(corps).doesNotContain(compte.toString());
        }
    }

    @Test
    @DisplayName("CA8 : deux vainqueurs posés par SQL sont triés par dossard et absents de inscritsEnCourse et inscritsEnAbandon ; une Course EN_COURS sans vainqueur a nombreVainqueurs 0 et une liste vide")
    void ca8_tri_par_dossard_et_course_sans_vainqueur() throws Exception {
        String x = decor.courseXDemarree();
        decor.jdbc.update("update inscription set statut = 'VAINQUEUR' where course_id = ? and compte_id = ?",
                UUID.fromString(x), decor.idTom);
        decor.jdbc.update("update inscription set statut = 'VAINQUEUR' where course_id = ? and compte_id = ?",
                UUID.fromString(x), decor.idBruno);
        decor.jdbc.update("update course set statut = 'TERMINEE' where id = ?", UUID.fromString(x));
        String sansVainqueur = decor.courseYDemarree();

        JsonNode corps = decor.json.readTree(decor.pilotage(decor.patron, x).body());
        JsonNode corpsSans = decor.json.readTree(decor.pilotage(decor.patron, sansVainqueur).body());

        assertThat(corps.get("nombreVainqueurs").asInt()).isEqualTo(2);
        assertThat(corps.get("inscritsVainqueurs")).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 3);
        assertThat(corps.get("inscritsVainqueurs")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Bruno", "Tom");
        assertThat(corps.get("inscritsEnAbandon")).isEmpty();
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(corpsSans.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corpsSans.get("nombreVainqueurs").asInt()).isZero();
        assertThat(corpsSans.get("inscritsVainqueurs")).isEmpty();
    }

    @Test
    @DisplayName("CA8 : anonyme 401, Léo et Alice 403 ACCES_REFUSE, Course EN_PREPARATION 409 COURSE_NON_DEMARREE, Course inconnue 404 COURSE_INTROUVABLE")
    void ca8_erreurs() throws Exception {
        String x = courseTermineeAvecBrunoVainqueur();
        String preparation = decor.creerCourse("En préparation", 1, 5);

        HttpResponse<String> anonyme = decor.api.requete("GET", DecorAbandon.CHEMIN + "/" + x + "/pilotage", Map.of(),
                null, null);

        assertErreur(anonyme, 401, "NON_AUTHENTIFIE");
        for (Session session : List.of(decor.leo, decor.alice)) {
            assertErreur(decor.pilotage(session, x), 403, "ACCES_REFUSE");
        }
        assertErreur(decor.pilotage(decor.patron, preparation), 409, "COURSE_NON_DEMARREE");
        assertErreur(decor.pilotage(decor.patron, ID_INCONNU), 404, "COURSE_INTROUVABLE");
    }

    @Test
    @DisplayName("CA8 : le nombre de requêtes SQL du pilotage est identique avec 1 puis 10 vainqueurs et avec 3 puis 30 abandons (pas de N+1)")
    void ca8_nombre_de_requetes_constant() throws Exception {
        UUID petite = courseTermineeAvecInscrits("Petite", 1, 3);
        UUID grande = courseTermineeAvecInscrits("Grande", 10, 30);
        assertThat(decor.pilotage(decor.patron, petite.toString()).statusCode()).isEqualTo(200); // réchauffe

        long avecPeu = requetesPourUnPilotage(petite);
        long avecBeaucoup = requetesPourUnPilotage(grande);

        assertThat(avecPeu).isPositive();
        assertThat(avecBeaucoup).isEqualTo(avecPeu);
        JsonNode corps = decor.json.readTree(decor.pilotage(decor.patron, grande.toString()).body());
        assertThat(corps.get("inscritsVainqueurs")).hasSize(10);
        assertThat(corps.get("nombreVainqueurs").asInt()).isEqualTo(10);
        assertThat(corps.get("inscritsEnAbandon")).hasSize(30);
    }

    @Test
    @DisplayName("CA8 : le GET ne modifie aucune ligne et ne produit aucune ligne INFO")
    void ca8_lecture_seule_et_silencieuse() throws Exception {
        String x = courseTermineeAvecBrunoVainqueur();
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

    private long requetesPourUnPilotage(UUID idCourse) throws Exception {
        var statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();
        assertThat(decor.pilotage(decor.patron, idCourse.toString()).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    /** Course TERMINEE (SQL, départ t0) avec des VAINQUEUR et des ABANDON HORS_DELAI Boucle 1. */
    private UUID courseTermineeAvecInscrits(String nom, int vainqueurs, int abandons) {
        UUID course = decor.insererCourse(nom, "TERMINEE");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), course);
        int dossard = 1;
        for (int i = 1; i <= vainqueurs + abandons; i++) {
            boolean vainqueur = i <= vainqueurs;
            UUID compte = decor.insererCompte("P" + nom.charAt(0) + i, "COUREUR");
            UUID inscription = decor.insererInscription(course, compte, dossard++, vainqueur ? "VAINQUEUR" : "ABANDON");
            if (!vainqueur) {
                decor.jdbc.update("update inscription set motif_abandon = 'HORS_DELAI', boucle_abandon = 1 "
                        + "where id = ?", inscription);
            }
            if (i % 2 == 0) {
                decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), inscription, 1,
                        Timestamp.from(DecorAbandon.T0.plusSeconds(5)), "SCAN");
            }
        }
        return course;
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : X terminée : scan neuf du vainqueur puis de l'abandonnée -> 409 COURSE_TERMINEE « La course est terminée. » sans propriété d'extension et sans Passage créé ; rejeu du scan de Bruno -> 200")
    void ca9_scan_refuse_apres_la_fin() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
        UUID scanBruno = UUID.randomUUID();
        HttpResponse<String> creation = decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), scanBruno, 0);
        assertThat(creation.statusCode()).as(creation.body()).isEqualTo(201);
        decor.horloge.fixer(FIN_BOUCLE_1);
        assertThat(controler(x)).isEqualTo(new Resultat(2, true));
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

    @Test
    @DisplayName("CA9 : sur une Course EN_COURS voisine, un coureur ABANDON obtient toujours 409 INSCRIPTION_NON_EN_COURSE ; Alice (COUREUR) 403 ACCES_REFUSE ; un bénévole non affecté 403 BENEVOLE_NON_AFFECTE")
    void ca9_autres_refus_inchanges() throws Exception {
        String x = decor.courseXDemarree();
        String y = decor.courseYDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(FIN_BOUCLE_1);
        assertThat(controler(x)).isEqualTo(new Resultat(2, true));
        decor.jdbc.update("update inscription set statut = 'ABANDON' where course_id = ? and compte_id = ?",
                UUID.fromString(y), decor.idAlice);
        decor.insererCompte("Léa", "BENEVOLE");
        Session lea = decor.api.ouvrir("Léa", DecorAbandon.MOT_DE_PASSE);

        HttpResponse<String> abandonDansCourseEnCours = decor.scanner(decor.leo, y, decor.jeton(y, decor.idAlice),
                UUID.randomUUID(), 0);
        HttpResponse<String> parCoureur = decor.scanner(decor.alice, x, decor.jeton(x, decor.idChloe),
                UUID.randomUUID(), 0);
        HttpResponse<String> nonAffecte = decor.scanner(lea, x, decor.jeton(x, decor.idChloe), UUID.randomUUID(), 0);

        assertErreur(abandonDansCourseEnCours, 409, "INSCRIPTION_NON_EN_COURSE");
        assertErreur(parCoureur, 403, "ACCES_REFUSE");
        assertErreur(nonAffecte, 403, "BENEVOLE_NON_AFFECTE");
        assertThat(decor.compterPassages(x, decor.idChloe)).isZero();
        assertThat(decor.compterPassages(y, decor.idAlice)).isZero();
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(decor.json.readTree(reponse.body()).get("code").asString()).isEqualTo(code);
    }
}
