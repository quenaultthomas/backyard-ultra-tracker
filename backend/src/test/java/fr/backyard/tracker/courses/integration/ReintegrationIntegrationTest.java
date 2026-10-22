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
import java.sql.Timestamp;
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
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 4.12 : réintégration (CA5 à CA12). Le contrat vient de la spec. Course X : Boucle de 1 min,
 * 5 Boucles max, t0 = 14:00:00Z ; Bruno (dossard 1), Chloé (2), Tom (3), Léo affecté. Le planificateur est désactivé
 * par le socle des tests : le contrôle automatique est appelé directement. Le journal est lu sur les seuls loggers
 * de l'application (fr.backyard), à l'abri du bruit du pool de connexions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@DirtiesContext
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class ReintegrationIntegrationTest {

    static final String ID_INCONNU = "00000000-0000-4000-8000-000000000001";
    static final Instant FIN_BOUCLE_1 = DecorAbandon.T0.plusSeconds(60);
    /** Instant où la Boucle 1 est tranchée (7.1) : fin de Boucle + délai de grâce de 15 s. */
    static final Instant DECISION_BOUCLE_1 = FIN_BOUCLE_1.plusSeconds(DecorAbandon.GRACE_SECONDES);
    static final List<String> CHAMPS_REPONSE = List.of("inscriptionId", "dossard", "statut", "bouclesCorrigees");
    static final List<String> CHAMPS_PILOTAGE = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur",
            "demarreeLe", "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle",
            "boucleMaxTerminee", "boucleEnAttenteDeDecision", "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "nombreAyantBoucle", "nombreEnBoucle", "inscritsEnCourse",
            "nombreAbandons", "inscritsEnAbandon", "nombreVainqueurs", "inscritsVainqueurs");
    static final List<String> CHAMPS_INSCRIT = List.of("inscriptionId", "dossard", "pseudo", "statut",
            "nombreBouclesTerminees", "boucleCouranteTerminee", "nombreBouclesCorrigees");
    static final List<String> CHAMPS_ABANDON = List.of("inscriptionId", "dossard", "pseudo", "statut",
            "nombreBouclesTerminees", "boucleAbandon", "motifAbandon", "nombreBouclesCorrigees");
    static final List<String> CHAMPS_VAINQUEUR = List.of("inscriptionId", "dossard", "pseudo", "statut",
            "nombreBouclesTerminees", "nombreBouclesCorrigees");
    static final String TOUT_INSCRIPTION = "select * from inscription order by id";
    static final String TOUT_COURSE = "select * from course order by id";
    static final String TOUT_PASSAGE = "select * from passage order by id";
    static final String ETAT_INSCRIPTIONS = "select id, statut, motif_abandon, boucle_abandon from inscription "
            + "order by id";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";

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
        decor.jdbc.execute("drop trigger if exists test_echec_passage on passage");
        decor.jdbc.execute("drop function if exists test_echec_passage()");
        decor.jdbc.execute("drop trigger if exists test_echec_inscription on inscription");
        decor.jdbc.execute("drop function if exists test_echec_inscription()");
    }

    // ---------------------------------------------------------------- aides

    private HttpResponse<String> reintegrer(Session session, String course, String inscription) throws Exception {
        return decor.api.requete("POST", chemin(course, inscription), session.entetes(), null, null);
    }

    private static String chemin(String course, String inscription) {
        return DecorAbandon.CHEMIN + "/" + course + "/inscriptions/" + inscription + "/reintegration";
    }

    private HttpResponse<String> abandonner(Session session, String course, String inscription, String motif)
            throws Exception {
        return decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + course + "/inscriptions/" + inscription
                + "/abandon", session.entetes(), "application/json", "{\"motif\":\"" + motif + "\"}");
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

    private List<Integer> boucles(JsonNode contenu) {
        List<Integer> numeros = new ArrayList<>();
        contenu.get("bouclesCorrigees").forEach(n -> numeros.add(n.asInt()));
        return numeros;
    }

    private List<String> lignesReintegration() {
        return decor.evenements().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("Réintégration")).toList();
    }

    private List<String> toutesLignesInfo() {
        return decor.evenements().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertJournalSansDonneeSensible(List<String> lignes) {
        List<String> jetons = decor.jdbc.queryForList("select jeton_qr from inscription", String.class);
        for (String ligne : lignes) {
            assertThat(ligne).doesNotContain("Tom", "Bruno", "Chloé", "Alice", DecorAbandon.NOM_X, "Backyard")
                    .doesNotContain(jetons);
        }
    }

    private void abandonnerParSql(String course, UUID compte, String motif, Integer boucle) {
        decor.jdbc.update("update inscription set statut = 'ABANDON', motif_abandon = ?, boucle_abandon = ? "
                + "where course_id = ? and compte_id = ?", motif, boucle, UUID.fromString(course), compte);
    }

    /** « 1,2c,3c » : numéros des Passages de l'Inscription, suffixés « c » pour une CORRECTION ; « - » si aucun. */
    private String passages(String course, UUID compte) {
        return decor.jdbc.queryForObject("select coalesce(string_agg(numero_boucle || case when origine = "
                + "'CORRECTION' then 'c' else '' end, ',' order by numero_boucle), '-') from passage "
                + "where inscription_id = ?", String.class, UUID.fromString(inscriptionDe(course, compte)));
    }

    private int courseTermineeAvecCoureurEnCourse() {
        return decor.jdbc.queryForObject("select count(*) from inscription i join course c on c.id = i.course_id "
                + "where c.statut = 'TERMINEE' and i.statut = 'EN_COURSE'", Integer.class);
    }

    private int correctionsDeCoureursAbandonnes() {
        return decor.jdbc.queryForObject("select count(*) from passage p join inscription i on i.id = p.inscription_id "
                + "where p.origine = 'CORRECTION' and i.statut = 'ABANDON'", Integer.class);
    }

    /** X démarrée, Bruno {1}, Chloé {1}, Tom ABANDON HORS_DELAI Boucle 1 sans Passage. */
    private String courseAvecTomHorsDelai() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idChloe, 1);
        abandonnerParSql(x, decor.idTom, "HORS_DELAI", 1);
        return x;
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : Patron réintègre Tom (HORS_DELAI, Boucle 1) à 14:01:30Z : 200, exactement les 4 champs, bouclesCorrigees [1], sans jeton ni pseudo ; en base Tom EN_COURSE sans motif ni Boucle, Passage 1 CORRECTION sans scanne_le, Bruno et Chloé inchangés ; journal INFO sans donnée sensible")
    void ca5_reintegration_nominale_persistee() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String tom = inscriptionDe(x, decor.idTom);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList(TOUT_COURSE);
        decor.demarrerJournal();

        HttpResponse<String> reponse = reintegrer(decor.patron, x, tom);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode contenu = corps(reponse);
        assertThat(contenu.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_REPONSE);
        assertThat(contenu.get("inscriptionId").asString()).isEqualTo(tom);
        assertThat(contenu.get("dossard").asInt()).isEqualTo(3);
        assertThat(contenu.get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(boucles(contenu)).containsExactly(1);
        assertThat(reponse.body()).doesNotContain("Tom", decor.jeton(x, decor.idTom), decor.idTom.toString());
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        assertThat(passages(x, decor.idTom)).isEqualTo("1c");
        assertThat(decor.jdbc.queryForObject("select count(*) from passage where origine = 'CORRECTION' "
                + "and scanne_le is not null", Integer.class)).isZero();
        assertThat(decor.jdbc.queryForObject("select count(*) from passage", Integer.class))
                .isEqualTo(passagesAvant.size() + 1);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).containsAll(passagesAvant);
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.jdbc.queryForList(TOUT_COURSE)).isEqualTo(coursesAvant);
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        assertThat(lignesReintegration()).containsExactly("Réintégration : course " + x + ", inscription " + tom
                + ", motif d'origine HORS_DELAI, 1 boucle(s) corrigée(s).");
        assertJournalSansDonneeSensible(toutesLignesInfo());
    }

    @Test
    @DisplayName("CA5 : Nadia réintègre ensuite Tom une 2e fois : 409 INSCRIPTION_NON_EN_ABANDON, statut EN_COURSE, aucune ligne ajoutée, aucune nouvelle ligne de journal")
    void ca5_rejeu_refuse_sans_ecriture() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String tom = inscriptionDe(x, decor.idTom);
        decor.demarrerJournal();
        assertThat(reintegrer(decor.patron, x, tom).statusCode()).isEqualTo(200);
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);

        HttpResponse<String> rejeu = reintegrer(decor.nadia, x, tom);

        assertErreur(rejeu, 409, "INSCRIPTION_NON_EN_ABANDON");
        assertThat(corps(rejeu).get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(corps(rejeu).get("detail").asString()).isEqualTo("Ce coureur n'est pas en abandon.");
        assertThat(rejeu.body()).doesNotContain("Tom");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(lignesReintegration()).hasSize(1);
    }

    @Test
    @DisplayName("CA5 : abandon MANUEL de Tom {} pendant la Boucle 1 puis réintégration : 200 avec bouclesCorrigees [] (boucleAbandon 1 > N = 0), aucun Passage créé, journal « 0 boucle(s) corrigée(s) »")
    void ca5_abandon_manuel_puis_reintegration_sans_boucle_a_corriger() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(30));
        String tom = inscriptionDe(x, decor.idTom);
        assertThat(abandonner(decor.patron, x, tom, "MANUEL").statusCode()).isEqualTo(200);
        decor.demarrerJournal();

        HttpResponse<String> reponse = reintegrer(decor.patron, x, tom);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(boucles(corps(reponse))).isEmpty();
        assertThat(corps(reponse).get("bouclesCorrigees").isArray()).isTrue();
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        assertThat(passages(x, decor.idTom)).isEqualTo("-");
        assertThat(lignesReintegration()).containsExactly("Réintégration : course " + x + ", inscription " + tom
                + ", motif d'origine MANUEL, 0 boucle(s) corrigée(s).");
    }

    @Test
    @DisplayName("CA5 : Tom abandonné AUTRE avec {1} (boucleAbandon 2) et Clock à 14:03:30Z : bouclesCorrigees [2, 3] en ordre croissant, Passages 1, 2c, 3c")
    void ca5_plusieurs_boucles_corrigees() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idTom, 1);
        abandonnerParSql(x, decor.idTom, "AUTRE", 2);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(210));

        HttpResponse<String> reponse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(boucles(corps(reponse))).containsExactly(2, 3);
        assertThat(passages(x, decor.idTom)).isEqualTo("1,2c,3c");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
    }

    @Test
    @DisplayName("CA5 : les quatre motifs d'abandon (HORS_DELAI, VOLONTAIRE, MANUEL, AUTRE) sont réintégrables ; le motif d'origine figure au journal")
    void ca5_tous_les_motifs_sont_reintegrables() throws Exception {
        decor.demarrerJournal();
        for (String motif : List.of("HORS_DELAI", "VOLONTAIRE", "MANUEL", "AUTRE")) {
            String course = decor.courseDemarreeAvecMaxBoucles("Course motif " + motif, 5);
            abandonnerParSql(course, decor.idTom, motif, 1);
            decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
            String tom = inscriptionDe(course, decor.idTom);

            HttpResponse<String> reponse = reintegrer(decor.patron, course, tom);

            assertThat(reponse.statusCode()).as(motif + " " + reponse.body()).isEqualTo(200);
            assertThat(boucles(corps(reponse))).containsExactly(1);
            assertThat(decor.etat(course, decor.idTom)).isEqualTo("EN_COURSE:-:-");
            assertThat(lignesReintegration()).anyMatch(l -> l.contains(tom) && l.contains("motif d'origine " + motif
                    + ","));
        }
    }

    @Test
    @DisplayName("CA5 : abandon posé par SQL sans motif ni Boucle d'abandon : b0 = nombre de Passages + 1, journal « motif d'origine inconnu »")
    void ca5_abandon_legacy_sans_boucle_ni_motif() throws Exception {
        String x = decor.courseXDemarree();
        abandonnerParSql(x, decor.idTom, null, null);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(150));
        String tom = inscriptionDe(x, decor.idTom);
        decor.demarrerJournal();

        HttpResponse<String> reponse = reintegrer(decor.patron, x, tom);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(boucles(corps(reponse))).containsExactly(1, 2);
        assertThat(lignesReintegration()).containsExactly("Réintégration : course " + x + ", inscription " + tom
                + ", motif d'origine inconnu, 2 boucle(s) corrigée(s).");
    }

    @Test
    @DisplayName("CA5 : instants limites : à 14:00:59.999Z la Boucle 1 est courante (aucune correction), à 14:01:00.000Z elle est terminée (corrigée), à 14:04:59.999Z les Boucles 1 à 4 sont corrigées (la Boucle 5 reste courante)")
    void ca5_instants_limites() throws Exception {
        String avant = decor.courseDemarreeAvecMaxBoucles("Avant la fin", 5);
        String pile = decor.courseDemarreeAvecMaxBoucles("Pile a la fin", 5);
        String derniere = decor.courseDemarreeAvecMaxBoucles("Derniere boucle", 5);
        for (String course : List.of(avant, pile, derniere)) {
            abandonnerParSql(course, decor.idTom, "MANUEL", 1);
        }

        decor.horloge.fixer(FIN_BOUCLE_1.minusMillis(1));
        HttpResponse<String> reponseAvant = reintegrer(decor.patron, avant, inscriptionDe(avant, decor.idTom));
        decor.horloge.fixer(FIN_BOUCLE_1);
        HttpResponse<String> reponsePile = reintegrer(decor.patron, pile, inscriptionDe(pile, decor.idTom));
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(300).minusMillis(1));
        HttpResponse<String> reponseDerniere = reintegrer(decor.patron, derniere,
                inscriptionDe(derniere, decor.idTom));

        assertThat(boucles(corps(reponseAvant))).isEmpty();
        assertThat(boucles(corps(reponsePile))).containsExactly(1);
        assertThat(reponseDerniere.statusCode()).as(reponseDerniere.body()).isEqualTo(200);
        assertThat(boucles(corps(reponseDerniere))).containsExactly(1, 2, 3, 4);
        assertThat(passages(derniere, decor.idTom)).isEqualTo("1c,2c,3c,4c");
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : Course inconnue ou {id} non UUID : 404 COURSE_INTROUVABLE (avant l'Inscription) ; aucune écriture, aucune ligne INFO")
    void ca6_course_introuvable() throws Exception {
        String x = courseAvecTomHorsDelai();
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        for (String inscription : List.of(inscriptionDe(x, decor.idTom), ID_INCONNU, "abc")) {
            HttpResponse<String> inconnue = reintegrer(decor.patron, ID_INCONNU, inscription);
            assertErreur(inconnue, 404, "COURSE_INTROUVABLE");
            assertThat(corps(inconnue).get("detail").asString()).isEqualTo("La course est introuvable.");
            assertErreur(reintegrer(decor.patron, "pas-un-uuid", inscription), 404, "COURSE_INTROUVABLE");
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(lignesReintegration()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Course EN_PREPARATION : 409 COURSE_NON_DEMARREE, y compris avec une Inscription inconnue ou non UUID (pas 404) ; aucune écriture")
    void ca6_course_non_demarree() throws Exception {
        String preparation = decor.creerCourse("Course en préparation", 1, 5);
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        for (String inscription : List.of(ID_INCONNU, "abc")) {
            HttpResponse<String> reponse = reintegrer(decor.patron, preparation, inscription);
            assertErreur(reponse, 409, "COURSE_NON_DEMARREE");
            assertThat(corps(reponse).get("detail").asString()).isEqualTo("La course n'est pas démarrée.");
        }

        assertThat(decor.statutCourse(preparation)).isEqualTo("EN_PREPARATION");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(lignesReintegration()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Course TERMINEE avec vainqueur : 409 COURSE_TERMINEE avec le detail de la réintégration, y compris Inscription inconnue ou non UUID ; Course et Inscriptions inchangées, jamais rouverte")
    void ca6_course_terminee_avec_vainqueur() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(DECISION_BOUCLE_1);
        controler.executer(UUID.fromString(x));
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList(TOUT_COURSE);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        decor.demarrerJournal();

        for (String inscription : List.of(inscriptionDe(x, decor.idTom), ID_INCONNU, "abc")) {
            HttpResponse<String> reponse = reintegrer(decor.patron, x, inscription);
            assertErreur(reponse, 409, "COURSE_TERMINEE");
            assertThat(corps(reponse).get("detail").asString())
                    .isEqualTo("La course est terminée : aucune réintégration n'est possible.");
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_COURSE)).isEqualTo(coursesAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(lignesReintegration()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Course terminée sans vainqueur par l'abandon du dernier coureur : 409 COURSE_TERMINEE, aucune réouverture, aucun Passage")
    void ca6_course_terminee_sans_vainqueur() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(20));
        for (UUID compte : List.of(decor.idBruno, decor.idChloe, decor.idTom)) {
            assertThat(abandonner(decor.patron, x, inscriptionDe(x, compte), "MANUEL").statusCode()).isEqualTo(200);
        }
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);

        HttpResponse<String> reponse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));
        HttpResponse<String> inconnue = reintegrer(decor.patron, x, ID_INCONNU);

        assertErreur(reponse, 409, "COURSE_TERMINEE");
        assertErreur(inconnue, 409, "COURSE_TERMINEE");
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForObject("select count(*) from passage", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA6 : Course EN_COURS sans demarree_le (SQL) : 409 HEURE_DE_DEPART_INCONNUE, avant la lecture de l'Inscription (Inscription inconnue : même 409)")
    void ca6_heure_de_depart_inconnue() throws Exception {
        UUID course = decor.insererCourse("Course sans heure", "EN_COURS");
        UUID inscription = decor.insererInscription(course, decor.idTom, 1, "ABANDON");
        List<Map<String, Object>> avant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        assertErreur(reintegrer(decor.patron, course.toString(), inscription.toString()), 409,
                "HEURE_DE_DEPART_INCONNUE");
        assertErreur(reintegrer(decor.patron, course.toString(), ID_INCONNU), 409, "HEURE_DE_DEPART_INCONNUE");

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(avant);
        assertThat(lignesReintegration()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Clock à 14:05:00.000Z (dernière Boucle écoulée), planificateur désactivé : 409 TEMPS_DE_COURSE_ECOULE avec son detail propre, avant la lecture de l'Inscription ; aucune écriture")
    void ca6_temps_de_course_ecoule() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(300));
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        decor.demarrerJournal();

        for (String inscription : List.of(inscriptionDe(x, decor.idTom), ID_INCONNU)) {
            HttpResponse<String> reponse = reintegrer(decor.patron, x, inscription);
            assertErreur(reponse, 409, "TEMPS_DE_COURSE_ECOULE");
            assertThat(corps(reponse).get("detail").asString())
                    .isEqualTo("Le temps de la dernière boucle est écoulé : la réintégration n'est plus possible.");
        }
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(540));
        assertErreur(reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom)), 409, "TEMPS_DE_COURSE_ECOULE");

        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(lignesReintegration()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : sur Course éligible, Inscription inconnue, non UUID, nulle ou d'une autre Course : 404 INSCRIPTION_INTROUVABLE ; aucune écriture, Course Y inchangée")
    void ca6_inscription_introuvable() throws Exception {
        String x = courseAvecTomHorsDelai();
        String y = decor.courseYDemarree();
        abandonnerParSql(y, decor.idAlice, "AUTRE", 1);
        String alice = inscriptionDe(y, decor.idAlice);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList(TOUT_COURSE);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        decor.demarrerJournal();

        HttpResponse<String> inconnue = reintegrer(decor.patron, x, ID_INCONNU);
        assertErreur(inconnue, 404, "INSCRIPTION_INTROUVABLE");
        assertThat(corps(inconnue).get("detail").asString()).isEqualTo("L'inscription est introuvable.");
        assertErreur(reintegrer(decor.patron, x, "abc"), 404, "INSCRIPTION_INTROUVABLE");
        assertErreur(reintegrer(decor.patron, x, "00000000-0000-0000-0000-000000000000"), 404,
                "INSCRIPTION_INTROUVABLE");
        assertErreur(reintegrer(decor.patron, x, alice), 404, "INSCRIPTION_INTROUVABLE");

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_COURSE)).isEqualTo(coursesAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(lignesReintegration()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Inscription EN_COURSE ou VAINQUEUR (SQL) sur Course EN_COURS : 409 INSCRIPTION_NON_EN_ABANDON avec le statut correspondant ; aucune écriture, aucune ligne INFO")
    void ca6_inscription_non_en_abandon() throws Exception {
        String x = decor.courseXDemarree();
        decor.jdbc.update("update inscription set statut = 'VAINQUEUR' where course_id = ? and compte_id = ?",
                UUID.fromString(x), decor.idTom);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        decor.demarrerJournal();

        HttpResponse<String> enCourse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idBruno));
        HttpResponse<String> vainqueur = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));

        assertErreur(enCourse, 409, "INSCRIPTION_NON_EN_ABANDON");
        assertThat(corps(enCourse).get("statut").asString()).isEqualTo("EN_COURSE");
        assertErreur(vainqueur, 409, "INSCRIPTION_NON_EN_ABANDON");
        assertThat(corps(vainqueur).get("statut").asString()).isEqualTo("VAINQUEUR");
        assertThat(enCourse.body() + vainqueur.body()).doesNotContain("Bruno", "Tom");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForObject("select count(*) from passage", Integer.class)).isZero();
        assertThat(lignesReintegration()).isEmpty();
    }

    @Test
    @DisplayName("CA6 : un corps JSON quelconque est ignoré (200 nominal, pas de 400 de corps)")
    void ca6_corps_ignore() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));

        HttpResponse<String> reponse = decor.api.requete("POST", chemin(x, inscriptionDe(x, decor.idTom)),
                decor.patron.entetes(), "application/json", "{\"motif\":\"FOO\",\"x\":[1,2]}");

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(boucles(corps(reponse))).containsExactly(1);
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
    }

    @Test
    @DisplayName("CA6 : ordre des erreurs : 409 de Course avant 404 INSCRIPTION_INTROUVABLE avant 409 INSCRIPTION_NON_EN_ABANDON")
    void ca6_ordre_des_erreurs() throws Exception {
        String x = decor.courseXDemarree();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));

        assertErreur(reintegrer(decor.patron, x, ID_INCONNU), 404, "INSCRIPTION_INTROUVABLE");
        assertErreur(reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom)), 409, "INSCRIPTION_NON_EN_ABANDON");
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(300));
        assertErreur(reintegrer(decor.patron, x, ID_INCONNU), 409, "TEMPS_DE_COURSE_ECOULE");
        assertErreur(reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom)), 409, "TEMPS_DE_COURSE_ECOULE");
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : sans jeton CSRF : 403 CSRF_INVALIDE (avant tout) ; anonyme avec jeton : 401 NON_AUTHENTIFIE ; Alice et Léo (affecté) : 403 ACCES_REFUSE, aussi sur une Course inconnue ; aucun effet sur X")
    void ca7_securite() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String tom = inscriptionDe(x, decor.idTom);
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        String jetonCsrf = decor.api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jetonCsrf, "X-XSRF-TOKEN", jetonCsrf);

        for (String course : List.of(x, ID_INCONNU)) {
            assertErreur(decor.api.requete("POST", chemin(course, tom), decor.patron.enteteLecture(), null, null),
                    403, "CSRF_INVALIDE");
            assertErreur(decor.api.requete("POST", chemin(course, tom), Map.of(), null, null), 403, "CSRF_INVALIDE");
            assertErreur(decor.api.requete("POST", chemin(course, tom), anonyme, null, null), 401,
                    "NON_AUTHENTIFIE");
            assertErreur(reintegrer(decor.alice, course, tom), 403, "ACCES_REFUSE");
            assertErreur(reintegrer(decor.leo, course, tom), 403, "ACCES_REFUSE");
            assertErreur(reintegrer(decor.tom, course, tom), 403, "ACCES_REFUSE");
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA7 : GET, PUT, DELETE sur le chemin de réintégration : 404 RESSOURCE_INTROUVABLE ou 405, jamais 2xx ; aucun effet sur X")
    void ca7_autres_methodes() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String chemin = chemin(x, inscriptionDe(x, decor.idTom));
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);

        for (String methode : List.of("GET", "PUT", "DELETE")) {
            HttpResponse<String> reponse = decor.api.requete(methode, chemin, decor.patron.entetes(), null, null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }

        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForObject("select count(*) from passage where origine = 'CORRECTION'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA7 : Nadia (ADMIN) et Patron (ADMIN_MASTER) sont autorisés")
    void ca7_admins_autorises() throws Exception {
        String x = courseAvecTomHorsDelai();
        abandonnerParSql(x, decor.idChloe, "AUTRE", 2);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));

        HttpResponse<String> parNadia = reintegrer(decor.nadia, x, inscriptionDe(x, decor.idTom));
        HttpResponse<String> parPatron = reintegrer(decor.patron, x, inscriptionDe(x, decor.idChloe));

        assertThat(parNadia.statusCode()).as(parNadia.body()).isEqualTo(200);
        assertThat(parPatron.statusCode()).as(parPatron.body()).isEqualTo(200);
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : cycle abandon VOLONTAIRE (Boucle 2, Tom {1}) -> réintégration à 14:03:30Z ([2, 3]) -> abandon MANUEL à 14:03:40Z (boucleAbandon 4) -> réintégration à 14:04:10Z : [4] seulement ; Tom {1, 2c, 3c, 4c}, aucun doublon, Passages existants jamais modifiés ni supprimés")
    void ca8_cycle_abandon_reintegration() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idTom, 1);
        String tom = inscriptionDe(x, decor.idTom);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(70));
        HttpResponse<String> premierAbandon = abandonner(decor.patron, x, tom, "VOLONTAIRE");
        assertThat(corps(premierAbandon).get("boucleAbandon").asInt()).isEqualTo(2);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(210));

        HttpResponse<String> premiere = reintegrer(decor.patron, x, tom);
        List<Map<String, Object>> apresPremiere = decor.jdbc.queryForList(TOUT_PASSAGE);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(220));
        HttpResponse<String> secondAbandon = abandonner(decor.patron, x, tom, "MANUEL");
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(250));
        HttpResponse<String> seconde = reintegrer(decor.patron, x, tom);

        assertThat(boucles(corps(premiere))).containsExactly(2, 3);
        assertThat(corps(secondAbandon).get("boucleAbandon").asInt()).isEqualTo(4);
        assertThat(seconde.statusCode()).as(seconde.body()).isEqualTo(200);
        assertThat(boucles(corps(seconde))).containsExactly(4);
        assertThat(passages(x, decor.idTom)).isEqualTo("1,2c,3c,4c");
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).containsAll(apresPremiere).hasSize(apresPremiere.size() + 1);
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
    }

    @Test
    @DisplayName("CA8 : une Inscription anonymisée (Compte supprimé, 3.6) est réintégrable ; le pilotage affiche « Coureur anonyme » dans la liste")
    void ca8_inscription_anonymisee() throws Exception {
        String x = decor.courseXDemarree();
        String tom = inscriptionDe(x, decor.idTom);
        HttpResponse<String> suppression = decor.api.requete("POST", "/api/comptes/moi/suppression",
                decor.tom.entetes(), "application/json", "{\"motDePasseActuel\":\"" + MOT_DE_PASSE + "\"}");
        assertThat(suppression.statusCode()).as(suppression.body()).isEqualTo(204);
        abandonnerParSql(x, decor.idTom, "HORS_DELAI", 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));

        HttpResponse<String> reponse = reintegrer(decor.patron, x, tom);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(boucles(corps(reponse))).containsExactly(1);
        JsonNode pilotage = corps(decor.pilotage(decor.patron, x));
        List<String> pseudos = new ArrayList<>();
        pilotage.get("inscritsEnCourse").forEach(i -> pseudos.add(i.get("pseudo").asString()));
        assertThat(pseudos).contains("Coureur anonyme");
    }

    @Test
    @DisplayName("CA8 : plusieurs Courses en parallèle : réintégrer une Inscription de Y via l'URL de X donne 404 INSCRIPTION_INTROUVABLE ; Y reste inchangée, le calcul de X n'utilise que ses propres paramètres")
    void ca8_isolation_entre_courses() throws Exception {
        String x = courseAvecTomHorsDelai();
        String y = decor.courseYDemarree();
        abandonnerParSql(y, decor.idAlice, "AUTRE", 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(150));
        List<Map<String, Object>> inscriptionsYAvant = decor.jdbc.queryForList(
                "select * from inscription where course_id = ? order by id", UUID.fromString(y));
        List<Map<String, Object>> courseYAvant = decor.jdbc.queryForList("select * from course where id = ?",
                UUID.fromString(y));

        assertErreur(reintegrer(decor.patron, x, inscriptionDe(y, decor.idAlice)), 404, "INSCRIPTION_INTROUVABLE");
        HttpResponse<String> reponse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));

        assertThat(boucles(corps(reponse))).containsExactly(1, 2);
        assertThat(decor.jdbc.queryForList("select * from inscription where course_id = ? order by id",
                UUID.fromString(y))).isEqualTo(inscriptionsYAvant);
        assertThat(decor.jdbc.queryForList("select * from course where id = ?", UUID.fromString(y)))
                .isEqualTo(courseYAvant);
        assertThat(decor.jdbc.queryForObject("select count(*) from passage p join inscription i on "
                + "i.id = p.inscription_id where i.course_id = ?", Integer.class, UUID.fromString(y))).isZero();
        // Y (90 min) : à 150 s la Boucle 1 n'est pas terminée ; la réintégrer donnerait [], jamais [1, 2].
        assertThat(reintegrer(decor.patron, y, inscriptionDe(y, decor.idAlice)).body()).contains("[]");
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : réintégration puis contrôle à 14:01:15.000Z : Passage 1c, Chloé ABANDON HORS_DELAI Boucle 1, Bruno et Tom EN_COURSE, Course EN_COURS (le réintégré change l'issue)")
    void ca9_reintegration_puis_controle() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        abandonnerParSql(x, decor.idTom, "MANUEL", 1);
        decor.horloge.fixer(DECISION_BOUCLE_1);

        HttpResponse<String> reponse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));
        controler.executer(UUID.fromString(x));

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(boucles(corps(reponse))).containsExactly(1);
        assertThat(passages(x, decor.idTom)).isEqualTo("1c");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA9 : contrôle puis réintégration : Bruno VAINQUEUR, Course TERMINEE, réintégration 409 COURSE_TERMINEE, aucun Passage de Tom")
    void ca9_controle_puis_reintegration() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        abandonnerParSql(x, decor.idTom, "MANUEL", 1);
        decor.horloge.fixer(DECISION_BOUCLE_1);

        controler.executer(UUID.fromString(x));
        HttpResponse<String> reponse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));

        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");
        assertErreur(reponse, 409, "COURSE_TERMINEE");
        assertThat(passages(x, decor.idTom)).isEqualTo("-");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:MANUEL:1");
    }

    @Test
    @DisplayName("CA9 : 20 essais, réintégration de Tom et contrôle automatique à 14:01:00.000Z simultanés : exactement l'un des deux états finaux, jamais 500, jamais de Passage CORRECTION pour un ABANDON, jamais de Course TERMINEE avec une Inscription EN_COURSE")
    void ca9_reintegration_et_controle_simultanes() throws Exception {
        int reintegrationsGagnantes = 0;
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course controle " + essai, 5);
            decor.poserPassage(x, decor.idBruno, 1);
            abandonnerParSql(x, decor.idTom, "MANUEL", 1);
            decor.horloge.fixer(DECISION_BOUCLE_1);
            String tom = inscriptionDe(x, decor.idTom);

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> reintegrer(decor.patron, x, tom),
                    () -> {
                        controler.executer(UUID.fromString(x));
                        return null;
                    });

            HttpResponse<String> reintegration = resultats.get(0);
            String contexte = "essai " + essai + " " + reintegration.body();
            if (reintegration.statusCode() == 200) {
                reintegrationsGagnantes++;
                assertThat(decor.etat(x, decor.idTom)).as(contexte).isEqualTo("EN_COURSE:-:-");
                assertThat(passages(x, decor.idTom)).as(contexte).isEqualTo("1c");
                assertThat(decor.etat(x, decor.idBruno)).as(contexte).isEqualTo("EN_COURSE:-:-");
                assertThat(decor.etat(x, decor.idChloe)).as(contexte).isEqualTo("ABANDON:HORS_DELAI:1");
                assertThat(decor.statutCourse(x)).as(contexte).isEqualTo("EN_COURS");
            } else {
                assertErreur(reintegration, 409, "COURSE_TERMINEE");
                assertThat(decor.etat(x, decor.idTom)).as(contexte).isEqualTo("ABANDON:MANUEL:1");
                assertThat(passages(x, decor.idTom)).as(contexte).isEqualTo("-");
                assertThat(decor.etat(x, decor.idBruno)).as(contexte).isEqualTo("VAINQUEUR:-:-");
                assertThat(decor.statutCourse(x)).as(contexte).isEqualTo("TERMINEE");
            }
            assertThat(correctionsDeCoureursAbandonnes()).as(contexte).isZero();
            assertThat(courseTermineeAvecCoureurEnCourse()).as(contexte).isZero();
        }
        assertThat(reintegrationsGagnantes).isBetween(0, 20);
    }

    @Test
    @DisplayName("CA9 : 20 essais, deux réintégrations simultanées de Tom : un 200 et un 409 INSCRIPTION_NON_EN_ABANDON, un seul jeu de Passages, une seule ligne de journal, jamais 500")
    void ca9_deux_reintegrations_simultanees() throws Exception {
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course double " + essai, 5);
            abandonnerParSql(x, decor.idTom, "HORS_DELAI", 1);
            decor.horloge.fixer(DecorAbandon.T0.plusSeconds(150));
            String tom = inscriptionDe(x, decor.idTom);
            decor.demarrerJournal();

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> reintegrer(decor.patron, x, tom),
                    () -> reintegrer(decor.nadia, x, tom));

            List<Integer> statuts = resultats.stream().map(HttpResponse::statusCode).sorted().toList();
            assertThat(statuts).as("essai " + essai).containsExactly(200, 409);
            HttpResponse<String> refus = resultats.stream().filter(r -> r.statusCode() == 409).findFirst()
                    .orElseThrow();
            assertErreur(refus, 409, "INSCRIPTION_NON_EN_ABANDON");
            assertThat(passages(x, decor.idTom)).as("essai " + essai).isEqualTo("1c,2c");
            assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
            assertThat(lignesReintegration()).hasSize(1);
            decor.arreterJournal();
        }
    }

    @Test
    @DisplayName("CA9 : 20 essais, réintégration et scan de Tom (Boucle 2 courante) simultanés : soit scan 409 INSCRIPTION_NON_EN_COURSE puis réintégration, soit réintégration puis scan 201 ; jamais de Passage SCAN pour un coureur ABANDON")
    void ca9_reintegration_et_scan_simultanes() throws Exception {
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course scan " + essai, 5);
            decor.poserPassage(x, decor.idTom, 1);
            abandonnerParSql(x, decor.idTom, "MANUEL", 2);
            decor.horloge.fixer(DecorAbandon.T0.plusSeconds(70));
            String tom = inscriptionDe(x, decor.idTom);
            String jeton = decor.jeton(x, decor.idTom);

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> reintegrer(decor.patron, x, tom),
                    () -> decor.scanner(decor.leo, x, jeton, UUID.randomUUID(), 0));

            HttpResponse<String> reintegration = resultats.get(0);
            HttpResponse<String> scan = resultats.get(1);
            assertThat(reintegration.statusCode()).as(reintegration.body()).isEqualTo(200);
            assertThat(boucles(corps(reintegration))).isEmpty();
            assertThat(decor.etat(x, decor.idTom)).as("essai " + essai).isEqualTo("EN_COURSE:-:-");
            if (scan.statusCode() == 201) {
                assertThat(passages(x, decor.idTom)).as("essai " + essai).isEqualTo("1,2");
            } else {
                assertErreur(scan, 409, "INSCRIPTION_NON_EN_COURSE");
                assertThat(passages(x, decor.idTom)).as("essai " + essai).isEqualTo("1");
            }
        }
    }

    @Test
    @DisplayName("CA9 : 20 essais, réintégration et abandon manuel de Tom simultanés : sérialisés, jamais d'état mixte (EN_COURSE avec 1 Passage, ou ABANDON MANUEL Boucle 2 avec 1 Passage), jamais 500")
    void ca9_reintegration_et_abandon_manuel_simultanes() throws Exception {
        for (int essai = 0; essai < 20; essai++) {
            String x = decor.courseDemarreeAvecMaxBoucles("Course mixte " + essai, 5);
            abandonnerParSql(x, decor.idTom, "HORS_DELAI", 1);
            decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
            String tom = inscriptionDe(x, decor.idTom);

            List<HttpResponse<String>> resultats = lancerEnParallele(
                    () -> reintegrer(decor.patron, x, tom),
                    () -> abandonner(decor.nadia, x, tom, "MANUEL"));

            HttpResponse<String> reintegration = resultats.get(0);
            HttpResponse<String> abandon = resultats.get(1);
            String contexte = "essai " + essai + " " + reintegration.body() + " " + abandon.body();
            assertThat(reintegration.statusCode()).as(contexte).isEqualTo(200);
            assertThat(passages(x, decor.idTom)).as(contexte).isEqualTo("1c");
            if (abandon.statusCode() == 200) {
                assertThat(decor.etat(x, decor.idTom)).as(contexte).isEqualTo("ABANDON:MANUEL:2");
            } else {
                assertErreur(abandon, 409, "INSCRIPTION_NON_EN_COURSE");
                assertThat(decor.etat(x, decor.idTom)).as(contexte).isEqualTo("EN_COURSE:-:-");
            }
            assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        }
    }

    @Test
    @DisplayName("CA9 (RG7) : atomicité : si l'insertion du Passage CORRECTION échoue, 500 ERREUR_INTERNE, Tom reste ABANDON sans Passage, aucune ligne INFO de réintégration")
    void ca9_atomicite_echec_du_passage() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        decor.jdbc.execute("create function test_echec_passage() returns trigger as $$ begin "
                + "raise exception 'echec simule'; end; $$ language plpgsql");
        decor.jdbc.execute("create trigger test_echec_passage before insert on passage for each row "
                + "execute function test_echec_passage()");
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        decor.demarrerJournal();

        HttpResponse<String> reponse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));

        assertErreur(reponse, 500, "ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("echec simule", "Tom");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(lignesReintegration()).isEmpty();
        assertThat(decor.evenements()).filteredOn(e -> e.getLevel() == Level.ERROR)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("Tom",
                        decor.jeton(x, decor.idTom)));
    }

    @Test
    @DisplayName("CA9 (RG7) : atomicité : si l'écriture de l'Inscription échoue après celle des Passages, 500 ERREUR_INTERNE et aucun Passage CORRECTION ne subsiste (jamais de Passage pour une Inscription restée ABANDON)")
    void ca9_atomicite_echec_de_l_inscription() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        decor.jdbc.execute("create function test_echec_inscription() returns trigger as $$ begin "
                + "raise exception 'echec simule'; end; $$ language plpgsql");
        decor.jdbc.execute("create trigger test_echec_inscription before update on inscription for each row "
                + "execute function test_echec_inscription()");
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);

        HttpResponse<String> reponse = reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom));

        assertErreur(reponse, 500, "ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("echec simule");
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(correctionsDeCoureursAbandonnes()).isZero();
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
    @DisplayName("CA10 : pilotage après la réintégration de Tom : 200, no-store, 24 champs, inscritsEnCourse de 3 éléments de 7 champs (Tom terminées 1, corrigées 1 ; Bruno et Chloé 0), inscritsEnAbandon vide, nombreInscrits 3 ; aucun jeton, identifiant de Compte ni de Passage ; GET sans écriture ni ligne INFO")
    void ca10_pilotage_apres_reintegration() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        String tom = inscriptionDe(x, decor.idTom);
        assertThat(reintegrer(decor.patron, x, tom).statusCode()).isEqualTo(200);
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList(TOUT_INSCRIPTION);
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList(TOUT_PASSAGE);
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList(TOUT_COURSE);
        decor.demarrerJournal();

        HttpResponse<String> reponse = decor.pilotage(decor.patron, x);

        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode pilotage = corps(reponse);
        assertThat(pilotage.propertyNames()).hasSize(24).containsExactlyInAnyOrderElementsOf(CHAMPS_PILOTAGE);
        assertThat(pilotage.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(pilotage.get("inscritsEnCourse")).hasSize(3);
        assertThat(pilotage.get("inscritsEnCourse")).allSatisfy(n -> assertThat(n.propertyNames()).hasSize(7)
                .containsExactlyInAnyOrderElementsOf(CHAMPS_INSCRIT));
        for (JsonNode inscrit : pilotage.get("inscritsEnCourse")) {
            boolean estTom = inscrit.get("inscriptionId").asString().equals(tom);
            assertThat(inscrit.get("nombreBouclesCorrigees").asInt()).isEqualTo(estTom ? 1 : 0);
            assertThat(inscrit.get("nombreBouclesTerminees").asInt()).isEqualTo(1);
        }
        assertThat(pilotage.get("nombreAbandons").asInt()).isZero();
        assertThat(pilotage.get("inscritsEnAbandon")).isEmpty();
        List<String> sensibles = new ArrayList<>(decor.jdbc.queryForList("select jeton_qr from inscription",
                String.class));
        decor.jdbc.queryForList("select id from passage", UUID.class).forEach(id -> sensibles.add(id.toString()));
        for (UUID compte : List.of(decor.idAlice, decor.idBruno, decor.idChloe, decor.idTom, decor.idLeo)) {
            sensibles.add(compte.toString());
        }
        assertThat(reponse.body()).doesNotContain(sensibles);
        assertThat(decor.jdbc.queryForList(TOUT_INSCRIPTION)).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList(TOUT_PASSAGE)).isEqualTo(passagesAvant);
        assertThat(decor.jdbc.queryForList(TOUT_COURSE)).isEqualTo(coursesAvant);
        assertThat(toutesLignesInfo()).isEmpty();
    }

    @Test
    @DisplayName("CA10 : Tom de nouveau ABANDON avec un Passage CORRECTION : élément de inscritsEnAbandon à 8 champs, nombreBouclesCorrigees 1, badge conservé")
    void ca10_pilotage_reintegre_puis_de_nouveau_en_abandon() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        assertThat(reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom)).statusCode()).isEqualTo(200);
        decor.jdbc.update("update inscription set statut = 'ABANDON', motif_abandon = 'HORS_DELAI', "
                + "boucle_abandon = 2 where course_id = ? and compte_id = ?", UUID.fromString(x), decor.idTom);

        JsonNode pilotage = corps(decor.pilotage(decor.patron, x));

        JsonNode tom = null;
        for (JsonNode abandon : pilotage.get("inscritsEnAbandon")) {
            if (abandon.get("dossard").asInt() == 3) {
                tom = abandon;
            }
        }
        assertThat(tom).isNotNull();
        assertThat(tom.propertyNames()).hasSize(8).containsExactlyInAnyOrderElementsOf(CHAMPS_ABANDON);
        assertThat(tom.get("nombreBouclesCorrigees").asInt()).isEqualTo(1);
        assertThat(tom.get("nombreBouclesTerminees").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA10 : Course terminée avec Tom vainqueur (Passages 1c et 2) : élément de inscritsVainqueurs à 6 champs, nombreBouclesCorrigees 1, nombreBouclesTerminees 2 ; Bruno 0 corrigé")
    void ca10_pilotage_vainqueur_corrige() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassageCorrection(x, decor.idTom, 1);
        decor.poserPassage(x, decor.idTom, 2);
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        abandonnerParSql(x, decor.idChloe, "HORS_DELAI", 1);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(135));
        controler.executer(UUID.fromString(x));
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(195)); // Boucle 3 sans Passage : vainqueurs partagés
        controler.executer(UUID.fromString(x));
        assertThat(decor.statutCourse(x)).isEqualTo("TERMINEE");

        JsonNode pilotage = corps(decor.pilotage(decor.patron, x));

        assertThat(pilotage.get("inscritsVainqueurs")).hasSize(2);
        for (JsonNode vainqueur : pilotage.get("inscritsVainqueurs")) {
            assertThat(vainqueur.propertyNames()).hasSize(6).containsExactlyInAnyOrderElementsOf(CHAMPS_VAINQUEUR);
            boolean estTom = vainqueur.get("dossard").asInt() == 3;
            assertThat(vainqueur.get("nombreBouclesCorrigees").asInt()).isEqualTo(estTom ? 1 : 0);
            assertThat(vainqueur.get("nombreBouclesTerminees").asInt()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("CA10 : le nombre de requêtes SQL du pilotage est identique avec 2 et avec 10 inscrits portant des Passages CORRECTION (pas de N+1)")
    void ca10_nombre_de_requetes_constant() throws Exception {
        UUID petite = courseAvecInscritsCorriges("Petite course", 2);
        UUID grande = courseAvecInscritsCorriges("Grande course", 10);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        assertThat(decor.pilotage(decor.patron, petite.toString()).statusCode()).isEqualTo(200); // réchauffe

        long avecDeux = requetesPourUnPilotage(petite);
        long avecDix = requetesPourUnPilotage(grande);

        assertThat(avecDeux).isPositive();
        assertThat(avecDix).isEqualTo(avecDeux);
        JsonNode corps = corps(decor.pilotage(decor.patron, grande.toString()));
        long corriges = 0;
        for (JsonNode i : corps.get("inscritsEnCourse")) {
            corriges += i.get("nombreBouclesCorrigees").asInt();
        }
        for (JsonNode i : corps.get("inscritsEnAbandon")) {
            corriges += i.get("nombreBouclesCorrigees").asInt();
        }
        assertThat(corriges).isEqualTo(10);
    }

    private long requetesPourUnPilotage(UUID idCourse) throws Exception {
        var statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();
        assertThat(decor.pilotage(decor.patron, idCourse.toString()).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    /** Course EN_COURS (SQL, départ t0), moitié EN_COURSE moitié ABANDON, chacun avec un Passage CORRECTION 1. */
    private UUID courseAvecInscritsCorriges(String nom, int nombre) {
        UUID course = decor.insererCourse(nom, "EN_COURS");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), course);
        for (int i = 1; i <= nombre; i++) {
            UUID compte = decor.insererCompte("Corrige" + nom.charAt(0) + i, "COUREUR");
            UUID inscription = decor.insererInscription(course, compte, i, i % 2 == 0 ? "ABANDON" : "EN_COURSE");
            if (i % 2 == 0) {
                decor.jdbc.update("update inscription set motif_abandon = 'HORS_DELAI', boucle_abandon = 2 "
                        + "where id = ?", inscription);
            }
            decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), inscription, 1, null, "CORRECTION");
        }
        return course;
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : avant la réintégration le scan du jeton de Tom donne 409 INSCRIPTION_NON_EN_COURSE (statut ABANDON) ; après la réintégration (1c) pendant la Boucle 2, un scan neuf donne 201, numeroBoucle 2, origine SCAN, sans PASSAGE_PRECEDENT_MANQUANT ni SCAN_TROP_RAPPROCHE ; le rejeu de son identifiantScan donne 200")
    void ca11_scan_avant_et_apres_reintegration() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(70));
        String jeton = decor.jeton(x, decor.idTom);

        HttpResponse<String> avant = decor.scanner(decor.leo, x, jeton, UUID.randomUUID(), 0);
        assertErreur(avant, 409, "INSCRIPTION_NON_EN_COURSE");
        assertThat(corps(avant).get("statut").asString()).isEqualTo("ABANDON");
        assertThat(passages(x, decor.idTom)).isEqualTo("-");
        assertThat(reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom)).statusCode()).isEqualTo(200);
        UUID identifiant = UUID.randomUUID();

        HttpResponse<String> scan = decor.scanner(decor.leo, x, jeton, identifiant, 0);
        HttpResponse<String> rejeu = decor.scanner(decor.leo, x, jeton, identifiant, 0);

        assertThat(scan.statusCode()).as(scan.body()).isEqualTo(201);
        assertThat(corps(scan).get("numeroBoucle").asInt()).isEqualTo(2);
        assertThat(corps(scan).get("origine").asString()).isEqualTo("SCAN");
        assertThat(rejeu.statusCode()).as(rejeu.body()).isEqualTo(200);
        assertThat(passages(x, decor.idTom)).isEqualTo("1c,2");
    }

    @Test
    @DisplayName("CA11 : une série de scans valides, refusés et rejoués par Léo après la réintégration ne modifie ni statut, ni motif_abandon, ni boucle_abandon ; au contrôle suivant (14:02:00Z) Tom est EN_COURSE (a bouclé 2), Chloé HORS_DELAI Boucle 2")
    void ca11_scans_sans_effet_sur_les_statuts_puis_controle() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(70));
        assertThat(reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom)).statusCode()).isEqualTo(200);
        List<Map<String, Object>> avant = decor.jdbc.queryForList(ETAT_INSCRIPTIONS);
        UUID rejoue = UUID.randomUUID();

        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), rejoue, 0).statusCode()).isEqualTo(201);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), rejoue, 0).statusCode()).isEqualTo(200);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(409);
        assertThat(decor.scanner(decor.leo, x, "jeton-inconnu", UUID.randomUUID(), 0).statusCode()).isEqualTo(404);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idTom), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(201);
        assertThat(decor.api.requete("GET", "/api/benevole/courses", decor.leo.enteteLecture(), null, null)
                .statusCode()).isEqualTo(200);

        assertThat(decor.jdbc.queryForList(ETAT_INSCRIPTIONS)).isEqualTo(avant);
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(135));
        controler.executer(UUID.fromString(x));
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:2");
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA11 : un réintégré qui ne scanne pas la Boucle 2 retombe en ABANDON HORS_DELAI Boucle 2 au contrôle suivant, ses Passages CORRECTION sont conservés")
    void ca11_reintegre_qui_ne_scanne_pas() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(70));
        assertThat(reintegrer(decor.patron, x, inscriptionDe(x, decor.idTom)).statusCode()).isEqualTo(200);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(201);
        assertThat(decor.scanner(decor.leo, x, decor.jeton(x, decor.idChloe), UUID.randomUUID(), 0).statusCode())
                .isEqualTo(201);

        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(135));
        controler.executer(UUID.fromString(x));

        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:2");
        assertThat(passages(x, decor.idTom)).isEqualTo("1c");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : databasechangelog se termine toujours par 0010 (aucune migration ajoutée par 4.12)")
    void ca12_aucune_migration() {
        assertThat(decor.jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .endsWith("0009-passage", "0010-abandon-inscription");
    }

    @Test
    @DisplayName("CA12 : un GET .../pilotage ne produit aucune ligne INFO (le journal ne contient que les lignes de l'application)")
    void ca12_pilotage_silencieux() throws Exception {
        String x = courseAvecTomHorsDelai();
        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(90));
        decor.demarrerJournal();

        assertThat(decor.pilotage(decor.patron, x).statusCode()).isEqualTo(200);

        assertThat(toutesLignesInfo()).isEmpty();
    }
}
