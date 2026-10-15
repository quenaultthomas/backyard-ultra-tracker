package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 4.8 : abandon automatique, persistance, journal, concurrence et cohérence avec le scan
 * (CA6, CA7, CA10). Le planificateur est désactivé par le socle des tests : le cas d'usage est appelé directement,
 * à l'instant du Clock de test. Le contrat vient de la spec.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class AbandonAutomatiqueIntegrationTest {

    static final List<String> CHANGESETS = List.of("0002-compte", "0003-admin-master-unique", "0004-course",
            "0005-logo-course", "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course",
            "0009-passage", "0010-abandon-inscription");

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
    AbandonnerCoureursHorsDelai abandonner;

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

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : à t0 + 60 s (Bruno {1}, Chloé {}, Tom {1}) Chloé passe ABANDON HORS_DELAI Boucle 1 en base, Bruno et Tom restent EN_COURSE sans motif, aucun Passage touché, Course EN_COURS, Course Y non touchée")
    void ca6_abandon_hors_delai_persiste() throws Exception {
        String x = decor.courseXDemarree();
        String y = decor.courseYDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idTom, 1);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));

        int abandons = abandonner.executer(UUID.fromString(x)).abandons();
        int abandonsY = abandonner.executer(UUID.fromString(y)).abandons();

        assertThat(abandons).isEqualTo(1);
        assertThat(abandonsY).isZero();
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(y, decor.idAlice)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(decor.jdbc.queryForObject("select statut from course where id = ?", String.class,
                UUID.fromString(x))).isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA6 : un second appel au même instant ne modifie rien (aucune mise à jour Hibernate, aucune ligne INFO supplémentaire), le journal contient exactement une ligne sans pseudo, dossard ni nom de Course")
    void ca6_idempotence_et_journal() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));
        decor.demarrerJournal();

        abandonner.executer(UUID.fromString(x)).abandons();

        assertThat(decor.lignesAbandonAutomatique()).containsExactly(
                "Abandon automatique : course " + x + ", boucle 1, 1 inscription(s) en abandon.");
        assertThat(decor.lignesAbandonAutomatique().get(0)).doesNotContain("Chloé", "Bruno", "Tom",
                DecorAbandon.NOM_X);
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long misesAJour = statistiques.getEntityUpdateCount();
        long insertions = statistiques.getEntityInsertCount();
        long suppressions = statistiques.getEntityDeleteCount();
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");

        int second = abandonner.executer(UUID.fromString(x)).abandons();

        assertThat(second).isZero();
        assertThat(statistiques.getEntityUpdateCount()).isEqualTo(misesAJour);
        assertThat(statistiques.getEntityInsertCount()).isEqualTo(insertions);
        assertThat(statistiques.getEntityDeleteCount()).isEqualTo(suppressions);
        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(decor.lignesAbandonAutomatique()).hasSize(1);
    }

    @Test
    @DisplayName("CA6 : à t0 + 59,999 s rien n'est décidé, à t0 + 60 s Chloé est en abandon (instant exact de bascule)")
    void ca6_bascule_exacte() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusMillis(59_999));

        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isZero();
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");

        decor.horloge.fixer(DecorAbandon.T0.plusMillis(60_000));
        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isEqualTo(2);
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:1");
        // 4.9 RG1 : Bruno, seul passant de la Boucle 1, est désormais VAINQUEUR (adaptation, 4.8 CA6 sur RG4)
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.jdbc.queryForObject("select statut from course where id = ?", String.class,
                UUID.fromString(x))).isEqualTo("TERMINEE");
    }

    @Test
    @DisplayName("CA6 : rattrapage à t0 + 3 min (Bruno {1, 2}, Chloé {1}, Tom {}) : Tom abandon Boucle 1, Chloé Boucle 2, Bruno EN_COURSE ; une ligne INFO par Boucle")
    void ca6_rattrapage_sur_plusieurs_boucles() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(180));
        decor.demarrerJournal();

        int abandons = abandonner.executer(UUID.fromString(x)).abandons();

        assertThat(abandons).isEqualTo(2);
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:2");
        // 4.9 RG2 : Bruno, seul passant de la Boucle 2, est VAINQUEUR (4.8 attendait EN_COURSE)
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.lignesAbandonAutomatique()).containsExactly(
                "Abandon automatique : course " + x + ", boucle 1, 1 inscription(s) en abandon.",
                "Abandon automatique : course " + x + ", boucle 2, 1 inscription(s) en abandon.");
    }

    @Test
    @DisplayName("CA6 : personne n'a de Passage sur la Boucle 1 à t0 + 2 min : aucun abandon, aucune ligne INFO, la Course reste EN_COURS")
    void ca6_personne_n_a_boucle_aucune_decision() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(120));
        decor.demarrerJournal();

        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isZero();

        for (UUID compte : List.of(decor.idBruno, decor.idChloe, decor.idTom)) {
            assertThat(decor.etat(x, compte)).isEqualTo("EN_COURSE:-:-");
        }
        assertThat(decor.lignesAbandonAutomatique()).isEmpty();
        assertThat(decor.jdbc.queryForObject("select statut from course where id = ?", String.class,
                UUID.fromString(x))).isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA6 : un Passage CORRECTION (sans heure) compte comme un Passage valide : Chloé sauvée, Tom abandonné")
    void ca6_passage_correction_est_valide() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassageCorrection(x, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));

        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isEqualTo(1);

        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:1");
    }

    @Test
    @DisplayName("CA6 : Course EN_PREPARATION, TERMINEE ou EN_COURS sans demarree_le, ou introuvable : 0 abandon, aucune exception, rien de modifié")
    void ca6_courses_hors_perimetre() throws Exception {
        String preparation = decor.creerCourse("En préparation", 1, 5);
        UUID terminee = decor.insererCourse("Terminée", "TERMINEE");
        UUID sansHeure = decor.insererCourse("Sans heure", "EN_COURS");
        for (UUID course : List.of(terminee, sansHeure)) {
            UUID inscriptionAvecPassage = decor.insererInscription(course, decor.idBruno, 1, "EN_COURSE");
            decor.insererInscription(course, decor.idChloe, 2, "EN_COURSE");
            decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), inscriptionAvecPassage, 1,
                    java.sql.Timestamp.from(DecorAbandon.T0.plusSeconds(5)), "SCAN");
        }
        decor.jdbc.update("update course set demarree_le = ? where id = ?",
                java.sql.Timestamp.from(DecorAbandon.T0), terminee);
        decor.horloge.fixer(DecorAbandon.T0.plus(Duration.ofHours(2)));
        List<Map<String, Object>> avant = decor.jdbc.queryForList("select * from inscription order by id");

        assertThat(abandonner.executer(UUID.fromString(preparation)).abandons()).isZero();
        assertThat(abandonner.executer(terminee).abandons()).isZero();
        assertThat(abandonner.executer(sansHeure).abandons()).isZero();
        assertThat(abandonner.executer(UUID.randomUUID()).abandons()).isZero();

        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA6 : migration 0010 seule ajoutée, colonnes nullables, motif 'X' et boucle 0 rejetés par la base, ABANDON sans motif ni Boucle accepté (fixture legacy)")
    void ca6_migration_et_contraintes() throws Exception {
        String x = decor.courseXDemarree();
        assertThat(decor.jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(decor.jdbc.queryForList("select column_name || ':' || data_type || ':' || is_nullable "
                + "from information_schema.columns where table_name = 'inscription' "
                + "and column_name in ('motif_abandon', 'boucle_abandon')", String.class))
                .containsExactlyInAnyOrder("motif_abandon:character varying:YES", "boucle_abandon:integer:YES");
        assertThat(decor.jdbc.queryForObject("select character_maximum_length from information_schema.columns "
                + "where table_name = 'inscription' and column_name = 'motif_abandon'", Integer.class)).isEqualTo(20);
        UUID chloe = UUID.fromString(decor.idInscription(x, decor.idChloe));

        assertThatThrownBy(() -> decor.jdbc.update("update inscription set motif_abandon = 'X' where id = ?", chloe))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("motif_abandon");
        assertThatThrownBy(() -> decor.jdbc.update("update inscription set boucle_abandon = 0 where id = ?", chloe))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("boucle_abandon");
        for (String motif : List.of("VOLONTAIRE", "HORS_DELAI", "MANUEL", "AUTRE")) {
            assertThat(decor.jdbc.update("update inscription set motif_abandon = ?, boucle_abandon = 3 where id = ?",
                    motif, chloe)).isEqualTo(1);
        }
        assertThat(decor.jdbc.update("update inscription set statut = 'ABANDON', motif_abandon = null, "
                + "boucle_abandon = null where id = ?", chloe)).isEqualTo(1);
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:-:-");
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : deux exécutions simultanées du cas d'usage pour la même Course : Chloé abandonnée une seule fois, une seule ligne INFO, aucune exception")
    void ca7_deux_executions_simultanees() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));
        decor.demarrerJournal();
        CyclicBarrier depart = new CyclicBarrier(2);
        Callable<Integer> execution = () -> {
            depart.await(10, TimeUnit.SECONDS);
            return abandonner.executer(UUID.fromString(x)).abandons();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> premiere = pool.submit(execution);
            Future<Integer> seconde = pool.submit(execution);

            assertThat(premiere.get(20, TimeUnit.SECONDS) + seconde.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.lignesAbandonAutomatique()).hasSize(1);
    }

    @Test
    @DisplayName("CA7 : 20 essais de scan de Chloé simultané au contrôle (t0 + 60,3 s, âge 500 ms) : soit 201 avec Passage et EN_COURSE, soit 409 INSCRIPTION_NON_EN_COURSE avec ABANDON et sans Passage, jamais les deux, jamais de 500")
    void ca7_scan_simultane_au_controle() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idTom, 1);
        String jeton = decor.jeton(x, decor.idChloe);
        UUID inscriptionChloe = UUID.fromString(decor.idInscription(x, decor.idChloe));
        decor.horloge.fixer(DecorAbandon.T0.plusMillis(60_300));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int essai = 1; essai <= 20; essai++) {
                decor.jdbc.update("delete from passage where inscription_id = ?", inscriptionChloe);
                decor.jdbc.update("update inscription set statut = 'EN_COURSE', motif_abandon = null, "
                        + "boucle_abandon = null where id = ?", inscriptionChloe);
                CyclicBarrier depart = new CyclicBarrier(2);
                Future<Integer> controle = pool.submit(() -> {
                    depart.await(10, TimeUnit.SECONDS);
                    return abandonner.executer(UUID.fromString(x)).abandons();
                });
                Future<HttpResponse<String>> scan = pool.submit(() -> {
                    depart.await(10, TimeUnit.SECONDS);
                    return decor.scanner(decor.leo, x, jeton, UUID.randomUUID(), 500);
                });

                controle.get(20, TimeUnit.SECONDS);
                HttpResponse<String> reponse = scan.get(20, TimeUnit.SECONDS);

                String contexte = "essai " + essai + " : " + reponse.statusCode() + " " + reponse.body();
                int passages = decor.compterPassages(x, decor.idChloe);
                if (reponse.statusCode() == 201) {
                    assertThat(passages).as(contexte).isEqualTo(1);
                    assertThat(decor.etat(x, decor.idChloe)).as(contexte).isEqualTo("EN_COURSE:-:-");
                } else {
                    assertThat(reponse.statusCode()).as(contexte).isEqualTo(409);
                    assertThat(decor.json.readTree(reponse.body()).get("code").asString()).as(contexte)
                            .isEqualTo("INSCRIPTION_NON_EN_COURSE");
                    assertThat(passages).as(contexte).isZero();
                    assertThat(decor.etat(x, decor.idChloe)).as(contexte).isEqualTo("ABANDON:HORS_DELAI:1");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CA7 : la Course X verrouillée n'empêche pas de scanner dans la Course Y ; le scan de X attend la fin du verrou puis aboutit")
    void ca7_le_verrou_est_par_course() throws Exception {
        String x = decor.courseXDemarree();
        String y = decor.courseYDemarree();
        String jetonChloe = decor.jeton(x, decor.idChloe);
        String jetonAlice = decor.jeton(y, decor.idAlice);
        decor.horloge.fixer(DecorAbandon.T0.plusMillis(30_000));

        try (Connection verrou = dataSource.getConnection()) {
            verrou.setAutoCommit(false);
            try (PreparedStatement requete = verrou.prepareStatement("select id from course where id = ? for update")) {
                requete.setObject(1, UUID.fromString(x));
                requete.execute();
            }
            CompletableFuture<HttpResponse<String>> scanX = CompletableFuture.supplyAsync(() -> {
                try {
                    return decor.scanner(decor.leo, x, jetonChloe, UUID.randomUUID(), 0);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            HttpResponse<String> scanY = decor.scanner(decor.leo, y, jetonAlice, UUID.randomUUID(), 0);

            assertThat(scanY.statusCode()).as(scanY.body()).isEqualTo(201);
            assertThat(scanX.isDone()).as("le scan de X attend le verrou de X").isFalse();
            verrou.commit();
            assertThat(scanX.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : Chloé abandonnée automatiquement : scan avec identifiant neuf -> 409 INSCRIPTION_NON_EN_COURSE (statut ABANDON, dossard 2, pseudo Chloé), mêmes propriétés qu'un abandon posé en SQL, aucun Passage ; rejeu d'un scan de Bruno -> 200")
    void ca10_scan_refuse_apres_abandon_automatique() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
        UUID scanBruno = UUID.randomUUID();
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), scanBruno, 0).statusCode())
                .isEqualTo(201);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(201);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));
        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isEqualTo(1);
        decor.jdbc.update("update inscription set statut = 'ABANDON' where course_id = ? and compte_id = ?",
                UUID.fromString(x), decor.idTom);

        HttpResponse<String> refus = decor.scanner(decor.leo, x, decor.jeton(x, decor.idChloe), UUID.randomUUID(), 0);
        HttpResponse<String> reference = decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), UUID.randomUUID(),
                0);

        assertThat(refus.statusCode()).as(refus.body()).isEqualTo(409);
        assertThat(refus.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        JsonNode corps = decor.json.readTree(refus.body());
        assertThat(corps.get("code").asString()).isEqualTo("INSCRIPTION_NON_EN_COURSE");
        assertThat(corps.get("statut").asString()).isEqualTo("ABANDON");
        assertThat(corps.get("dossard").asInt()).isEqualTo(2);
        assertThat(corps.get("pseudo").asString()).isEqualTo("Chloé");
        assertThat(corps.get("detail").asString()).isEqualTo("Ce coureur n'est plus en course.");
        JsonNode corpsReference = decor.json.readTree(reference.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrderElementsOf(corpsReference.propertyNames());
        assertThat(corps.get("title")).isEqualTo(corpsReference.get("title"));
        assertThat(decor.compterPassages(x, decor.idChloe)).isZero();

        HttpResponse<String> rejeu = decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), scanBruno, 0);
        assertThat(rejeu.statusCode()).as(rejeu.body()).isEqualTo(200);
        assertThat(decor.compterPassages(x, decor.idBruno)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA10 : le rejeu du scan d'un coureur passé depuis en ABANDON reste 200 (Chloé abandonnée Boucle 2)")
    void ca10_rejeu_apres_abandon_et_scan_d_un_survivant() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
        UUID scanChloe = UUID.randomUUID();
        for (UUID compte : List.of(decor.idBruno, decor.idChloe, decor.idTom)) {
            UUID scan = compte.equals(decor.idChloe) ? scanChloe : UUID.randomUUID();
            assertThat(decor.scanner(decor.leo, x, decor.jeton(x, compte), scan, 0).statusCode()).isEqualTo(201);
        }
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(70));
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(201);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(120));
        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isEqualTo(2);
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:2");
        // 4.9 : Bruno, seul passant de la Boucle 2, est vainqueur ; le rejeu d'un scan antérieur reste 200
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");

        HttpResponse<String> rejeu = decor.scanner(decor.leo, x, decor.jeton(x, decor.idChloe), scanChloe, 0);

        assertThat(rejeu.statusCode()).as(rejeu.body()).isEqualTo(200);
        assertThat(decor.compterPassages(x, decor.idChloe)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA10 : scan de Tom (Passage 1) en Boucle 2 à t0 + 70 s -> 201 ; scan de Chloé juste avant le contrôle (scanneLe dans la Boucle 1) -> 201 et le contrôle ne l'abandonne pas")
    void ca10_scan_avant_le_controle_sauve_le_coureur() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusMillis(60_300));

        HttpResponse<String> sauve = decor.scanner(decor.leo, x, decor.jeton(x, decor.idChloe), UUID.randomUUID(), 500);

        assertThat(sauve.statusCode()).as(sauve.body()).isEqualTo(201);
        assertThat(decor.json.readTree(sauve.body()).get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isZero();
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(70));
        HttpResponse<String> survivant = decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), UUID.randomUUID(), 0);
        assertThat(survivant.statusCode()).as(survivant.body()).isEqualTo(201);
        assertThat(decor.json.readTree(survivant.body()).get("numeroBoucle").asInt()).isEqualTo(2);
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
    }
}
