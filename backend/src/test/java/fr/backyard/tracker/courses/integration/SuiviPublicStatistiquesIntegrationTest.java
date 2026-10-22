package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 6.3 (statistiques du suivi public) : CA11 à CA16, serveur réel. Le contrat est celui de la
 * spec : GET /api/courses/{id}/suivi, public, liste fermée de 22 champs (7.1 : + boucleEnAttenteDeDecision) (RG1), cumuls, allure, durée finale
 * dérivés des Passages (RG4 à RG7), 3 instructions SQL de plus qu'en EN_PREPARATION (RG9). Courses de 60 min,
 * d = 6700 m, h = 35 m, t0 = 12/10/2026 14:00:00Z ; le contrôle de fin de course est celui du planificateur
 * (cas d'usage {@link AbandonnerCoureursHorsDelai} qu'il appelle), exécuté ici à la main sur le Clock de test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuiviPublicStatistiquesIntegrationTest {

    static final List<String> CHAMPS = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur",
            "nombreMaxBoucles", "boucleCourante", "derniereBoucle", "boucleMaxTerminee", "boucleEnAttenteDeDecision",
            "tempsAvantFinBoucleMillisecondes", "dureeEcouleeMillisecondes", "coureursEnCourse", "abandons",
            "vainqueurs", "distanceBoucleMetres", "denivelePositifBoucleMetres", "bouclesTerminees",
            "distanceCumuleeMetres", "denivelePositifCumuleMetres", "allureSecondesParKilometre",
            "dureeFinaleMillisecondes");
    static final List<String> CHAMPS_STATISTIQUES = List.of("distanceBoucleMetres", "denivelePositifBoucleMetres",
            "bouclesTerminees", "distanceCumuleeMetres", "denivelePositifCumuleMetres",
            "allureSecondesParKilometre", "dureeFinaleMillisecondes");
    static final Instant T0 = DecorAbandon.T0;
    /** Délai de grâce 7.1 (RG1) d'une Boucle de 90 min : min(3 min, 90 min / 4) = 3 min. */
    static final Duration DELAI_DE_GRACE = Duration.ofMinutes(3);
    static final int DUREE_MINUTES = 60;
    static final int DISTANCE = 6700;
    static final int DENIVELE = 35;

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
    String empreinte;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
        empreinte = encodeur.encode(DecorAbandon.MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        decor.arreterJournal();
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : X EN_COURS (Alice Boucle 1 SCAN à t0 + 50 min, Bruno Boucle 1 SCAN à t0 + 40 min) lue à t0 + 61 min sans session : 200, no-store, aucun Set-Cookie, 22 champs (7.1 : + boucleEnAttenteDeDecision), statistiques 1 / 6700 / 35 / 403 / null, aucune fuite")
    void ca11_statistiques_sans_session() throws Exception {
        UUID x = coursesql("X", "EN_COURS", T0);
        UUID alice = decor.insererInscription(x, decor.idAlice, 1, "EN_COURSE");
        UUID bruno = decor.insererInscription(x, decor.idBruno, 2, "EN_COURSE");
        passageScan(alice, 1, T0.plus(Duration.ofMinutes(50)));
        passageScan(bruno, 1, T0.plus(Duration.ofMinutes(40)));
        horloge.fixer(T0.plus(Duration.ofMinutes(61)));
        String jetonAlice = decor.jeton(x.toString(), decor.idAlice);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");

        HttpResponse<String> reponse = suivi(x.toString(), Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(22).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(corps.get("distanceBoucleMetres").asInt()).isEqualTo(6700);
        assertThat(corps.get("denivelePositifBoucleMetres").asInt()).isEqualTo(35);
        assertThat(corps.get("bouclesTerminees").asInt()).isEqualTo(1);
        assertThat(corps.get("distanceCumuleeMetres").asLong()).isEqualTo(6700L);
        assertThat(corps.get("denivelePositifCumuleMetres").asLong()).isEqualTo(35L);
        assertThat(corps.get("allureSecondesParKilometre").asLong()).isEqualTo(403L);
        assertThat(corps.get("dureeFinaleMillisecondes").isNull()).isTrue();
        assertThat(reponse.body()).doesNotContain("jeton", "compteId", "inscriptionId", "scanneLe", "origine",
                "SCAN", "CORRECTION", jetonAlice, decor.idAlice.toString(), alice.toString());
        assertThat(reponse.body().toLowerCase(Locale.ROOT)).doesNotContain("passage");
        assertThat(decor.jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : EN_PREPARATION, EN_COURS sans heure de départ (SQL) et TERMINEE : 200 jamais 409, paramètres de Boucle renseignés, zéros et null ; TERMINEE aux statistiques finales ; même corps pour ADMIN_MASTER, ADMIN, BENEVOLE non affecté, COUREUR et sans session")
    void ca12_etats_et_meme_corps_pour_tous() throws Exception {
        UUID preparation = coursesql("Préparation", "EN_PREPARATION", null);
        UUID sansDepart = coursesql("Sans départ", "EN_COURS", null);
        decor.insererInscription(preparation, decor.idAlice, 1, "EN_COURSE");
        decor.insererInscription(sansDepart, decor.idAlice, 1, "EN_COURSE");
        String terminee = courseTerminee("Terminée (a)", 5, Parcours.A);
        horloge.fixer(T0.plus(Duration.ofHours(10)));

        for (UUID id : List.of(preparation, sansDepart)) {
            HttpResponse<String> reponse = suivi(id.toString(), Map.of());
            assertThat(reponse.statusCode()).isEqualTo(200);
            JsonNode corps = decor.json.readTree(reponse.body());
            assertThat(corps.propertyNames()).hasSize(22);
            assertStatistiques(corps, 0, 0, 0, null, null);
        }
        HttpResponse<String> anonyme = suivi(terminee, Map.of());
        assertThat(anonyme.statusCode()).isEqualTo(200);
        JsonNode attendu = decor.json.readTree(anonyme.body());
        assertThat(attendu.get("statut").asString()).isEqualTo("TERMINEE");
        assertStatistiques(attendu, 3, 20_100L, 105L, 90L, 10_800_000L);
        for (Session session : List.of(decor.patron, decor.nadia, decor.leo, decor.alice)) {
            HttpResponse<String> reponse = suivi(terminee, session.enteteLecture());
            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(decor.json.readTree(reponse.body())).isEqualTo(attendu);
        }
        assertThat(decor.jdbc.queryForObject("select statut from course where id = ?", String.class, preparation))
                .isEqualTo("EN_PREPARATION");
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 (a) : Alice seule scannée aux Boucles 1 à 3, Bruno aux Boucles 1 à 2 : TERMINEE, 3 Boucles, 20100 m, 105 m D+, 10 800 000 ms ; mêmes statistiques après avance de 10 h")
    void ca13_vainqueur_unique() throws Exception {
        verifierFigees(courseTerminee("Cas a", 5, Parcours.A), 3, 20_100L, 105L, 90L, 10_800_000L);
    }

    @Test
    @DisplayName("CA13 (b) : Alice et Bruno scannés sur la Boucle 1, personne sur la Boucle 2 (vainqueurs partagés) : 1 Boucle, 6700 m, 3 600 000 ms ; mêmes statistiques après avance de 10 h")
    void ca13_vainqueurs_partages_sans_passage_sur_la_boucle_n() throws Exception {
        verifierFigees(courseTerminee("Cas b", 5, Parcours.B), 1, 6_700L, 35L, 90L, 3_600_000L);
    }

    @Test
    @DisplayName("CA13 (c) : M = 2, Alice et Bruno scannés sur les Boucles 1 et 2 : 2 Boucles, 7 200 000 ms ; mêmes statistiques après avance de 10 h")
    void ca13_fin_par_nombre_max_de_boucles() throws Exception {
        verifierFigees(courseTerminee("Cas c", 2, Parcours.C), 2, 13_400L, 70L, 90L, 7_200_000L);
    }

    @Test
    @DisplayName("CA13 (d) : personne scanné : TERMINEE, 0 Boucle, cumuls 0, allure null, durée 0 ; mêmes statistiques après avance de 10 h")
    void ca13_aucun_passage() throws Exception {
        verifierFigees(courseTerminee("Cas d", 5, Parcours.D), 0, 0L, 0L, null, 0L);
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : 3 puis 60 Inscriptions de 3 Passages chacune : même nombre d'instructions SQL, exactement 3 de plus qu'en EN_PREPARATION et 2 de plus qu'en EN_COURS sans Inscription, pas de lecture Passage par Passage ni par Inscription")
    void ca14_nombre_d_instructions_independant_des_inscrits_et_des_passages() throws Exception {
        UUID petite = coursesql("Petite", "EN_COURS", T0);
        UUID grande = coursesql("Grande", "EN_COURS", T0);
        UUID sansInscription = coursesql("Sans inscription", "EN_COURS", T0);
        UUID preparation = coursesql("Préparation", "EN_PREPARATION", null);
        garnir(petite, "A", 3);
        garnir(grande, "B", 60);
        for (int i = 1; i <= 3; i++) {
            decor.insererInscription(preparation, compte("C" + i), i, "EN_COURSE");
        }
        horloge.fixer(T0.plus(Duration.ofMinutes(3 * 60 + 30)));
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(suivi(grande.toString(), Map.of()).statusCode()).isEqualTo(200);

        long pour3 = requetes(statistiques, petite);
        long pour60 = requetes(statistiques, grande);
        long enPreparation = requetes(statistiques, preparation);
        long sansCoureur = requetes(statistiques, sansInscription);

        assertThat(pour60).as("indépendant du nombre d'inscrits et de Passages (pas de N+1)").isEqualTo(pour3);
        assertThat(pour3 - enPreparation).as("Inscriptions, pseudos, Passages").isEqualTo(3);
        assertThat(sansCoureur - enPreparation).as("sans Inscription : seules les Inscriptions sont lues").isEqualTo(1);
        assertThat(pour3 - sansCoureur).as("pseudos et Passages omis sans Inscription").isEqualTo(2);
        JsonNode corps = corps(grande.toString());
        assertThat(corps.get("bouclesTerminees").asInt()).isEqualTo(3);
        assertThat(corps.get("coureursEnCourse")).hasSize(60);

        // une seule instruction lit les Passages : 180 Passages sur 60 Inscriptions ne coûtent pas une instruction de plus
        // que 9 Passages sur 3 Inscriptions (égalité pour3 / pour60 ci-dessus) et l'écart avec EN_PREPARATION est de 3.
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : abandon manuel de Bruno puis réintégration par Nadia (Passage CORRECTION) : l'allure, calculée sur les seuls Passages SCAN, est identique avant et après ; deux Courses X et Y (d = 1000) renvoient chacune leurs paramètres et statistiques")
    void ca15_allure_hors_corrections_et_isolation_entre_courses() throws Exception {
        horloge.fixer(T0);
        String x = demarreeAvecParametres("Course X statistiques", 5, DISTANCE, DENIVELE, decor.alice, decor.bruno, decor.chloe);
        String y = demarreeAvecParametres("Course Y statistiques", 5, 1000, 10, decor.alice);

        scannerA(y, decor.idAlice, 5);
        scannerA(x, decor.idAlice, 10);
        scannerA(x, decor.idChloe, 20);
        horloge.fixer(T0.plus(Duration.ofMinutes(30)));
        String bruno = decor.idInscription(x, decor.idBruno);
        HttpResponse<String> abandon = decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x + "/inscriptions/"
                + bruno + "/abandon", decor.nadia.entetes(), "application/json", "{\"motif\":\"MANUEL\"}");
        assertThat(abandon.statusCode()).as(abandon.body()).isEqualTo(200);
        horloge.fixer(T0.plus(Duration.ofMinutes(61)));
        assertThat(abandonner.executer(UUID.fromString(x)).vainqueurs()).isZero();
        horloge.fixer(T0.plus(Duration.ofMinutes(62)));

        JsonNode avant = corps(x);
        HttpResponse<String> reintegration = decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x
                + "/inscriptions/" + bruno + "/reintegration", decor.nadia.entetes(), null, null);
        assertThat(reintegration.statusCode()).as(reintegration.body()).isEqualTo(200);
        JsonNode apres = corps(x);

        assertThat(decor.jdbc.queryForObject("select count(*) from passage where origine = 'CORRECTION'",
                Integer.class)).isEqualTo(1);
        // Alice (10 min) et Chloé (20 min) : 1 800 000 / (2 x 6700) = 134,3
        assertStatistiques(avant, 1, 6_700L, 35L, 134L, null);
        assertStatistiques(apres, 1, 6_700L, 35L, 134L, null);
        assertThat(apres.get("allureSecondesParKilometre")).isEqualTo(avant.get("allureSecondesParKilometre"));
        JsonNode autre = corps(y);
        assertThat(autre.get("distanceBoucleMetres").asInt()).isEqualTo(1000);
        assertThat(autre.get("denivelePositifBoucleMetres").asInt()).isEqualTo(10);
        assertThat(autre.get("bouclesTerminees").asInt()).isEqualTo(1);
        assertThat(autre.get("distanceCumuleeMetres").asLong()).isEqualTo(1000L);
        assertThat(autre.get("denivelePositifCumuleMetres").asLong()).isEqualTo(10L);
        // Alice dans Y à 5 min : 300 000 / 1000 = 300 s/km, indépendant des Passages de X
        assertThat(autre.get("allureSecondesParKilometre").asLong()).isEqualTo(300L);
        assertThat(corps(x).get("distanceBoucleMetres").asInt()).isEqualTo(DISTANCE);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : lectures, scans, abandon manuel, réintégration et contrôle serveur : aucune ligne INFO ou supérieure ne contient un pseudo, un jeton ou un identifiant de Passage ; les lectures seules n'écrivent aucune ligne INFO")
    void ca16_journal_sans_pseudo_jeton_ni_passage() throws Exception {
        horloge.fixer(T0);
        String x = demarreeAvecParametres("Course X statistiques", 5, DISTANCE, DENIVELE, decor.alice, decor.bruno, decor.chloe);
        List<String> jetons = List.of(decor.jeton(x, decor.idAlice), decor.jeton(x, decor.idBruno),
                decor.jeton(x, decor.idChloe));
        UUID prepa = coursesql("Préparation", "EN_PREPARATION", null);
        decor.demarrerJournal();

        assertThat(suivi(prepa.toString(), Map.of()).statusCode()).isEqualTo(200);
        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);
        assertThat(decor.evenements()).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());

        scannerA(x, decor.idAlice, 10);
        scannerA(x, decor.idChloe, 20);
        horloge.fixer(T0.plus(Duration.ofMinutes(30)));
        String bruno = decor.idInscription(x, decor.idBruno);
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x + "/inscriptions/" + bruno + "/abandon",
                decor.nadia.entetes(), "application/json", "{\"motif\":\"MANUEL\"}").statusCode()).isEqualTo(200);
        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);
        horloge.fixer(T0.plus(Duration.ofMinutes(61)));
        abandonner.executer(UUID.fromString(x));
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x + "/inscriptions/" + bruno
                + "/reintegration", decor.nadia.entetes(), null, null).statusCode()).isEqualTo(200);
        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);
        assertThat(suivi(x, decor.alice.enteteLecture()).statusCode()).isEqualTo(200);

        List<String> idsPassages = decor.jdbc.queryForList("select id::text from passage", String.class);
        assertThat(idsPassages).isNotEmpty();
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : decor.evenements()) {
            if (e.getLevel().isGreaterOrEqual(Level.INFO)) {
                messages.add(e.getFormattedMessage());
            }
        }
        assertThat(messages).allSatisfy(m -> assertThat(m)
                .doesNotContain("Alice", "Bruno", "Chloé", "Léo", "Nadia").doesNotContain(jetons)
                .doesNotContain(idsPassages));
    }

    // ---------------------------------------------------------------- scénarios de fin de course

    enum Parcours { A, B, C, D }

    /**
     * Course TERMINEE obtenue par l'API (création, inscriptions, démarrage, scans), le contrôle de fin de course
     * étant exécuté à chaque fin de Boucle. Alice (dossard 1) et Bruno (2) y sont inscrits.
     */
    private String courseTerminee(String nom, int maxBoucles, Parcours parcours) throws Exception {
        horloge.fixer(T0);
        String course = demarreeAvecParametres(nom, maxBoucles, DISTANCE, DENIVELE, decor.alice, decor.bruno);
        int bouclesJouees = switch (parcours) {
            case A -> 3;
            case B -> 2;
            case C -> 2;
            case D -> 1;
        };
        for (int boucle = 1; boucle <= bouclesJouees; boucle++) {
            int debutMinutes = (boucle - 1) * DUREE_MINUTES;
            if (parcours != Parcours.D && (parcours != Parcours.B || boucle == 1)) {
                scannerA(course, decor.idAlice, debutMinutes + 10);
                if (parcours != Parcours.A || boucle <= 2) {
                    scannerA(course, decor.idBruno, debutMinutes + 10);
                }
            }
            horloge.fixer(T0.plus(Duration.ofMinutes((long) boucle * DUREE_MINUTES)).plus(DELAI_DE_GRACE)); // 7.1 : décision à départ(N+1) + grâce
            abandonner.executer(UUID.fromString(course));
        }
        assertThat(decor.statutCourse(course)).as(nom).isEqualTo("TERMINEE");
        return course;
    }

    private void verifierFigees(String course, int boucles, long distance, long denivele, Long allure, long duree)
            throws Exception {
        JsonNode premier = corps(course);
        assertThat(premier.get("statut").asString()).isEqualTo("TERMINEE");
        assertStatistiques(premier, boucles, distance, denivele, allure, duree);
        assertThat(premier.get("boucleCourante").isNull()).isTrue();
        assertThat(premier.get("dureeEcouleeMillisecondes").isNull()).isTrue();

        horloge.fixer(horloge.instant().plus(Duration.ofHours(10)));
        JsonNode second = corps(course);

        for (String champ : CHAMPS_STATISTIQUES) {
            assertThat(second.get(champ)).as(champ).isEqualTo(premier.get(champ));
        }
    }

    private void assertStatistiques(JsonNode corps, int boucles, long distance, long denivele, Long allure,
                                    Long duree) {
        assertThat(corps.get("distanceBoucleMetres").asInt()).isEqualTo(DISTANCE);
        assertThat(corps.get("denivelePositifBoucleMetres").asInt()).isEqualTo(DENIVELE);
        assertThat(corps.get("bouclesTerminees").asInt()).isEqualTo(boucles);
        assertThat(corps.get("distanceCumuleeMetres").asLong()).isEqualTo(distance);
        assertThat(corps.get("denivelePositifCumuleMetres").asLong()).isEqualTo(denivele);
        if (allure == null) {
            assertThat(corps.get("allureSecondesParKilometre").isNull()).isTrue();
        } else {
            assertThat(corps.get("allureSecondesParKilometre").asLong()).isEqualTo(allure);
        }
        if (duree == null) {
            assertThat(corps.get("dureeFinaleMillisecondes").isNull()).isTrue();
        } else {
            assertThat(corps.get("dureeFinaleMillisecondes").asLong()).isEqualTo(duree);
        }
    }

    // ---------------------------------------------------------------- outils

    /** Course créée par l'API (Léo affecté, coureurs inscrits) et démarrée à l'instant courant du Clock (t0). */
    private String demarreeAvecParametres(String nom, int maxBoucles, int distance, int denivele,
                                          Session... coureurs) throws Exception {
        ObjectNode corps = decor.json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", DecorAbandon.JOUR);
        corps.put("distanceBoucleMetres", distance);
        corps.put("dureeBoucleMinutes", DUREE_MINUTES);
        corps.put("denivelePositifBoucleMetres", denivele);
        corps.put("nombreMaxParticipants", 100);
        corps.put("nombreMaxBoucles", maxBoucles);
        HttpResponse<String> creation = decor.api.requete("POST", DecorAbandon.CHEMIN, decor.patron.entetes(),
                "application/json", corps.toString());
        assertThat(creation.statusCode()).as(creation.body()).isEqualTo(201);
        String course = decor.json.readTree(creation.body()).get("id").asString();
        assertThat(decor.affecter(course, decor.idLeo).statusCode()).isEqualTo(200);
        for (Session coureur : coureurs) {
            assertThat(decor.sinscrire(coureur, course).statusCode()).isEqualTo(201);
        }
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + course + "/demarrage",
                decor.patron.entetes(), null, null).statusCode()).isEqualTo(200);
        return course;
    }

    /** Scan par Léo via l'API, {@code minutesApresT0} minutes après le départ. */
    private void scannerA(String course, UUID compte, int minutesApresT0) throws Exception {
        horloge.fixer(T0.plus(Duration.ofMinutes(minutesApresT0)));
        HttpResponse<String> scan = decor.scanner(decor.leo, course, decor.jeton(course, compte), UUID.randomUUID(), 0);
        assertThat(scan.statusCode()).as(scan.body()).isEqualTo(201);
    }

    /** Course SQL (60 min, 6700 m, 35 m, 5 Boucles max). */
    private UUID coursesql(String nom, String statut, Instant demarreeLe) {
        UUID id = UUID.randomUUID();
        decor.jdbc.update("insert into course (id, nom, date_course, statut, distance_boucle_metres, "
                + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, "
                + "nombre_max_boucles, demarree_le) values (?, ?, ?, ?, ?, ?, ?, 100, 5, ?)", id, nom,
                Date.valueOf(DecorAbandon.JOUR), statut, DISTANCE, DUREE_MINUTES, DENIVELE,
                demarreeLe == null ? null : Timestamp.from(demarreeLe));
        return id;
    }

    private void passageScan(UUID inscription, int boucle, Instant scanneLe) {
        decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), inscription, boucle,
                Timestamp.from(scanneLe), "SCAN");
    }

    /** {@code nombre} Inscriptions de Comptes distincts, chacune avec 3 Passages SCAN (Boucles 1 à 3). */
    private void garnir(UUID course, String prefixe, int nombre) {
        for (int i = 1; i <= nombre; i++) {
            UUID inscription = decor.insererInscription(course, compte(prefixe + i), i, "EN_COURSE");
            for (int boucle = 1; boucle <= 3; boucle++) {
                passageScan(inscription, boucle, T0.plus(Duration.ofMinutes((boucle - 1) * 60L + 10)));
            }
        }
    }

    /** Compte coureur créé sans hachage coûteux. */
    private UUID compte(String pseudo) {
        UUID id = UUID.randomUUID();
        decor.jdbc.update(DecorAbandon.INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte,
                "COUREUR", Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private long requetes(Statistics statistiques, UUID course) throws Exception {
        long avant = statistiques.getPrepareStatementCount();
        assertThat(suivi(course.toString(), Map.of()).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    private HttpResponse<String> suivi(String id, Map<String, String> entetes) throws Exception {
        return decor.api.requete("GET", "/api/courses/" + id + "/suivi", entetes, null, null);
    }

    private JsonNode corps(String course) throws Exception {
        HttpResponse<String> reponse = suivi(course, Map.of());
        assertThat(reponse.statusCode()).isEqualTo(200);
        return decor.json.readTree(reponse.body());
    }
}
