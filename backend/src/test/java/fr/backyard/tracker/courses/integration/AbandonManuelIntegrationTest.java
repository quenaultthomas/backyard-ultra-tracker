package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
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
 * Contrat de l'incrément 4.11 : abandon manuel (CA5 à CA12). Le contrat vient de la spec. Course X : Boucle de 1 min,
 * 5 Boucles max, t0 = 14:00:00Z ; Bruno (dossard 1), Chloé (2), Tom (3), Léo affecté. Le planificateur est désactivé
 * par le socle des tests : le contrôle automatique est appelé directement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@DirtiesContext
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class AbandonManuelIntegrationTest {

    static final Instant DECISION_BOUCLE_1 = DecorAbandon.T0.plusSeconds(60 + DecorAbandon.GRACE_SECONDES);
    static final String ID_INCONNU = "00000000-0000-4000-8000-000000000001";
    static final List<String> CHAMPS_REPONSE = List.of("inscriptionId", "dossard", "statut", "motifAbandon",
            "boucleAbandon", "statutCourse");
    static final List<String> CHAMPS_PILOTAGE = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur",
            "demarreeLe", "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle",
            "boucleMaxTerminee", "boucleEnAttenteDeDecision", "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "nombreAyantBoucle", "nombreEnBoucle", "inscritsEnCourse",
            "nombreAbandons", "inscritsEnAbandon", "nombreVainqueurs", "inscritsVainqueurs");
    static final String TOUT_INSCRIPTION = "select * from inscription order by id";
    static final String TOUT_COURSE = "select * from course order by id";
    static final String TOUT_PASSAGE = "select * from passage order by id";
    static final String ETAT_INSCRIPTIONS = "select id, statut, motif_abandon, boucle_abandon from inscription "
            + "order by id";

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

    DecorAbandon decor;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
    }

    @AfterEach
    void nettoyer() {
        decor.arreterJournal();
        decor.jdbc.execute("drop trigger if exists test_echec_fin_course on course");
        decor.jdbc.execute("drop function if exists test_echec_fin_course()");
    }

    // ---------------------------------------------------------------- aides

    private HttpResponse<String> abandonner(Session session, String course, String inscription, String corps)
            throws Exception {
        return decor.api.requete("POST", cheminAbandon(course, inscription), session.entetes(), "application/json",
                corps);
    }

    private static String cheminAbandon(String course, String inscription) {
        return DecorAbandon.CHEMIN + "/" + course + "/inscriptions/" + inscription + "/abandon";
    }

    private static String motif(String motif) {
        return "{\"motif\":\"" + motif + "\"}";
    }

    private String inscriptionDe(String course, UUID compte) {
        return decor.idInscription(course, compte);
    }

    private JsonNode corps(HttpResponse<String> reponse) {
        return decor.json.readTree(reponse.body());
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code) {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(corps(reponse).get("code").asString()).isEqualTo(code);
    }

    private List<String> lignesAbandonManuel() {
        return lignesInfo("Abandon manuel");
    }

    private List<String> lignesInfo(String debut) {
        return decor.evenements().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(debut)).toList();
    }

    private List<String> toutesLignesInfo() {
        return decor.evenements().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void abandonnerParSql(String course, UUID compte, String motif, int boucle) {
        decor.jdbc.update("update inscription set statut = 'ABANDON', motif_abandon = ?, boucle_abandon = ? "
                + "where course_id = ? and compte_id = ?", motif, boucle, UUID.fromString(course), compte);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : Patron abandonne Tom VOLONTAIRE : 200, exactement les 6 champs, Boucle 1, EN_COURS, sans jeton ni pseudo ; en base seul Tom change, aucun Passage ajouté ; journal INFO sans donnée sensible")
    void ca5_abandon_nominal_persiste() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(30));
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        String tom = inscriptionDe(x, decor.idTom);
        decor.demarrerJournal();

        HttpResponse<String> reponse = abandonner(decor.patron, x, tom, motif("VOLONTAIRE"));

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        JsonNode contenu = corps(reponse);
        assertThat(contenu.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_REPONSE);
        assertThat(contenu.get("inscriptionId").asString()).isEqualTo(tom);
        assertThat(contenu.get("dossard").asInt()).isEqualTo(3);
        assertThat(contenu.get("statut").asString()).isEqualTo("ABANDON");
        assertThat(contenu.get("motifAbandon").asString()).isEqualTo("VOLONTAIRE");
        assertThat(contenu.get("boucleAbandon").asInt()).isEqualTo(1);
        assertThat(contenu.get("statutCourse").asString()).isEqualTo("EN_COURS");
        assertThat(reponse.body()).doesNotContain("Tom", decor.jeton(x, decor.idTom), decor.idTom.toString());
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:VOLONTAIRE:1");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(lignesAbandonManuel()).containsExactly(
                "Abandon manuel : course " + x + ", inscription " + tom + ", motif VOLONTAIRE, boucle 1.");
        assertThat(lignesInfo("Fin de course")).isEmpty();
        List<String> jetons = decor.jdbc.queryForList("select jeton_qr from inscription", String.class);
        for (String ligne : toutesLignesInfo()) {
            assertThat(ligne).doesNotContain("Tom", "Bruno", "Chloé", DecorAbandon.NOM_X).doesNotContain(jetons);
        }
    }

    @Test
    @DisplayName("CA5 : Nadia abandonne Bruno {1, 2} MANUEL : Boucle 3 ; Chloé {1} AUTRE : Boucle 2 ; le Passage CORRECTION compte comme un SCAN ; les motifs sont persistés")
    void ca5_boucle_d_abandon_et_motifs() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.poserPassageCorrection(x, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));

        HttpResponse<String> bruno = abandonner(decor.nadia, x, inscriptionDe(x, decor.idBruno), motif("MANUEL"));
        HttpResponse<String> chloe = abandonner(decor.nadia, x, inscriptionDe(x, decor.idChloe), motif("AUTRE"));
        HttpResponse<String> tom = abandonner(decor.nadia, x, inscriptionDe(x, decor.idTom), motif("VOLONTAIRE"));

        assertThat(bruno.statusCode()).as(bruno.body()).isEqualTo(200);
        assertThat(corps(bruno).get("boucleAbandon").asInt()).isEqualTo(3);
        assertThat(corps(bruno).get("motifAbandon").asString()).isEqualTo("MANUEL");
        assertThat(corps(chloe).get("boucleAbandon").asInt()).isEqualTo(2);
        assertThat(corps(chloe).get("motifAbandon").asString()).isEqualTo("AUTRE");
        assertThat(corps(tom).get("boucleAbandon").asInt()).isEqualTo(2);
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("ABANDON:MANUEL:3");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:AUTRE:2");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:VOLONTAIRE:2");
    }

    @Test
    @DisplayName("CA5 : coureur ayant terminé la dernière Boucle autorisée (Passages {1..5}) : boucleAbandon 6")
    void ca5_boucle_apres_la_derniere_boucle_autorisee() throws Exception {
        String x = decor.courseXDemarree();
        for (int boucle = 1; boucle <= 5; boucle++) {
            decor.poserPassage(x, decor.idBruno, boucle);
        }
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(270));

        HttpResponse<String> reponse = abandonner(decor.patron, x, inscriptionDe(x, decor.idBruno), motif("MANUEL"));

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(corps(reponse).get("boucleAbandon").asInt()).isEqualTo(6);
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("ABANDON:MANUEL:6");
    }

    @Test
    @DisplayName("CA5 : une Course EN_COURS sans demarree_le reste éligible à l'abandon manuel")
    void ca5_course_en_cours_sans_heure_de_depart() throws Exception {
        UUID course = decor.insererCourse("Course sans heure", "EN_COURS");
        UUID inscription = decor.insererInscription(course, decor.idBruno, 1, "EN_COURSE");
        decor.insererInscription(course, decor.idChloe, 2, "EN_COURSE");

        HttpResponse<String> reponse = abandonner(decor.patron, course.toString(), inscription.toString(),
                motif("AUTRE"));

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(corps(reponse).get("boucleAbandon").asInt()).isEqualTo(1);
        assertThat(corps(reponse).get("statutCourse").asString()).isEqualTo("EN_COURS");
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : corps absent, JSON invalide, motif inconnu, vide ou numérique : 400 CORPS_ILLISIBLE, y compris sur une Course inconnue ; aucune écriture, aucun journal INFO")
    void ca6_corps_illisible() throws Exception {
        String x = decor.courseXDemarree();
        String tom = inscriptionDe(x, decor.idTom);
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        for (String corps : new String[] {null, "{", "{\"motif\":\"FOO\"}", "{\"motif\":\"\"}", "{\"motif\":1}",
                "{\"motif\":0}", "{\"motif\":\"volontaire\"}"}) {
            assertErreur(abandonner(decor.patron, x, tom, corps), 400, "CORPS_ILLISIBLE");
            assertErreur(abandonner(decor.patron, ID_INCONNU, tom, corps), 400, "CORPS_ILLISIBLE");
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(lignesAbandonManuel()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : {} et motif null : 400 VALIDATION_ECHOUEE, erreurs [{motif, MOTIF_OBLIGATOIRE}] ; HORS_DELAI : MOTIF_INVALIDE ; contrôlé avant la Course")
    void ca6_validation_du_motif() throws Exception {
        String x = decor.courseXDemarree();
        String tom = inscriptionDe(x, decor.idTom);
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);

        for (String course : List.of(x, ID_INCONNU)) {
            for (String corps : List.of("{}", "{\"motif\":null}")) {
                HttpResponse<String> reponse = abandonner(decor.patron, course, tom, corps);
                assertErreur(reponse, 400, "VALIDATION_ECHOUEE");
                JsonNode erreurs = corps(reponse).get("erreurs");
                assertThat(erreurs.size()).isEqualTo(1);
                assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("motif");
                assertThat(erreurs.get(0).get("code").asString()).isEqualTo("MOTIF_OBLIGATOIRE");
                assertThat(erreurs.get(0).get("message").asString()).isNotBlank();
            }
            HttpResponse<String> horsDelai = abandonner(decor.patron, course, tom, motif("HORS_DELAI"));
            assertErreur(horsDelai, 400, "VALIDATION_ECHOUEE");
            JsonNode erreurs = corps(horsDelai).get("erreurs");
            assertThat(erreurs.size()).isEqualTo(1);
            assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("motif");
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("MOTIF_INVALIDE");
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA6 : Course inconnue ou {id} non UUID : 404 COURSE_INTROUVABLE (avant l'Inscription) ; sur X EN_COURS : Inscription inconnue, non UUID ou d'une autre Course : 404 INSCRIPTION_INTROUVABLE")
    void ca6_introuvables() throws Exception {
        String x = decor.courseXDemarree();
        String y = decor.courseYDemarree();
        String tom = inscriptionDe(x, decor.idTom);
        String alice = inscriptionDe(y, decor.idAlice);
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList(TOUT_COURSE);
        decor.demarrerJournal();

        assertErreur(abandonner(decor.patron, ID_INCONNU, tom, motif("MANUEL")), 404, "COURSE_INTROUVABLE");
        assertErreur(abandonner(decor.patron, "pas-un-uuid", tom, motif("MANUEL")), 404, "COURSE_INTROUVABLE");
        assertErreur(abandonner(decor.patron, ID_INCONNU, "abc", motif("MANUEL")), 404, "COURSE_INTROUVABLE");
        HttpResponse<String> inconnue = abandonner(decor.patron, x, ID_INCONNU, motif("MANUEL"));
        assertErreur(inconnue, 404, "INSCRIPTION_INTROUVABLE");
        assertThat(corps(inconnue).get("detail").asString()).isEqualTo("L'inscription est introuvable.");
        assertErreur(abandonner(decor.patron, x, "abc", motif("MANUEL")), 404, "INSCRIPTION_INTROUVABLE");
        assertErreur(abandonner(decor.patron, x, "00000000-0000-0000-0000-000000000000", motif("MANUEL")), 404,
                "INSCRIPTION_INTROUVABLE");
        assertErreur(abandonner(decor.patron, x, alice, motif("MANUEL")), 404, "INSCRIPTION_INTROUVABLE");

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_COURSE)).isEqualTo(coursesAvant);
        assertThat(lignesAbandonManuel()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Course EN_PREPARATION : 409 COURSE_NON_DEMARREE ; Course TERMINEE : 409 COURSE_TERMINEE avec le detail de l'abandon ; y compris Inscription inconnue ou non UUID (pas 404)")
    void ca6_course_non_eligible_avant_l_inscription() throws Exception {
        String preparation = decor.creerCourse("Course en préparation", 1, 5);
        UUID terminee = decor.insererCourse("Course terminée", "TERMINEE");
        UUID inscriptionTerminee = decor.insererInscription(terminee, decor.idBruno, 1, "ABANDON");
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        for (String inscription : List.of(ID_INCONNU, "abc")) {
            HttpResponse<String> nonDemarree = abandonner(decor.patron, preparation, inscription, motif("MANUEL"));
            assertErreur(nonDemarree, 409, "COURSE_NON_DEMARREE");
            assertThat(corps(nonDemarree).get("detail").asString()).isEqualTo("La course n'est pas démarrée.");
        }
        for (String inscription : List.of(ID_INCONNU, "abc", inscriptionTerminee.toString())) {
            HttpResponse<String> reponse = abandonner(decor.patron, terminee.toString(), inscription,
                    motif("MANUEL"));
            assertErreur(reponse, 409, "COURSE_TERMINEE");
            assertThat(corps(reponse).get("detail").asString())
                    .isEqualTo("La course est terminée : aucun abandon n'est possible.");
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(decor.statutCourse(preparation)).isEqualTo("EN_PREPARATION");
        assertThat(decor.statutCourse(terminee.toString())).isEqualTo("TERMINEE");
        assertThat(lignesAbandonManuel()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Inscription ABANDON ou VAINQUEUR sur Course EN_COURS : 409 INSCRIPTION_NON_EN_COURSE avec statut ; l'abandon existant n'est jamais écrasé ; 404 Inscription avant 409 Inscription")
    void ca6_inscription_non_en_course() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        abandonnerParSql(x, decor.idChloe, "HORS_DELAI", 1);
        decor.jdbc.update("update inscription set statut = 'VAINQUEUR' where course_id = ? and compte_id = ?",
                UUID.fromString(x), decor.idTom);
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        HttpResponse<String> abandon = abandonner(decor.patron, x, inscriptionDe(x, decor.idChloe), motif("MANUEL"));
        HttpResponse<String> vainqueur = abandonner(decor.patron, x, inscriptionDe(x, decor.idTom), motif("MANUEL"));

        assertErreur(abandon, 409, "INSCRIPTION_NON_EN_COURSE");
        assertThat(corps(abandon).get("statut").asString()).isEqualTo("ABANDON");
        assertThat(corps(abandon).get("detail").asString()).isEqualTo("Ce coureur n'est plus en course.");
        assertThat(abandon.body()).doesNotContain("Chloé");
        assertErreur(vainqueur, 409, "INSCRIPTION_NON_EN_COURSE");
        assertThat(corps(vainqueur).get("statut").asString()).isEqualTo("VAINQUEUR");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(lignesAbandonManuel()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : un rejeu du même POST reçoit 409 INSCRIPTION_NON_EN_COURSE (statut ABANDON), état inchangé, une seule ligne de journal")
    void ca6_rejeu() throws Exception {
        String x = decor.courseXDemarree();
        String tom = inscriptionDe(x, decor.idTom);
        decor.demarrerJournal();
        assertThat(abandonner(decor.patron, x, tom, motif("MANUEL")).statusCode()).isEqualTo(200);

        HttpResponse<String> rejeu = abandonner(decor.patron, x, tom, motif("VOLONTAIRE"));

        assertErreur(rejeu, 409, "INSCRIPTION_NON_EN_COURSE");
        assertThat(corps(rejeu).get("statut").asString()).isEqualTo("ABANDON");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:MANUEL:1");
        assertThat(lignesAbandonManuel()).hasSize(1);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : sans jeton CSRF : 403 CSRF_INVALIDE (avant tout) ; anonyme avec jeton : 401 NON_AUTHENTIFIE ; Alice et Léo (affecté) : 403 ACCES_REFUSE, aussi sur une Course inconnue ; aucun effet")
    void ca7_securite() throws Exception {
        String x = decor.courseXDemarree();
        String tom = inscriptionDe(x, decor.idTom);
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        String jetonCsrf = decor.api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jetonCsrf, "X-XSRF-TOKEN", jetonCsrf);

        for (String course : List.of(x, ID_INCONNU)) {
            HttpResponse<String> sansJeton = decor.api.requete("POST", cheminAbandon(course, tom),
                    decor.patron.enteteLecture(), "application/json", motif("MANUEL"));
            assertErreur(sansJeton, 403, "CSRF_INVALIDE");
            HttpResponse<String> anonymeSansJeton = decor.api.requete("POST", cheminAbandon(course, tom), Map.of(),
                    "application/json", motif("MANUEL"));
            assertErreur(anonymeSansJeton, 403, "CSRF_INVALIDE");
            HttpResponse<String> nonAuthentifie = decor.api.requete("POST", cheminAbandon(course, tom), anonyme,
                    "application/json", motif("MANUEL"));
            assertErreur(nonAuthentifie, 401, "NON_AUTHENTIFIE");
            assertErreur(abandonner(decor.alice, course, tom, motif("MANUEL")), 403, "ACCES_REFUSE");
            assertErreur(abandonner(decor.leo, course, tom, motif("MANUEL")), 403, "ACCES_REFUSE");
            assertErreur(abandonner(decor.alice, course, tom, "{"), 403, "ACCES_REFUSE");
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA7 : GET, PUT, DELETE sur le chemin d'abandon : 404 RESSOURCE_INTROUVABLE ou 405, jamais 2xx ; aucun effet sur X")
    void ca7_autres_methodes() throws Exception {
        String x = decor.courseXDemarree();
        String chemin = cheminAbandon(x, inscriptionDe(x, decor.idTom));
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);

        for (String methode : List.of("GET", "PUT", "DELETE")) {
            boolean corps = "PUT".equals(methode);
            HttpResponse<String> reponse = decor.api.requete(methode, chemin, decor.patron.entetes(),
                    corps ? "application/json" : null, corps ? motif("MANUEL") : null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : Bruno et Chloé ABANDON, Patron abandonne Tom MANUEL : 200 TERMINEE ; Course TERMINEE, demarree_le inchangé, aucun VAINQUEUR ; journal « Abandon manuel » puis « Fin de course ... aucun vainqueur. » ; pilotage 24 champs, 0 vainqueur ; Course Y inchangée")
    void ca8_dernier_coureur_termine_la_course() throws Exception {
        String x = decor.courseXDemarree();
        String y = decor.courseYDemarree();
        abandonnerParSql(x, decor.idBruno, "VOLONTAIRE", 1);
        abandonnerParSql(x, decor.idChloe, "AUTRE", 1);
        Object demarreeLe = decor.jdbc.queryForObject("select demarree_le from course where id = ?",
                java.sql.Timestamp.class, UUID.fromString(x));
        String tom = inscriptionDe(x, decor.idTom);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(20));
        decor.demarrerJournal();

        HttpResponse<String> reponse = abandonner(decor.patron, x, tom, motif("MANUEL"));

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(corps(reponse).get("statutCourse").asString()).isEqualTo("TERMINEE");
        assertThat(corps(reponse).get("boucleAbandon").asInt()).isEqualTo(1);
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:MANUEL:1");
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
        assertThat(decor.jdbc.queryForObject("select demarree_le from course where id = ?",
                java.sql.Timestamp.class, UUID.fromString(x))).isEqualTo(demarreeLe);
        assertThat(decor.jdbc.queryForObject("select count(*) from inscription where statut = 'VAINQUEUR'",
                Integer.class)).isZero();
        assertThat(decor.statutCourse(y)).isEqualTo("EN_COURS");
        assertThat(decor.etat(y, decor.idAlice)).isEqualTo("EN_COURSE:-:-");
        assertThat(lignesAbandonManuel()).containsExactly(
                "Abandon manuel : course " + x + ", inscription " + tom + ", motif MANUEL, boucle 1.");
        assertThat(lignesInfo("Fin de course")).containsExactly(
                "Fin de course : course " + x + ", boucle 1, aucun vainqueur.");
        List<String> jetons = decor.jdbc.queryForList("select jeton_qr from inscription", String.class);
        for (String ligne : toutesLignesInfo()) {
            assertThat(ligne).doesNotContain("Tom", "Bruno", "Chloé", DecorAbandon.NOM_X).doesNotContain(jetons);
        }

        JsonNode pilotage = corps(decor.pilotage(decor.patron, x));
        assertThat(pilotage.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_PILOTAGE);
        assertThat(pilotage.get("statut").asString()).isEqualTo("TERMINEE");
        assertThat(pilotage.get("nombreVainqueurs").asInt()).isZero();
        assertThat(pilotage.get("inscritsVainqueurs").size()).isZero();
        assertThat(pilotage.get("inscritsEnCourse").size()).isZero();
        assertThat(pilotage.get("nombreAbandons").asInt()).isEqualTo(3);
        assertThat(pilotage.get("inscritsEnAbandon").size()).isEqualTo(3);
        assertThat(pilotage.get("nombreInscrits").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA8 : Course à un seul inscrit : son abandon la termine ; un second abandon : 409 COURSE_TERMINEE")
    void ca8_un_seul_inscrit() throws Exception {
        String course = decor.creerCourse("Course solo", 1, 5);
        assertThat(decor.sinscrire(decor.bruno, course).statusCode()).isEqualTo(201);
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + course + "/demarrage",
                decor.patron.entetes(), null, null).statusCode()).isEqualTo(200);
        String bruno = inscriptionDe(course, decor.idBruno);

        HttpResponse<String> premier = abandonner(decor.patron, course, bruno, motif("VOLONTAIRE"));
        HttpResponse<String> second = abandonner(decor.patron, course, bruno, motif("VOLONTAIRE"));

        assertThat(premier.statusCode()).as(premier.body()).isEqualTo(200);
        assertThat(corps(premier).get("statutCourse").asString()).isEqualTo("TERMINEE");
        assertErreur(second, 409, "COURSE_TERMINEE");
        assertThat(decor.statutCourse(course)).isEqualTo("TERMINEE");
    }

    @Test
    @DisplayName("CA8 : Bruno et Chloé EN_COURSE, l'abandon de Tom laisse la Course EN_COURS ; l'avant-dernier abandon aussi, la dernière restante n'est pas VAINQUEUR ; aucune ligne « Fin de course »")
    void ca8_il_reste_des_coureurs() throws Exception {
        String x = decor.courseXDemarree();
        decor.demarrerJournal();

        HttpResponse<String> tom = abandonner(decor.patron, x, inscriptionDe(x, decor.idTom), motif("AUTRE"));
        HttpResponse<String> bruno = abandonner(decor.patron, x, inscriptionDe(x, decor.idBruno), motif("MANUEL"));

        assertThat(corps(tom).get("statutCourse").asString()).isEqualTo("EN_COURS");
        assertThat(corps(bruno).get("statutCourse").asString()).isEqualTo("EN_COURS");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        assertThat(lignesInfo("Fin de course")).isEmpty();
    }

    @Test
    @DisplayName("CA8 : atomicité : si l'écriture de la Course échoue, 500 ERREUR_INTERNE et l'Inscription reste EN_COURSE (tout ou rien)")
    void ca8_atomicite() throws Exception {
        String x = decor.courseXDemarree();
        abandonnerParSql(x, decor.idBruno, "VOLONTAIRE", 1);
        abandonnerParSql(x, decor.idChloe, "AUTRE", 1);
        decor.jdbc.execute("create function test_echec_fin_course() returns trigger as $$ begin "
                + "raise exception 'echec simule'; end; $$ language plpgsql");
        decor.jdbc.execute("create trigger test_echec_fin_course before update on course for each row "
                + "execute function test_echec_fin_course()");
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        HttpResponse<String> reponse = abandonner(decor.patron, x, inscriptionDe(x, decor.idTom), motif("MANUEL"));

        assertErreur(reponse, 500, "ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("echec simule");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(lignesInfo("Fin de course")).isEmpty();
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : 20 essais, abandon manuel de Tom {} et contrôle automatique à 14:01:15.000Z simultanés : état cohérent (Tom MANUEL ou HORS_DELAI Boucle 1), abandon 200 ou 409, jamais 500, jamais de Course TERMINEE avec une Inscription EN_COURSE")
    void ca9_abandon_et_controle_automatique_simultanes() throws Exception {
        int manuelsGagnants = 0;
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course concurrente " + essai, 5);
            decor.poserPassage(x, decor.idBruno, 1);
            decor.poserPassage(x, decor.idChloe, 1);
            decor.horloge.fixer(DECISION_BOUCLE_1);
            String tom = inscriptionDe(x, decor.idTom);

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> abandonner(decor.patron, x, tom, motif("MANUEL")),
                    () -> {
                        controler.executer(UUID.fromString(x));
                        return null;
                    });

            HttpResponse<String> abandon = resultats.get(0);
            String etatTom = decor.etat(x, decor.idTom);
            if (abandon.statusCode() == 200) {
                assertThat(etatTom).as("essai " + essai).isEqualTo("ABANDON:MANUEL:1");
                manuelsGagnants++;
            } else {
                assertThat(etatTom).as("essai " + essai).isEqualTo("ABANDON:HORS_DELAI:1");
                assertThat(abandon.statusCode()).as(abandon.body()).isEqualTo(409);
                assertThat(corps(abandon).get("code").asString()).isIn("INSCRIPTION_NON_EN_COURSE",
                        "COURSE_TERMINEE");
            }
            assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
            assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
            assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
            assertThat(courseTermineeAvecCoureurEnCourse()).isZero();
        }
        assertThat(manuelsGagnants).isBetween(0, 20);
    }

    @Test
    @DisplayName("CA9 : 20 essais, Tom seul EN_COURSE ({}), abandon et contrôle simultanés : soit Tom MANUEL et Course TERMINEE sans vainqueur, soit Tom VAINQUEUR et abandon 409 COURSE_TERMINEE ; jamais 500, jamais de Course TERMINEE avec une Inscription EN_COURSE")
    void ca9_dernier_coureur_et_controle_simultanes() throws Exception {
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course dernier " + essai, 5);
            abandonnerParSql(x, decor.idBruno, "VOLONTAIRE", 1);
            abandonnerParSql(x, decor.idChloe, "AUTRE", 1);
            decor.horloge.fixer(DECISION_BOUCLE_1);
            String tom = inscriptionDe(x, decor.idTom);

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> abandonner(decor.patron, x, tom, motif("MANUEL")),
                    () -> {
                        controler.executer(UUID.fromString(x));
                        return null;
                    });

            HttpResponse<String> abandon = resultats.get(0);
            String etatTom = decor.etat(x, decor.idTom);
            if (abandon.statusCode() == 200) {
                assertThat(etatTom).as("essai " + essai).isEqualTo("ABANDON:MANUEL:1");
                assertThat(decor.jdbc.queryForObject("select count(*) from inscription where course_id = ? "
                        + "and statut = 'VAINQUEUR'", Integer.class, UUID.fromString(x))).isZero();
            } else {
                assertThat(etatTom).as("essai " + essai).isEqualTo("VAINQUEUR:-:-");
                assertErreur(abandon, 409, "COURSE_TERMINEE");
            }
            assertThat(decor.statutCourse(x)).as("essai " + essai).isEqualTo("TERMINEE");
            assertThat(courseTermineeAvecCoureurEnCourse()).isZero();
        }
    }

    @Test
    @DisplayName("CA9 : 20 essais, scan de Tom (Boucle 1) et abandon simultanés : soit 201 puis abandon Boucle 2 avec 1 Passage, soit abandon Boucle 1 puis scan 409 INSCRIPTION_NON_EN_COURSE sans Passage ; jamais de Passage après l'abandon")
    void ca9_scan_et_abandon_simultanes() throws Exception {
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course scan " + essai, 5);
            decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
            String tom = inscriptionDe(x, decor.idTom);
            String jeton = decor.jeton(x, decor.idTom);

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> abandonner(decor.patron, x, tom, motif("MANUEL")),
                    () -> decor.scanner(decor.leo, x, jeton, UUID.randomUUID(), 0));

            HttpResponse<String> abandon = resultats.get(0);
            HttpResponse<String> scan = resultats.get(1);
            assertThat(abandon.statusCode()).as(abandon.body()).isEqualTo(200);
            if (scan.statusCode() == 201) {
                assertThat(decor.etat(x, decor.idTom)).as("essai " + essai).isEqualTo("ABANDON:MANUEL:2");
                assertThat(decor.compterPassages(x, decor.idTom)).isEqualTo(1);
            } else {
                assertErreur(scan, 409, "INSCRIPTION_NON_EN_COURSE");
                assertThat(decor.etat(x, decor.idTom)).as("essai " + essai).isEqualTo("ABANDON:MANUEL:1");
                assertThat(decor.compterPassages(x, decor.idTom)).isZero();
            }
        }
    }

    @Test
    @DisplayName("CA9 : 20 essais, deux abandons simultanés du même coureur : un 200 et un 409 INSCRIPTION_NON_EN_COURSE, une seule ligne de journal par essai, jamais 500")
    void ca9_deux_abandons_simultanes() throws Exception {
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course double " + essai, 5);
            String tom = inscriptionDe(x, decor.idTom);
            decor.demarrerJournal();

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> abandonner(decor.patron, x, tom, motif("MANUEL")),
                    () -> abandonner(decor.nadia, x, tom, motif("VOLONTAIRE")));

            List<Integer> statuts = resultats.stream().map(HttpResponse::statusCode)
                    .sorted().toList();
            assertThat(statuts).as("essai " + essai).containsExactly(200, 409);
            HttpResponse<String> refus = resultats.stream()
                    .filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
            assertErreur(refus, 409, "INSCRIPTION_NON_EN_COURSE");
            assertThat(lignesAbandonManuel()).hasSize(1);
            assertThat(decor.etat(x, decor.idTom)).startsWith("ABANDON:").endsWith(":1");
            decor.arreterJournal();
        }
    }

    private int courseTermineeAvecCoureurEnCourse() {
        return decor.jdbc.queryForObject("select count(*) from inscription i join course c on c.id = i.course_id "
                + "where c.statut = 'TERMINEE' and i.statut = 'EN_COURSE'", Integer.class);
    }

    /** Lance les deux actions en même temps (barrière) et rend leurs résultats ; toute exception fait échouer. */
    private static List<HttpResponse<String>> lancerEnParallele(Callable<HttpResponse<String>> premiere,
                                                                Callable<HttpResponse<String>> seconde)
            throws Exception {
        ExecutorService executeur = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barriere = new CyclicBarrier(2);
            List<Future<HttpResponse<String>>> futurs = new ArrayList<>();
            for (Callable<HttpResponse<String>> action : List.of(premiere, seconde)) {
                futurs.add(executeur.submit(() -> {
                    barriere.await(10, TimeUnit.SECONDS);
                    return action.call();
                }));
            }
            List<HttpResponse<String>> resultats = new ArrayList<>();
            for (Future<HttpResponse<String>> futur : futurs) {
                resultats.add(futur.get(30, TimeUnit.SECONDS));
            }
            return resultats;
        } finally {
            executeur.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : après l'abandon de Tom, Léo scanne son jeton (identifiantScan neuf) : 409 INSCRIPTION_NON_EN_COURSE, statut ABANDON, dossard 3, pseudo, aucun Passage ; le rejeu d'un identifiantScan antérieur reste 200 ; un scan valide de Bruno ne change aucun statut")
    void ca10_scan_d_un_coureur_abandonne() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
        UUID scanAnterieur = UUID.randomUUID();
        HttpResponse<String> premier = decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), scanAnterieur, 0);
        assertThat(premier.statusCode()).as(premier.body()).isEqualTo(201);
        assertThat(abandonner(decor.patron, x, inscriptionDe(x, decor.idTom), motif("MANUEL")).statusCode())
                .isEqualTo(200);
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:MANUEL:2");
        int passagesTom = decor.compterPassages(x, decor.idTom);

        HttpResponse<String> refus = decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), UUID.randomUUID(), 0);
        HttpResponse<String> rejeu = decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), scanAnterieur, 0);
        HttpResponse<String> bruno = decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), UUID.randomUUID(), 0);

        assertErreur(refus, 409, "INSCRIPTION_NON_EN_COURSE");
        assertThat(corps(refus).get("statut").asString()).isEqualTo("ABANDON");
        assertThat(corps(refus).get("dossard").asInt()).isEqualTo(3);
        assertThat(corps(refus).get("pseudo").asString()).isEqualTo("Tom");
        assertThat(decor.compterPassages(x, decor.idTom)).isEqualTo(passagesTom);
        assertThat(rejeu.statusCode()).as(rejeu.body()).isEqualTo(200);
        assertThat(bruno.statusCode()).as(bruno.body()).isEqualTo(201);
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:MANUEL:2");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA10 : Course TERMINEE par l'abandon du dernier coureur : le scan du jeton d'un coureur abandonné ou du jeton de Bruno donne 409 COURSE_TERMINEE")
    void ca10_scan_apres_fin_de_course() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
        for (UUID compte : List.of(decor.idBruno, decor.idChloe, decor.idTom)) {
            assertThat(abandonner(decor.patron, x, inscriptionDe(x, compte), motif("MANUEL")).statusCode())
                    .isEqualTo(200);
        }
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");

        assertErreur(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), UUID.randomUUID(), 0), 409,
                "COURSE_TERMINEE");
        assertErreur(decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), UUID.randomUUID(), 0), 409,
                "COURSE_TERMINEE");
        assertThat(decor.jdbc.queryForObject("select count(*) from passage", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA10 : pilotage après l'abandon de Bruno {1, 2} : absent de inscritsEnCourse, présent dans inscritsEnAbandon (boucleAbandon 3, motifAbandon MANUEL, nombreBouclesTerminees 2), nombreInscrits inchangé, 24 champs")
    void ca10_pilotage_apres_abandon() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String bruno = inscriptionDe(x, decor.idBruno);
        assertThat(abandonner(decor.patron, x, bruno, motif("MANUEL")).statusCode()).isEqualTo(200);

        JsonNode pilotage = corps(decor.pilotage(decor.nadia, x));

        assertThat(pilotage.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_PILOTAGE);
        assertThat(pilotage.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(pilotage.get("statut").asString()).isEqualTo("EN_COURS");
        List<String> enCourse = new ArrayList<>();
        pilotage.get("inscritsEnCourse").forEach(i -> enCourse.add(i.get("inscriptionId").asString()));
        assertThat(enCourse).hasSize(2).doesNotContain(bruno);
        assertThat(pilotage.get("nombreAbandons").asInt()).isEqualTo(1);
        JsonNode abandon = pilotage.get("inscritsEnAbandon").get(0);
        assertThat(abandon.get("inscriptionId").asString()).isEqualTo(bruno);
        assertThat(abandon.get("dossard").asInt()).isEqualTo(1);
        assertThat(abandon.get("boucleAbandon").asInt()).isEqualTo(3);
        assertThat(abandon.get("motifAbandon").asString()).isEqualTo("MANUEL");
        assertThat(abandon.get("nombreBouclesTerminees").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : databasechangelog se termine toujours par 0010 (aucune migration ajoutée) et un GET .../pilotage ne produit aucune ligne INFO")
    void ca11_non_regression() throws Exception {
        String x = decor.courseXDemarree();
        assertThat(decor.jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .endsWith("0009-passage", "0010-abandon-inscription");
        decor.demarrerJournal();

        assertThat(decor.pilotage(decor.patron, x).statusCode()).isEqualTo(200);

        assertThat(toutesLignesInfo()).isEmpty();
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : Alice et Léo (affecté à X) reçoivent 403 ACCES_REFUSE avec un jeton CSRF valide ; rien ne change en base")
    void ca12_roles_refuses_avec_csrf_valide() throws Exception {
        String x = decor.courseXDemarree();
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);

        assertErreur(abandonner(decor.leo, x, inscriptionDe(x, decor.idTom), motif("MANUEL")), 403, "ACCES_REFUSE");
        assertErreur(abandonner(decor.alice, x, inscriptionDe(x, decor.idTom), motif("MANUEL")), 403,
                "ACCES_REFUSE");
        assertErreur(abandonner(decor.tom, x, inscriptionDe(x, decor.idTom), motif("VOLONTAIRE")), 403,
                "ACCES_REFUSE");

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA12 : une série de scans valides, refusés et rejoués par Léo ne modifie ni statut, ni motif_abandon, ni boucle_abandon (jamais d'abandon via le scan)")
    void ca12_le_scan_ne_change_aucun_statut() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(10));
        abandonnerParSql(x, decor.idChloe, "HORS_DELAI", 1);
        List<Map<String, Object>> avant = decor.jdbc.queryForList(ETAT_INSCRIPTIONS);
        String statutCourse = decor.statutCourse(x);
        UUID rejoue = UUID.randomUUID();

        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), rejoue, 0).statusCode()).isEqualTo(201);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), rejoue, 0).statusCode()).isEqualTo(200);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(409);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idChloe), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(409);
        assertThat(decor.scanner(decor.leo, x, "jeton-inconnu", UUID.randomUUID(), 0).statusCode()).isEqualTo(404);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(201);
        assertThat(decor.api.requete("GET", "/api/benevole/courses", decor.leo.enteteLecture(), null, null)
                .statusCode()).isEqualTo(200);

        assertThat(decor.jdbc.queryForList(ETAT_INSCRIPTIONS)).isEqualTo(avant);
        assertThat(decor.statutCourse(x)).isEqualTo(statutCourse);
    }
}
