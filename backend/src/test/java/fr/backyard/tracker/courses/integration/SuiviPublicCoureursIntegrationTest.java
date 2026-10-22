package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Contrat de l'incrément 6.2 (suivi public : coureurs en course, abandons, vainqueurs) : CA8 à CA14, serveur réel.
 * Le contrat est celui de la spec : GET /api/courses/{id}/suivi, public, liste fermée de 14 champs, trois listes
 * (RG3, RG4, RG6), pseudos par l'annuaire (RG5), deux instructions ajoutées, indépendantes du nombre d'inscrits (RG7), rien lu en EN_PREPARATION (RG2).
 * Course de 1 min, t0 = 14:00:00Z ; le planificateur est désactivé par le socle des tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuiviPublicCoureursIntegrationTest {

    static final List<String> CHAMPS = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur",
            "nombreMaxBoucles", "boucleCourante", "derniereBoucle", "boucleMaxTerminee", "boucleEnAttenteDeDecision",
            "tempsAvantFinBoucleMillisecondes", "dureeEcouleeMillisecondes", "coureursEnCourse", "abandons",
            "vainqueurs", "distanceBoucleMetres", "denivelePositifBoucleMetres", "bouclesTerminees",
            "distanceCumuleeMetres", "denivelePositifCumuleMetres", "allureSecondesParKilometre",
            "dureeFinaleMillisecondes");
    static final List<String> CHAMPS_COUREUR = List.of("dossard", "pseudo");
    static final List<String> CHAMPS_ABANDON = List.of("dossard", "pseudo", "boucleAbandon", "motifAbandon");
    static final String ANONYME = "Coureur anonyme";

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

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : X EN_COURS (Alice 1 et Bruno 2 EN_COURSE, Karim 3 ABANDON Boucle 1 VOLONTAIRE) sans session : 200, no-store, aucun Set-Cookie, 22 champs (7.1 : + boucleEnAttenteDeDecision) exactement (6.3 RG1), lignes à 2 et 4 champs, aucune fuite, aucune écriture")
    void ca8_suivi_public_avec_trois_listes() throws Exception {
        UUID x = courseEnCours("X");
        UUID alice = inscrire(x, decor.idAlice, 1, "EN_COURSE");
        inscrire(x, decor.idBruno, 2, "EN_COURSE");
        UUID karim = decor.insererCompte("Karim", "COUREUR");
        inscrireAbandon(x, karim, 3, "VOLONTAIRE", 1);
        horloge(30);
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");

        HttpResponse<String> reponse = suivi(x.toString(), Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(22).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("coureursEnCourse")).hasSize(2);
        for (JsonNode ligne : corps.get("coureursEnCourse")) {
            assertThat(ligne.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COUREUR);
        }
        assertThat(lignes(corps.get("coureursEnCourse"))).containsExactly("1:Alice", "2:Bruno");
        assertThat(corps.get("abandons")).hasSize(1);
        JsonNode abandon = corps.get("abandons").get(0);
        assertThat(abandon.propertyNames()).hasSize(4).containsExactlyInAnyOrderElementsOf(CHAMPS_ABANDON);
        assertThat(abandon.get("dossard").asInt()).isEqualTo(3);
        assertThat(abandon.get("pseudo").asString()).isEqualTo("Karim");
        assertThat(abandon.get("boucleAbandon").asInt()).isEqualTo(1);
        assertThat(abandon.get("motifAbandon").asString()).isEqualTo("VOLONTAIRE");
        assertThat(corps.get("vainqueurs").isArray()).isTrue();
        assertThat(corps.get("vainqueurs")).isEmpty();
        assertThat(reponse.body()).doesNotContain("jeton", "compteId", "inscriptionId", "motDePasse",
                decor.idAlice.toString(), karim.toString(), alice.toString(), decor.jeton(x.toString(), decor.idAlice),
                decor.jeton(x.toString(), decor.idBruno), decor.jeton(x.toString(), karim));
        assertThat(reponse.body()).doesNotContain("EN_COURSE", "\"ABANDON\"", "VAINQUEUR", "SCAN", "CORRECTION",
                "nombreInscrits", "nombreBoucles", "dureeBoucle");
        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(decor.jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
    }

    @Test
    @DisplayName("CA8 : un abandon enregistré sans Boucle ni motif sort avec boucleAbandon et motifAbandon à null, toujours 4 champs")
    void ca8_abandon_sans_boucle_ni_motif() throws Exception {
        UUID x = courseEnCours("X");
        decor.insererInscription(x, decor.idAlice, 1, "ABANDON");
        horloge(30);

        JsonNode abandon = corps(x).get("abandons").get(0);

        assertThat(abandon.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_ABANDON);
        assertThat(abandon.get("boucleAbandon").isNull()).isTrue();
        assertThat(abandon.get("motifAbandon").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : EN_PREPARATION avec 2 Inscriptions : 200 (jamais 409), les trois listes sont des tableaux vides, aucun pseudo dans le corps")
    void ca9_en_preparation_listes_vides() throws Exception {
        UUID x = decor.insererCourse("Préparation", "EN_PREPARATION");
        inscrire(x, decor.idAlice, 1, "EN_COURSE");
        inscrire(x, decor.idBruno, 2, "EN_COURSE");

        HttpResponse<String> reponse = suivi(x.toString(), Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(22);
        assertThat(corps.get("statut").asString()).isEqualTo("EN_PREPARATION");
        for (String liste : List.of("coureursEnCourse", "abandons", "vainqueurs")) {
            assertThat(corps.get(liste).isArray()).as(liste).isTrue();
            assertThat(corps.get(liste)).as(liste).isEmpty();
        }
        assertThat(reponse.body()).doesNotContain("Alice", "Bruno");
    }

    @Test
    @DisplayName("CA9 : TERMINEE avec vainqueurs partagés (dossards 2 et 1) : vainqueurs [1, 2], coureursEnCourse vide, ni vainqueur dans abandons")
    void ca9_terminee_vainqueurs_partages() throws Exception {
        UUID x = decor.insererCourse("Partagés", "TERMINEE");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), x);
        inscrire(x, decor.idBruno, 2, "VAINQUEUR");
        inscrire(x, decor.idAlice, 1, "VAINQUEUR");
        horloge(600);

        JsonNode corps = corps(x);

        assertThat(corps.get("statut").asString()).isEqualTo("TERMINEE");
        assertThat(lignes(corps.get("vainqueurs"))).containsExactly("1:Alice", "2:Bruno");
        assertThat(corps.get("coureursEnCourse")).isEmpty();
        assertThat(corps.get("abandons")).isEmpty();
        for (JsonNode ligne : corps.get("vainqueurs")) {
            assertThat(ligne.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COUREUR);
        }
    }

    @Test
    @DisplayName("CA9 : TERMINEE sans vainqueur : vainqueurs vide, abandons renseignés, coureursEnCourse vide")
    void ca9_terminee_sans_vainqueur() throws Exception {
        UUID x = decor.insererCourse("Sans vainqueur", "TERMINEE");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), x);
        inscrireAbandon(x, decor.idAlice, 1, "VOLONTAIRE", 1);
        inscrireAbandon(x, decor.idBruno, 2, "MANUEL", 1);

        JsonNode corps = corps(x);

        assertThat(corps.get("vainqueurs").isArray()).isTrue();
        assertThat(corps.get("vainqueurs")).isEmpty();
        assertThat(corps.get("coureursEnCourse")).isEmpty();
        assertThat(lignes(corps.get("abandons"))).containsExactly("1:Alice", "2:Bruno");
    }

    @Test
    @DisplayName("CA9 : 5 abandons de Boucles et dossards variés : Boucle décroissante, null en dernier, puis dossard croissant ; identique d'un appel à l'autre ; coureurs en course par dossard croissant")
    void ca9_tri_des_listes() throws Exception {
        UUID x = courseEnCours("X");
        inscrireAbandon(x, compte("P4"), 4, "MANUEL", 2);
        inscrireAbandon(x, compte("P2"), 2, "AUTRE", 5);
        inscrireAbandon(x, compte("P6"), 6, "VOLONTAIRE", 5);
        UUID sans = compte("P3");
        decor.insererInscription(x, sans, 3, "ABANDON");
        inscrireAbandon(x, compte("P5"), 5, "HORS_DELAI", 1);
        inscrire(x, compte("P9"), 9, "EN_COURSE");
        inscrire(x, compte("P7"), 7, "EN_COURSE");
        inscrire(x, compte("P8"), 8, "EN_COURSE");
        horloge(30);

        JsonNode premier = corps(x);
        JsonNode second = corps(x);

        assertThat(lignes(premier.get("abandons"))).containsExactly("2:P2", "6:P6", "4:P4", "5:P5", "3:P3");
        assertThat(premier.get("abandons").get(0).get("motifAbandon").asString()).isEqualTo("AUTRE");
        assertThat(premier.get("abandons").get(4).get("boucleAbandon").isNull()).isTrue();
        assertThat(lignes(premier.get("coureursEnCourse"))).containsExactly("7:P7", "8:P8", "9:P9");
        assertThat(second).isEqualTo(premier);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : Alice (X EN_COURS et Y EN_PREPARATION) supprime son Compte : ligne dossard 1 en « Coureur anonyme », « Alice » absent du corps ; un nouveau Compte « Alice » inscrit à X (dossard 5) ne rejaillit pas sur la ligne 1")
    void ca10_compte_supprime_anonymise_et_pseudo_reutilise() throws Exception {
        UUID x = courseEnCours("X");
        UUID y = decor.insererCourse("Y", "EN_PREPARATION");
        inscrire(x, decor.idAlice, 1, "EN_COURSE");
        inscrire(x, decor.idBruno, 2, "EN_COURSE");
        inscrire(y, decor.idAlice, 1, "EN_COURSE");
        horloge(30);

        HttpResponse<String> suppression = decor.api.requete("POST", "/api/comptes/moi/suppression",
                decor.alice.entetes(), "application/json",
                "{\"motDePasseActuel\":\"" + DecorAbandon.MOT_DE_PASSE + "\"}");
        assertThat(suppression.statusCode()).isEqualTo(204);

        HttpResponse<String> reponse = suivi(x.toString(), Map.of());
        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body()).doesNotContain("Alice");
        assertThat(lignes(decor.json.readTree(reponse.body()).get("coureursEnCourse")))
                .containsExactly("1:" + ANONYME, "2:Bruno");

        UUID nouvelleAlice = decor.insererCompte("Alice", "COUREUR");
        inscrire(x, nouvelleAlice, 5, "EN_COURSE");
        invaliderLeMicroCache();

        assertThat(lignes(corps(x).get("coureursEnCourse")))
                .containsExactly("1:" + ANONYME, "2:Bruno", "5:Alice");
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : abandon manuel de Bruno (Boucle 2, MANUEL) visible, puis réintégration : retour en course et abandons vide sans trace, puis abandon automatique de Tom (HORS_DELAI, Boucle 1)")
    void ca11_abandon_manuel_reintegration_abandon_automatique() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String bruno = decor.idInscription(x, decor.idBruno);

        HttpResponse<String> abandon = decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x + "/inscriptions/"
                + bruno + "/abandon", decor.nadia.entetes(), "application/json", "{\"motif\":\"MANUEL\"}");
        assertThat(abandon.statusCode()).as(abandon.body()).isEqualTo(200);

        invaliderLeMicroCache();
        JsonNode apresAbandon = corps(x);
        assertThat(lignes(apresAbandon.get("abandons"))).containsExactly("1:Bruno");
        assertThat(apresAbandon.get("abandons").get(0).get("boucleAbandon").asInt()).isEqualTo(2);
        assertThat(apresAbandon.get("abandons").get(0).get("motifAbandon").asString()).isEqualTo("MANUEL");
        assertThat(lignes(apresAbandon.get("coureursEnCourse"))).containsExactly("2:Chloé", "3:Tom");

        HttpResponse<String> reintegration = decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x
                + "/inscriptions/" + bruno + "/reintegration", decor.nadia.entetes(), null, null);
        assertThat(reintegration.statusCode()).as(reintegration.body()).isEqualTo(200);

        invaliderLeMicroCache();
        JsonNode apresReintegration = corps(x);
        assertThat(lignes(apresReintegration.get("coureursEnCourse"))).containsExactly("1:Bruno", "2:Chloé", "3:Tom");
        assertThat(apresReintegration.get("abandons").isArray()).isTrue();
        assertThat(apresReintegration.get("abandons")).isEmpty();

        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isEqualTo(1);

        invaliderLeMicroCache();
        JsonNode apresAutomatique = corps(x);
        assertThat(lignes(apresAutomatique.get("abandons"))).containsExactly("3:Tom");
        assertThat(apresAutomatique.get("abandons").get(0).get("motifAbandon").asString()).isEqualTo("HORS_DELAI");
        assertThat(apresAutomatique.get("abandons").get(0).get("boucleAbandon").asInt()).isEqualTo(1);
        assertThat(lignes(apresAutomatique.get("coureursEnCourse"))).containsExactly("1:Bruno", "2:Chloé");
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : 3 puis 60 Inscriptions de Comptes distincts : même nombre d'instructions SQL, exactement 3 de plus qu'en EN_PREPARATION (6.3 CA14 remplace 2 par 3 : Passages en plus), lecture de Passage groupée")
    void ca12_nombre_de_requetes_independant_du_nombre_d_inscrits() throws Exception {
        UUID petite = courseEnCours("Petite");
        UUID grande = courseEnCours("Grande");
        UUID preparation = decor.insererCourse("Préparation", "EN_PREPARATION");
        for (int i = 1; i <= 3; i++) {
            inscrire(petite, compte("A" + i), i, "EN_COURSE");
        }
        for (int i = 1; i <= 60; i++) {
            UUID inscription = inscrire(grande, compte("B" + i), i, i % 3 == 0 ? "ABANDON" : "EN_COURSE");
            if (i % 3 == 0) {
                decor.jdbc.update("update inscription set motif_abandon = 'HORS_DELAI', boucle_abandon = 1 "
                        + "where id = ?", inscription);
            }
        }
        for (int i = 1; i <= 3; i++) {
            inscrire(preparation, compte("C" + i), i, "EN_COURSE");
        }
        horloge(30);
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(suivi(grande.toString(), Map.of()).statusCode()).isEqualTo(200);

        long pour3 = requetes(statistiques, petite);
        long pour60 = requetes(statistiques, grande);
        long enPreparation = requetes(statistiques, preparation);

        assertThat(pour60).as("même nombre d'instructions SQL pour 3 et 60 inscrits (pas de N+1)").isEqualTo(pour3);
        assertThat(pour3 - enPreparation).as("Inscriptions, pseudos et Passages s'ajoutent (6.3 RG9)").isEqualTo(3);
        assertThat(corps(grande).get("coureursEnCourse")).hasSize(40);
        assertThat(corps(grande).get("abandons")).hasSize(20);
        // les instructions ajoutées (Inscriptions, pseudos, Passages) sont absentes en EN_PREPARATION
        statistiques.clear();
        corps(preparation);
        assertThat(java.util.Arrays.stream(statistiques.getEntityNames())
                .filter(n -> n.contains("Inscription") || n.contains("Compte"))
                .map(n -> statistiques.getEntityStatistics(n).getLoadCount()
                        + statistiques.getEntityStatistics(n).getFetchCount()).toList()).allMatch(n -> n == 0);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : X et Y avec des inscrits différents : ADMIN, ADMIN_MASTER, BENEVOLE non affecté, COUREUR et sans session reçoivent le même corps 200, sans aucune ligne de Y")
    void ca13_meme_corps_pour_tous_et_isolation_entre_courses() throws Exception {
        UUID x = courseEnCours("X");
        UUID y = courseEnCours("Y");
        inscrire(x, decor.idAlice, 1, "EN_COURSE");
        inscrire(x, decor.idBruno, 2, "EN_COURSE");
        inscrireAbandon(x, decor.idChloe, 3, "MANUEL", 1);
        inscrire(y, decor.idTom, 1, "EN_COURSE");
        inscrireAbandon(y, compte("Zoe"), 2, "AUTRE", 1);
        horloge(30);
        HttpResponse<String> anonyme = suivi(x.toString(), Map.of());
        assertThat(anonyme.statusCode()).isEqualTo(200);
        JsonNode attendu = decor.json.readTree(anonyme.body());

        for (Session session : List.of(decor.nadia, decor.patron, decor.leo, decor.alice)) {
            HttpResponse<String> reponse = suivi(x.toString(), session.enteteLecture());
            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(decor.json.readTree(reponse.body())).isEqualTo(attendu);
        }
        assertThat(anonyme.body()).doesNotContain("Tom", "Zoe");
        assertThat(lignes(attendu.get("coureursEnCourse"))).containsExactly("1:Alice", "2:Bruno");
        assertThat(lignes(attendu.get("abandons"))).containsExactly("3:Chloé");
        assertThat(lignes(corps(y).get("coureursEnCourse"))).containsExactly("1:Tom");
        assertThat(lignes(corps(y).get("abandons"))).containsExactly("2:Zoe");
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : lectures, abandon manuel, réintégration, abandon automatique, suppression de Compte : aucune ligne INFO ou plus ne contient un pseudo de coureur ou un jeton QR ; les lectures seules n'écrivent aucune ligne INFO")
    void ca14_journal_sans_pseudo_ni_jeton() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idChloe, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String bruno = decor.idInscription(x, decor.idBruno);
        List<String> jetons = List.of(decor.jeton(x, decor.idBruno), decor.jeton(x, decor.idChloe),
                decor.jeton(x, decor.idTom));
        decor.demarrerJournal();

        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);
        assertThat(decor.evenements()).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x + "/inscriptions/" + bruno + "/abandon",
                decor.nadia.entetes(), "application/json", "{\"motif\":\"MANUEL\"}").statusCode()).isEqualTo(200);
        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + x + "/inscriptions/" + bruno
                + "/reintegration", decor.nadia.entetes(), null, null).statusCode()).isEqualTo(200);
        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);
        assertThat(abandonner.executer(UUID.fromString(x)).abandons()).isEqualTo(1);
        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);
        assertThat(decor.api.requete("POST", "/api/comptes/moi/suppression", decor.chloe.entetes(),
                "application/json", "{\"motDePasseActuel\":\"" + DecorAbandon.MOT_DE_PASSE + "\"}").statusCode())
                .isEqualTo(204);
        assertThat(suivi(x, Map.of()).statusCode()).isEqualTo(200);

        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : decor.evenements()) {
            if (e.getLevel().isGreaterOrEqual(Level.INFO)) {
                messages.add(e.getFormattedMessage());
            }
        }
        assertThat(messages).isNotEmpty().allSatisfy(m -> assertThat(m)
                .doesNotContain("Bruno", "Chloé", "Tom", "Alice").doesNotContain(jetons));
    }

    // ---------------------------------------------------------------- outils

    /** 7.2 RG2 : une seconde de plus que la lecture précédente, pour que le micro-cache relise la base. */
    private void invaliderLeMicroCache() {
        horloge.fixer(horloge.instant().plusSeconds(1));
    }

    private void horloge(long secondesApresT0) {
        horloge.fixer(DecorAbandon.T0.plusSeconds(secondesApresT0));
    }

    /** Course EN_COURS (SQL), démarrée à t0. */
    private UUID courseEnCours(String nom) {
        UUID id = decor.insererCourse(nom, "EN_COURS");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), id);
        return id;
    }

    private UUID inscrire(UUID course, UUID compte, int dossard, String statut) {
        return decor.insererInscription(course, compte, dossard, statut);
    }

    private void inscrireAbandon(UUID course, UUID compte, int dossard, String motif, int boucle) {
        UUID id = decor.insererInscription(course, compte, dossard, "ABANDON");
        decor.jdbc.update("update inscription set motif_abandon = ?, boucle_abandon = ? where id = ?", motif, boucle,
                id);
    }

    /** Compte coureur créé sans hachage coûteux. */
    private UUID compte(String pseudo) {
        UUID id = UUID.randomUUID();
        decor.jdbc.update(DecorAbandon.INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(java.util.Locale.ROOT),
                empreinte, "COUREUR", Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private long requetes(Statistics statistiques, UUID course) throws Exception {
        invaliderLeMicroCache();
        long avant = statistiques.getPrepareStatementCount();
        assertThat(suivi(course.toString(), Map.of()).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    private HttpResponse<String> suivi(String id, Map<String, String> entetes) throws Exception {
        return decor.api.requete("GET", "/api/courses/" + id + "/suivi", entetes, null, null);
    }

    private JsonNode corps(UUID course) throws Exception {
        return corps(course.toString());
    }

    private JsonNode corps(String course) throws Exception {
        HttpResponse<String> reponse = suivi(course, Map.of());
        assertThat(reponse.statusCode()).isEqualTo(200);
        return decor.json.readTree(reponse.body());
    }

    /** « dossard:pseudo » de chaque ligne, dans l'ordre de la réponse. */
    private static List<String> lignes(JsonNode liste) {
        List<String> lignes = new ArrayList<>();
        for (JsonNode ligne : liste) {
            lignes.add(ligne.get("dossard").asInt() + ":" + ligne.get("pseudo").asString());
        }
        return lignes;
    }
}
