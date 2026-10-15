package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
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
 * Contrat de l'incrément 4.8 : liste des abandons au pilotage (CA9). Le contrat vient de la spec : 23 champs au
 * premier niveau (19 inchangés plus nombreAbandons, inscritsEnAbandon, nombreVainqueurs et inscritsVainqueurs, 4.9), 7 champs par abandon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class PilotageAbandonsIntegrationTest {

    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final List<String> CHAMPS = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur", "demarreeLe",
            "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle", "boucleMaxTerminee",
            "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "nombreAyantBoucle", "nombreEnBoucle", "inscritsEnCourse",
            "nombreAbandons", "inscritsEnAbandon", "nombreVainqueurs", "inscritsVainqueurs");
    static final List<String> CHAMPS_ABANDON = List.of("inscriptionId", "dossard", "pseudo", "statut",
            "nombreBouclesTerminees", "boucleAbandon", "motifAbandon");

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

    /** X démarrée, Bruno {1}, Tom {1}, Chloé {} ; horloge à t0 + 90 s ; contrôle exécuté (Chloé abandonnée). */
    private String courseAvecChloeAbandonnee() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isEqualTo(1);
        return x;
    }

    @Test
    @DisplayName("CA9 : Patron lit le pilotage à t0 + 90 s après le contrôle : 200, no-store, 23 champs dont les 19 inchangés en valeur, un abandon à 7 champs (Chloé, dossard 2, HORS_DELAI, Boucle 1, 0 Boucle terminée), Nadia obtient le même corps")
    void ca9_pilotage_avec_un_abandon() throws Exception {
        String x = courseAvecChloeAbandonnee();

        HttpResponse<String> reponse = decor.pilotage(decor.patron, x);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json").doesNotContain("problem"));
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(23).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("courseId").asString()).isEqualTo(x);
        assertThat(corps.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(corps.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(corps.get("nombreAyantBoucle").asInt()).isZero();
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(2);
        JsonNode enCourse = corps.get("inscritsEnCourse");
        assertThat(enCourse).extracting(n -> n.get("pseudo").asString()).containsExactly("Bruno", "Tom");
        assertThat(enCourse).extracting(n -> n.get("statut").asString()).containsOnly("EN_COURSE");
        assertThat(corps.get("nombreAbandons").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreVainqueurs").asInt()).isZero();
        assertThat(corps.get("inscritsVainqueurs")).isEmpty();
        JsonNode abandons = corps.get("inscritsEnAbandon");
        assertThat(abandons).hasSize(1);
        JsonNode chloe = abandons.get(0);
        assertThat(chloe.propertyNames()).hasSize(7).containsExactlyInAnyOrderElementsOf(CHAMPS_ABANDON);
        assertThat(chloe.get("inscriptionId").asString()).isEqualTo(decor.idInscription(x, decor.idChloe));
        assertThat(chloe.get("dossard").asInt()).isEqualTo(2);
        assertThat(chloe.get("pseudo").asString()).isEqualTo("Chloé");
        assertThat(chloe.get("statut").asString()).isEqualTo("ABANDON");
        assertThat(chloe.get("nombreBouclesTerminees").asInt()).isZero();
        assertThat(chloe.get("boucleAbandon").asInt()).isEqualTo(1);
        assertThat(chloe.get("motifAbandon").asString()).isEqualTo("HORS_DELAI");

        HttpResponse<String> parNadia = decor.pilotage(decor.nadia, x);
        assertThat(parNadia.statusCode()).isEqualTo(200);
        assertThat(decor.json.readTree(parNadia.body())).isEqualTo(corps);
    }

    @Test
    @DisplayName("CA9 : aucun jeton, identifiant de Compte ni de Passage dans le corps")
    void ca9_aucune_fuite() throws Exception {
        String x = courseAvecChloeAbandonnee();

        String corps = decor.pilotage(decor.patron, x).body();

        assertThat(corps).doesNotContain(decor.jdbc.queryForList("select jeton_qr from inscription", String.class))
                .doesNotContain(decor.jdbc.queryForList("select id::text from passage", String.class))
                .doesNotContain("jetonQr").doesNotContain("compteId");
        for (UUID compte : List.of(decor.idAlice, decor.idBruno, decor.idChloe, decor.idTom, decor.idLeo)) {
            assertThat(corps).doesNotContain(compte.toString());
        }
    }

    @Test
    @DisplayName("CA9 : abandons triés par dossard ; un abandon legacy (SQL, sans motif ni Boucle) a boucleAbandon et motifAbandon à null")
    void ca9_tri_par_dossard_et_abandon_legacy() throws Exception {
        String x = courseAvecChloeAbandonnee();
        decor.jdbc.update("update inscription set statut = 'ABANDON' where course_id = ? and compte_id = ?",
                UUID.fromString(x), decor.idTom);
        decor.jdbc.update("update inscription set statut = 'ABANDON', motif_abandon = 'MANUEL', boucle_abandon = 2 "
                + "where course_id = ? and compte_id = ?", UUID.fromString(x), decor.idBruno);

        JsonNode corps = decor.json.readTree(decor.pilotage(decor.patron, x).body());

        assertThat(corps.get("nombreAbandons").asInt()).isEqualTo(3);
        JsonNode abandons = corps.get("inscritsEnAbandon");
        assertThat(abandons).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2, 3);
        assertThat(abandons.get(0).get("motifAbandon").asString()).isEqualTo("MANUEL");
        assertThat(abandons.get(0).get("boucleAbandon").asInt()).isEqualTo(2);
        assertThat(abandons.get(0).get("nombreBouclesTerminees").asInt()).isEqualTo(1);
        assertThat(abandons.get(2).get("pseudo").asString()).isEqualTo("Tom");
        assertThat(abandons.get(2).get("boucleAbandon").isNull()).isTrue();
        assertThat(abandons.get(2).get("motifAbandon").isNull()).isTrue();
        assertThat(corps.get("inscritsEnCourse")).isEmpty();
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA9 : Course TERMINEE (SQL) avec un abandon : 200, abandon présent ; EN_COURS sans demarree_le : abandons renseignés aussi")
    void ca9_courses_terminee_et_sans_heure() throws Exception {
        UUID terminee = decor.insererCourse("Terminée", "TERMINEE");
        UUID inscription = decor.insererInscription(terminee, decor.idBruno, 1, "ABANDON");
        decor.jdbc.update("update inscription set motif_abandon = 'HORS_DELAI', boucle_abandon = 2 where id = ?",
                inscription);
        decor.insererInscription(terminee, decor.idChloe, 2, "EN_COURSE");
        UUID sansHeure = decor.insererCourse("Sans heure", "EN_COURS");
        decor.insererInscription(sansHeure, decor.idTom, 1, "ABANDON");

        JsonNode apresTerminee = decor.json.readTree(decor.pilotage(decor.patron, terminee.toString()).body());
        JsonNode apresSansHeure = decor.json.readTree(decor.pilotage(decor.patron, sansHeure.toString()).body());

        assertThat(apresTerminee.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(apresTerminee.get("nombreAbandons").asInt()).isEqualTo(1);
        assertThat(apresTerminee.get("inscritsEnAbandon").get(0).get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(apresTerminee.get("inscritsEnAbandon").get(0).get("motifAbandon").asString())
                .isEqualTo("HORS_DELAI");
        assertThat(apresTerminee.get("inscritsEnCourse")).hasSize(1);
        assertThat(apresSansHeure.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(apresSansHeure.get("nombreAbandons").asInt()).isEqualTo(1);
        assertThat(apresSansHeure.get("inscritsEnAbandon").get(0).get("pseudo").asString()).isEqualTo("Tom");
    }

    @Test
    @DisplayName("CA9 : anonyme 401, Léo et Alice 403 ACCES_REFUSE, Course EN_PREPARATION 409 COURSE_NON_DEMARREE, Course inconnue 404 COURSE_INTROUVABLE (inchangés)")
    void ca9_erreurs_inchangees() throws Exception {
        String x = courseAvecChloeAbandonnee();
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
    @DisplayName("CA9 : le nombre de requêtes SQL est identique avec 3 et avec 30 abandons (pas de N+1)")
    void ca9_nombre_de_requetes_constant() throws Exception {
        UUID petite = courseEnCoursAvecAbandons("Petite course", 3);
        UUID grande = courseEnCoursAvecAbandons("Grande course", 30);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        assertThat(decor.pilotage(decor.patron, petite.toString()).statusCode()).isEqualTo(200); // réchauffe

        long avecTrois = requetesPourUnPilotage(petite);
        long avecTrente = requetesPourUnPilotage(grande);

        assertThat(avecTrois).isPositive();
        assertThat(avecTrente).isEqualTo(avecTrois);
        JsonNode corps = decor.json.readTree(decor.pilotage(decor.patron, grande.toString()).body());
        assertThat(corps.get("inscritsEnAbandon")).hasSize(30);
        assertThat(corps.get("nombreAbandons").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("CA9 : le GET ne modifie aucune ligne (le pilotage ne déclenche jamais le contrôle) et ne produit aucune ligne INFO")
    void ca9_lecture_seule_et_silencieuse() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90)); // Chloé et Tom en retard, contrôle non exécuté
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList("select * from course order by id");
        decor.demarrerJournal();

        JsonNode corps = decor.json.readTree(decor.pilotage(decor.patron, x).body());

        assertThat(corps.get("nombreAbandons").asInt()).isZero();
        assertThat(corps.get("inscritsEnAbandon")).isEmpty();
        assertThat(corps.get("inscritsEnCourse")).hasSize(3);
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

    /** Course EN_COURS (SQL, départ t0) dont tous les inscrits sont en abandon HORS_DELAI Boucle 1. */
    private UUID courseEnCoursAvecAbandons(String nom, int nombre) {
        UUID course = decor.insererCourse(nom, "EN_COURS");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), course);
        for (int i = 1; i <= nombre; i++) {
            UUID compte = decor.insererCompte("Abandon" + nom.charAt(0) + i, "COUREUR");
            UUID inscription = decor.insererInscription(course, compte, i, "ABANDON");
            decor.jdbc.update("update inscription set motif_abandon = 'HORS_DELAI', boucle_abandon = 1 where id = ?",
                    inscription);
            if (i % 2 == 0) {
                decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), inscription, 1,
                        Timestamp.from(DecorAbandon.T0.plusSeconds(5)), "SCAN");
            }
        }
        return course;
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(decor.json.readTree(reponse.body()).get("code").asString()).isEqualTo(code);
    }
}
