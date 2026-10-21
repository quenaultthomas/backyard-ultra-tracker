package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai.Resultat;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
 * Contrat de l'incrément 7.1 (scan tardif, délai de grâce) : CA11 à CA16, serveur réel. Le contrat vient de la spec :
 * Boucles de 60 min (délai de grâce 3 min), départ à t0 = 14:00:00Z. Le planificateur est désactivé par le socle des
 * tests : le cas d'usage de contrôle est appelé directement à l'instant du Clock de test. Alice (A) et Bruno (B) sont
 * les deux coureurs en lice (dossards 1 et 2), Léo est le bénévole affecté.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class ScanTardifGraceIntegrationTest {

    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Duration MINUTE = Duration.ofMinutes(1);
    static final Instant T0 = DecorAbandon.T0;
    static final int AGE_DEUX_MINUTES = 120_000;

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

    private void aMinutes(double minutes) {
        horloge.fixer(T0.plusMillis(Math.round(minutes * 60_000)));
    }

    /** Course de Boucles de 60 min démarrée à t0 : Alice et Bruno inscrits, Léo affecté. */
    private String courseDemarree(String nom, int maxBoucles) throws Exception {
        String course = decor.creerCourse(nom, 60, maxBoucles);
        assertThat(decor.affecter(course, decor.idLeo).statusCode()).isEqualTo(200);
        assertThat(decor.sinscrire(decor.alice, course).statusCode()).isEqualTo(201);
        assertThat(decor.sinscrire(decor.bruno, course).statusCode()).isEqualTo(201);
        horloge.fixer(T0);
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + course + "/demarrage",
                decor.patron.entetes(), null, null).statusCode()).isEqualTo(200);
        return course;
    }

    private HttpResponse<String> scanner(String course, UUID compte, long age) throws Exception {
        return decor.scanner(decor.leo, course, decor.jeton(course, compte), UUID.randomUUID(), age);
    }

    private void scannerAvecSucces(String course, UUID compte, int boucle, long age) throws Exception {
        HttpResponse<String> reponse = scanner(course, compte, age);
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(201);
        assertThat(decor.json.readTree(reponse.body()).get("numeroBoucle").asInt()).isEqualTo(boucle);
    }

    private HttpResponse<String> suivi(String course) throws Exception {
        return decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of(), null, null);
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(decor.json.readTree(reponse.body()).get("code").asString()).isEqualTo(code);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : A scanné sur 1, B non ; à 11:01:00 (grâce de la Boucle 1) le scan de B d'âge 120000 ms est enregistré sur la Boucle 1 (201) ; à 11:03:01 après le contrôle, A et B restent EN_COURSE et la Course EN_COURS")
    void ca11_scan_tardif_pendant_la_grace_est_accepte_et_la_course_continue() throws Exception {
        String x = courseDemarree("Backyard tardif", 5);
        aMinutes(10);
        scannerAvecSucces(x, decor.idAlice, 1, 0);
        aMinutes(61);
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(0, 0));

        scannerAvecSucces(x, decor.idBruno, 1, AGE_DEUX_MINUTES);

        assertThat(decor.compterPassages(x, decor.idBruno)).isEqualTo(1);
        horloge.fixer(T0.plus(MINUTE.multipliedBy(63)).plusSeconds(1));
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(0, 0));
        assertThat(decor.etat(x, decor.idAlice)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : A scanné sur 1, B non ; à 11:02:59.999 puis 11:03:00 : rien tant que la grâce court ; à 11:03:01 B ABANDON HORS_DELAI Boucle 1, A VAINQUEUR, Course TERMINEE")
    void ca12_decision_seulement_apres_la_grace() throws Exception {
        String x = courseDemarree("Backyard decision", 5);
        aMinutes(10);
        scannerAvecSucces(x, decor.idAlice, 1, 0);

        aMinutes(62.0 + 59.0 / 60);
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(0, 0));
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        horloge.fixer(T0.plus(MINUTE.multipliedBy(63)).minusMillis(1));
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(0, 0));
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");

        horloge.fixer(T0.plus(MINUTE.multipliedBy(63)).plusSeconds(1));
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(1, 1));

        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idAlice)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
    }

    @Test
    @DisplayName("CA12 : à l'instant exact départ(N+1) + grâce (11:03:00.000) la Boucle 1 est tranchée")
    void ca12_instant_exact_de_bascule() throws Exception {
        String x = courseDemarree("Backyard bascule", 5);
        aMinutes(10);
        scannerAvecSucces(x, decor.idAlice, 1, 0);

        horloge.fixer(T0.plus(MINUTE.multipliedBy(63)));
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(1, 1));

        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idAlice)).isEqualTo("VAINQUEUR:-:-");
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : la décision de CA12 prise, le scan de B (âge 120000 ms) à 11:03:30 est refusé 409 COURSE_TERMINEE et ne crée aucun Passage")
    void ca13_scan_apres_la_decision_est_refuse() throws Exception {
        String x = courseDemarree("Backyard apres grace", 5);
        aMinutes(10);
        scannerAvecSucces(x, decor.idAlice, 1, 0);
        horloge.fixer(T0.plus(MINUTE.multipliedBy(63)).plusSeconds(1));
        controler.executer(UUID.fromString(x));
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");

        horloge.fixer(T0.plus(MINUTE.multipliedBy(63)).plusSeconds(30));
        HttpResponse<String> refus = scanner(x, decor.idBruno, AGE_DEUX_MINUTES);

        assertErreur(refus, 409, "COURSE_TERMINEE");
        assertThat(decor.compterPassages(x, decor.idBruno)).isZero();
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("ABANDON:HORS_DELAI:1");
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : 2 Boucles max, A et B avec Passage sur 1, A sur 2 ; le scan de B sur 2 (âge 120000 ms) à 12:01:00 est accepté (201) ; la Course reste EN_COURS pendant la grâce ; à 12:03:01 A et B sont VAINQUEUR, Course TERMINEE")
    void ca14_derniere_boucle_scan_tardif_vainqueurs_partages() throws Exception {
        String x = courseDemarree("Backyard derniere", 2);
        aMinutes(10);
        scannerAvecSucces(x, decor.idAlice, 1, 0);
        scannerAvecSucces(x, decor.idBruno, 1, 0);
        aMinutes(70);
        scannerAvecSucces(x, decor.idAlice, 2, 0);
        aMinutes(120.5);
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(0, 0));
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        aMinutes(121);

        scannerAvecSucces(x, decor.idBruno, 2, AGE_DEUX_MINUTES);

        horloge.fixer(T0.plus(MINUTE.multipliedBy(123)).minusMillis(1));
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(0, 0));
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        horloge.fixer(T0.plus(MINUTE.multipliedBy(123)).plusSeconds(1));
        assertThat(controler.executer(UUID.fromString(x))).isEqualTo(new Resultat(0, 2));
        assertThat(decor.etat(x, decor.idAlice)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
    }

    @Test
    @DisplayName("CA14 : dernière Boucle, un scan dont l'instant réel est à la fin de la Boucle 2 (12:00:00) reste refusé 409 TEMPS_DE_COURSE_ECOULE pendant la grâce")
    void ca14_derniere_boucle_scan_reel_apres_la_fin_refuse() throws Exception {
        String x = courseDemarree("Backyard fin de boucle", 2);
        aMinutes(10);
        scannerAvecSucces(x, decor.idAlice, 1, 0);
        scannerAvecSucces(x, decor.idBruno, 1, 0);
        aMinutes(70);
        scannerAvecSucces(x, decor.idAlice, 2, 0);
        aMinutes(121);

        HttpResponse<String> refus = scanner(x, decor.idBruno, 60_000);

        assertErreur(refus, 409, "TEMPS_DE_COURSE_ECOULE");
        assertThat(decor.compterPassages(x, decor.idBruno)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : à 11:00:30, GET /suivi (anonyme) et GET /pilotage (ADMIN) exposent boucleEnAttenteDeDecision = 1 ; à 10:59:59 et à 11:04:00 la valeur est null ; aucun champ nominatif ajouté au suivi")
    void ca15_indicateur_d_attente_de_decision() throws Exception {
        String x = courseDemarree("Backyard attente", 5);
        aMinutes(10);
        scannerAvecSucces(x, decor.idAlice, 1, 0);

        horloge.fixer(T0.plus(MINUTE.multipliedBy(60)).minusSeconds(1));
        assertThat(attente(suivi(x))).isNull();
        assertThat(attente(decor.pilotage(decor.nadia, x))).isNull();

        horloge.fixer(T0.plus(MINUTE.multipliedBy(60)).plusSeconds(30));
        HttpResponse<String> publicEnAttente = suivi(x);
        assertThat(publicEnAttente.statusCode()).isEqualTo(200);
        assertThat(attente(publicEnAttente)).isEqualTo(1);
        assertThat(attente(decor.pilotage(decor.nadia, x))).isEqualTo(1);
        assertThat(attente(decor.pilotage(decor.patron, x))).isEqualTo(1);
        JsonNode corps = decor.json.readTree(publicEnAttente.body());
        assertThat(corps.propertyNames()).doesNotContain("jetonQr", "compteId", "motDePasse", "pseudos");
        assertThat(publicEnAttente.body()).doesNotContain(decor.jeton(x, decor.idAlice))
                .doesNotContain(decor.jeton(x, decor.idBruno)).doesNotContain(decor.idAlice.toString());

        horloge.fixer(T0.plus(MINUTE.multipliedBy(64)));
        assertThat(attente(suivi(x))).isNull();
        assertThat(attente(decor.pilotage(decor.nadia, x))).isNull();
    }

    @Test
    @DisplayName("CA15 : Course non démarrée (EN_PREPARATION) : GET /suivi expose boucleEnAttenteDeDecision = null ; Course TERMINEE : null")
    void ca15_course_non_demarree_ou_terminee_sans_attente() throws Exception {
        String preparation = decor.creerCourse("Backyard prepa", 60, 5);

        HttpResponse<String> reponse = suivi(preparation);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.has("boucleEnAttenteDeDecision")).isTrue();
        assertThat(corps.get("boucleEnAttenteDeDecision").isNull()).isTrue();

        String terminee = courseDemarree("Backyard terminee", 5);
        horloge.fixer(T0.plus(MINUTE.multipliedBy(63)));
        controler.executer(UUID.fromString(terminee));
        assertThat(decor.statutCourse(terminee)).isEqualTo("TERMINEE");
        assertThat(attente(suivi(terminee))).isNull();
        assertThat(attente(decor.pilotage(decor.patron, terminee))).isNull();
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : GET /suivi d'une Course inexistante : 404 COURSE_INTROUVABLE ; GET /pilotage sans session : 401 ; en COUREUR : 403 ACCES_REFUSE (comme avant)")
    void ca16_codes_inchanges() throws Exception {
        String x = courseDemarree("Backyard securite", 5);
        aMinutes(61);

        assertErreur(suivi(ID_INCONNU), 404, "COURSE_INTROUVABLE");
        HttpResponse<String> anonyme = decor.api.requete("GET", DecorAbandon.CHEMIN + "/" + x + "/pilotage", Map.of(),
                null, null);
        assertThat(anonyme.statusCode()).isEqualTo(401);
        assertErreur(decor.pilotage(decor.alice, x), 403, "ACCES_REFUSE");
        assertErreur(decor.pilotage(decor.leo, x), 403, "ACCES_REFUSE");
    }

    private Integer attente(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.has("boucleEnAttenteDeDecision")).as("champ présent").isTrue();
        JsonNode valeur = corps.get("boucleEnAttenteDeDecision");
        return valeur.isNull() ? null : valeur.asInt();
    }
}
