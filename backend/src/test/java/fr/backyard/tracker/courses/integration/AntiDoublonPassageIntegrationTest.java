package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 4.6 : refus 409 PASSAGE_TROP_RAPPROCHE (anti-doublon) de
 * POST /api/benevole/courses/{courseId}/passages (CA4 à CA7). La Course X (60 min, 5 Boucles max) et la Course Y
 * (1 min) sont démarrées à 14:03:27.654Z, soit t0 = 14:03:27Z après troncature. Bruno (dossard 1) et Chloé
 * (dossard 2) sont inscrits, Léo est affecté. Les jetons sont fixés en base avec des majuscules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class AntiDoublonPassageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String NOM_X = "Backyard des Crêtes";
    static final String NOM_Y = "Backyard Express";
    static final Instant DEMARRAGE = Instant.parse("2026-10-12T14:03:27.654Z");
    static final Instant T0 = Instant.parse("2026-10-12T14:03:27Z");
    static final String JOUR = "2026-10-12";
    static final String JB = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    static final String JC = new StringBuilder(JB).reverse().toString();
    static final String JBY = "Yy" + JB.substring(2);
    static final Set<String> STANDARD = Set.of("type", "title", "status", "detail", "instance", "code");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, ?, ?, ?, 400, 60, 5, 10, 5)";
    static final String INSERTION_PASSAGE = "insert into passage (id, inscription_id, numero_boucle, scanne_le, "
            + "origine) values (?, ?, ?, ?, ?)";

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

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    JournalApplicatif journal;
    Session patron;
    Session leo;
    Session marc;
    Session bruno;
    Session chloe;
    UUID idBruno;
    UUID idChloe;
    UUID idLeo;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(DEMARRAGE);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        idBruno = insererCompte("Bruno", "COUREUR");
        idChloe = insererCompte("Chloé", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        insererCompte("Marc", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        marc = api.ouvrir("Marc", MOT_DE_PASSE);
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Boucle 1 de Bruno à t0 + 59 min 50 s, scan à t0 + 60 min 5 s : 409 PASSAGE_TROP_RAPPROCHE complet, une seule ligne ; à 119,999 s : 1 seconde ; à 120 s : 201 Boucle 2")
    void ca4_anti_doublon_nominal_et_bornes() throws Exception {
        String x = courseDemarree(NOM_X, 60);
        poserPassage(idInscription(x, idBruno), 1, T0.plusSeconds(59 * 60 + 50), "SCAN");

        horloge.fixer(T0.plus(Duration.ofMinutes(60).plusSeconds(5)));
        HttpResponse<String> refus = scanner(leo, x, corps(JB));

        assertErreur(refus, 409, "PASSAGE_TROP_RAPPROCHE", "Conflit",
                "Ce coureur vient de passer : attendez 105 secondes avant un nouveau passage.");
        JsonNode erreur = json.readTree(refus.body());
        assertThat(erreur.get("dossard").asInt()).isEqualTo(1);
        assertThat(erreur.get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(erreur.get("numeroBoucle").asInt()).isEqualTo(2);
        assertThat(erreur.get("delaiMinimalSecondes").asInt()).isEqualTo(120);
        assertThat(erreur.get("secondesRestantes").asInt()).isEqualTo(105);
        assertThat(extensions(erreur)).containsExactlyInAnyOrder("dossard", "pseudo", "numeroBoucle",
                "delaiMinimalSecondes", "secondesRestantes");
        assertThat(refus.body()).doesNotContain(JB).doesNotContain(NOM_X);
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);

        // 119,999 s d'écart : il reste 1 ms, arrondi à 1 seconde (singulier).
        horloge.fixer(T0.plusMillis((59 * 60 + 50) * 1000L + 119_999));
        HttpResponse<String> presqueAssez = scanner(leo, x, corps(JB));
        assertErreur(presqueAssez, 409, "PASSAGE_TROP_RAPPROCHE", "Conflit",
                "Ce coureur vient de passer : attendez 1 seconde avant un nouveau passage.");
        assertThat(json.readTree(presqueAssez.body()).get("secondesRestantes").asInt()).isEqualTo(1);
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);

        // 120 s exactement : accepté (comparaison stricte).
        horloge.fixer(T0.plusSeconds(59 * 60 + 50 + 120));
        HttpResponse<String> accepte = scanner(leo, x, corps(JB));
        assertThat(accepte.statusCode()).isEqualTo(201);
        JsonNode passage = json.readTree(accepte.body());
        assertThat(passage.get("numeroBoucle").asInt()).isEqualTo(2);
        assertThat(passage.get("dossard").asInt()).isEqualTo(1);
        assertThat(compterPassagesDe(idBruno)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : rejeu 200 dans la fenêtre du délai, précédent manquant et déjà passé prioritaires, Passage CORRECTION sans contrôle, Boucle de 1 min (15 s), schéma inchangé")
    void ca5_ordre_rejeu_correction_boucle_courte_et_schema() throws Exception {
        String x = courseDemarree(NOM_X, 60);
        String y = courseDemarree(NOM_Y, 1, JBY);

        // Boucle 1 de Bruno par l'API à t0 + 59 min 50 s, avec un identifiant connu.
        horloge.fixer(T0.plusSeconds(59 * 60 + 50));
        UUID u1 = UUID.randomUUID();
        HttpResponse<String> creation = scanner(leo, x, corps(JB, u1.toString(), null));
        assertThat(creation.statusCode()).isEqualTo(201);
        // Même Boucle : PASSAGE_DEJA_ENREGISTRE, pas d'anti-doublon.
        assertErreur(scanner(leo, x, corps(JB)), 409, "PASSAGE_DEJA_ENREGISTRE", "Conflit",
                "Ce coureur a déjà un passage sur la boucle 1.");

        horloge.fixer(T0.plusSeconds(60 * 60 + 5));
        HttpResponse<String> rejeu = scanner(leo, x, corps(JB, u1.toString(), null));
        assertThat(rejeu.statusCode()).isEqualTo(200);
        assertThat(json.readTree(rejeu.body())).isEqualTo(json.readTree(creation.body()));
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);

        // Chloé sans Passage de Boucle 1 : précédent manquant, pas trop rapproché.
        HttpResponse<String> manquant = scanner(leo, x, corps(JC));
        assertErreur(manquant, 409, "PASSAGE_PRECEDENT_MANQUANT", "Conflit",
                "Ce coureur n'a pas de passage sur la boucle précédente.");
        assertThat(compterPassagesDe(idChloe)).isZero();

        // Passage de Boucle 1 de Chloé d'origine CORRECTION (sans instant) : aucun contrôle.
        poserPassage(idInscription(x, idChloe), 1, null, "CORRECTION");
        HttpResponse<String> apresCorrection = scanner(leo, x, corps(JC));
        assertThat(apresCorrection.statusCode()).isEqualTo(201);
        assertThat(json.readTree(apresCorrection.body()).get("numeroBoucle").asInt()).isEqualTo(2);

        // Course Y (1 min, délai 15 s) : Boucle 1 de Bruno à t0 + 55 s.
        poserPassage(idInscription(y, idBruno), 1, T0.plusSeconds(55), "SCAN");
        horloge.fixer(T0.plusSeconds(65));
        HttpResponse<String> refusY = scanner(leo, y, corps(JBY));
        assertErreur(refusY, 409, "PASSAGE_TROP_RAPPROCHE", "Conflit",
                "Ce coureur vient de passer : attendez 5 secondes avant un nouveau passage.");
        JsonNode erreurY = json.readTree(refusY.body());
        assertThat(erreurY.get("delaiMinimalSecondes").asInt()).isEqualTo(15);
        assertThat(erreurY.get("secondesRestantes").asInt()).isEqualTo(5);
        assertThat(erreurY.get("numeroBoucle").asInt()).isEqualTo(2);
        horloge.fixer(T0.plusSeconds(70));
        HttpResponse<String> accepteY = scanner(leo, y, corps(JBY));
        assertThat(accepteY.statusCode()).isEqualTo(201);
        assertThat(json.readTree(accepteY.body()).get("numeroBoucle").asInt()).isEqualTo(2);

        // Enchaînement Boucle 3 sur Y : l'âge du scan pilote la Boucle (voir aussi le test dédié).
        horloge.fixer(T0.plusSeconds(125));
        // 125 s - 60 s d'âge = t0 + 65 s : Boucle 2 (déjà passé), donc pas une Boucle 3 ; l'âge pilote la Boucle.
        assertErreur(scanner(leo, y, corps(JBY, UUID.randomUUID().toString(), 60_000L)), 409,
                "PASSAGE_DEJA_ENREGISTRE", "Conflit", "Ce coureur a déjà un passage sur la boucle 2.");
        // Sans âge, scan à t0 + 125 s = Boucle 3, 55 s après la Boucle 2 : accepté.
        assertThat(scanner(leo, y, corps(JBY)).statusCode()).isEqualTo(201);

        // Schéma : aucun changeset ni colonne ajoutés par 4.6.
        List<String> ids = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        assertThat(ids).last().isEqualTo("0011-compte-actif");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'passage'",
                String.class)).containsExactlyInAnyOrder("id", "inscription_id", "numero_boucle", "scanne_le", "origine");
    }

    @Test
    @DisplayName("CA5 : l'âge du scan est déduit avant l'anti-doublon (scanneLe = horloge - âge)")
    void ca5_age_du_scan_deduit_avant_la_comparaison() throws Exception {
        String x = courseDemarree(NOM_X, 60);
        poserPassage(idInscription(x, idBruno), 1, T0.plusSeconds(59 * 60 + 50), "SCAN");

        // Réception à t0 + 62 min, scan fait 10 s plus tôt : scanneLe = t0 + 61 min 50 s, écart 120 s : accepté.
        horloge.fixer(T0.plusSeconds(62 * 60));
        HttpResponse<String> accepte = scanner(leo, x, corps(JB, UUID.randomUUID().toString(), 10_000L));
        assertThat(accepte.statusCode()).isEqualTo(201);
        assertThat(json.readTree(accepte.body()).get("scanneLe").asString()).isEqualTo("2026-10-12T15:05:17.000Z");

        // Réception à t0 + 62 min, scan fait 11 s plus tôt : écart 119 s : refusé, 1 seconde restante.
        jdbc.update("delete from passage where numero_boucle = 2");
        HttpResponse<String> refus = scanner(leo, x, corps(JB, UUID.randomUUID().toString(), 11_000L));
        assertErreur(refus, 409, "PASSAGE_TROP_RAPPROCHE", "Conflit",
                "Ce coureur vient de passer : attendez 1 seconde avant un nouveau passage.");
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : les refus de 4.4a gardent statut, code, detail et extensions ; les trois jetons inconnus donnent des corps strictement identiques sans dossard, pseudo ni numeroBoucle")
    void ca6_refus_de_4_4a_inchanges() throws Exception {
        String x = courseDemarree(NOM_X, 60);
        UUID prepa = insererCourse("Prépa", "EN_PREPARATION");
        UUID terminee = insererCourse("Terminée", "TERMINEE");
        UUID sansHeure = insererCourse("Sans heure", "EN_COURS");
        for (UUID course : List.of(prepa, terminee, sansHeure)) {
            jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", course, idLeo);
        }
        String jp = "Pp" + JB.substring(2);
        String jt = "Tt" + JB.substring(2);
        String js = "Ss" + JB.substring(2);
        insererInscription(prepa, idBruno, 1, jp, "EN_COURSE");
        insererInscription(terminee, idBruno, 1, jt, "EN_COURSE");
        insererInscription(sansHeure, idBruno, 1, js, "EN_COURSE");

        // Jeton inconnu : trois cas aux corps strictement identiques.
        horloge.fixer(T0.plusSeconds(30 * 60));
        HttpResponse<String> inexistant = scanner(leo, x, corps("n'existe-nulle-part"));
        HttpResponse<String> autreCourse = scanner(leo, x, corps(jp));
        HttpResponse<String> minuscules = scanner(leo, x, corps(JB.toLowerCase(Locale.ROOT)));
        assertThat(JB).isNotEqualTo(JB.toLowerCase(Locale.ROOT));
        for (HttpResponse<String> reponse : List.of(inexistant, autreCourse, minuscules)) {
            assertErreur(reponse, 404, "JETON_INCONNU", "Introuvable", "Jeton inconnu pour cette course.");
            assertThat(extensions(json.readTree(reponse.body()))).isEmpty();
            assertThat(reponse.body()).doesNotContain("dossard").doesNotContain("pseudo")
                    .doesNotContain("numeroBoucle").doesNotContain("Bruno");
        }
        assertThat(json.readTree(autreCourse.body())).isEqualTo(json.readTree(inexistant.body()));
        assertThat(json.readTree(minuscules.body())).isEqualTo(json.readTree(inexistant.body()));

        // Course dans un autre état.
        assertErreurSans(scanner(leo, prepa.toString(), corps(jp)), 409, "COURSE_NON_DEMARREE",
                "La course n'est pas démarrée.");
        assertErreurSans(scanner(leo, terminee.toString(), corps(jt)), 409, "COURSE_TERMINEE",
                "La course est terminée.");
        assertErreurSans(scanner(leo, sansHeure.toString(), corps(js)), 409, "HEURE_DE_DEPART_INCONNUE",
                "L'heure de départ de la course est inconnue : le passage ne peut pas être placé sur une boucle.");

        // Inscription ABANDON / VAINQUEUR.
        for (String statut : List.of("ABANDON", "VAINQUEUR")) {
            jdbc.update("update inscription set statut = ? where compte_id = ? and course_id = ?", statut, idChloe,
                    UUID.fromString(x));
            HttpResponse<String> reponse = scanner(leo, x, corps(JC));
            assertErreur(reponse, 409, "INSCRIPTION_NON_EN_COURSE", "Conflit", "Ce coureur n'est plus en course.");
            JsonNode erreur = json.readTree(reponse.body());
            assertThat(extensions(erreur)).containsExactlyInAnyOrder("dossard", "pseudo", "statut");
            assertThat(erreur.get("dossard").asInt()).isEqualTo(2);
            assertThat(erreur.get("pseudo").asString()).isEqualTo("Chloé");
            assertThat(erreur.get("statut").asString()).isEqualTo(statut);
        }
        jdbc.update("update inscription set statut = 'EN_COURSE' where compte_id = ?", idChloe);

        // Scan antérieur au départ.
        horloge.fixer(T0.plusSeconds(10));
        assertErreurSans(scanner(leo, x, corps(JB, UUID.randomUUID().toString(), 20_000L)), 409,
                "SCAN_ANTERIEUR_AU_DEPART", "Le scan est antérieur au départ de la course.");

        // Déjà passé, identifiant déjà utilisé, précédent manquant.
        horloge.fixer(T0.plusSeconds(30 * 60));
        UUID u1 = UUID.randomUUID();
        assertThat(scanner(leo, x, corps(JB, u1.toString(), null)).statusCode()).isEqualTo(201);
        HttpResponse<String> deja = scanner(leo, x, corps(JB));
        assertErreur(deja, 409, "PASSAGE_DEJA_ENREGISTRE", "Conflit", "Ce coureur a déjà un passage sur la boucle 1.");
        assertThat(extensions(json.readTree(deja.body()))).containsExactlyInAnyOrder("dossard", "pseudo",
                "numeroBoucle");
        assertErreurSans(scanner(leo, x, corps(JC, u1.toString(), null)), 409, "IDENTIFIANT_SCAN_DEJA_UTILISE",
                "Cet identifiant de scan correspond à un autre passage.");
        horloge.fixer(T0.plusSeconds(60 * 60 + 5));
        HttpResponse<String> manquant = scanner(leo, x, corps(JC));
        assertErreur(manquant, 409, "PASSAGE_PRECEDENT_MANQUANT", "Conflit",
                "Ce coureur n'a pas de passage sur la boucle précédente.");
        JsonNode erreurManquant = json.readTree(manquant.body());
        assertThat(extensions(erreurManquant)).containsExactlyInAnyOrder("dossard", "pseudo", "numeroBoucle");
        assertThat(erreurManquant.get("numeroBoucle").asInt()).isEqualTo(2);

        // Temps de course écoulé (5 Boucles de 60 min), prioritaire sur le déjà passé.
        horloge.fixer(T0.plusSeconds(5 * 60 * 60));
        HttpResponse<String> ecoule = scanner(leo, x, corps(JB));
        assertErreur(ecoule, 409, "TEMPS_DE_COURSE_ECOULE", "Conflit", "Le temps de la dernière boucle est écoulé.");
        assertThat(extensions(json.readTree(ecoule.body()))).containsExactlyInAnyOrder("dossard", "pseudo");

        // Bénévole non affecté.
        HttpResponse<String> nonAffecte = scanner(marc, x, corps(JB));
        assertErreur(nonAffecte, 403, "BENEVOLE_NON_AFFECTE", "Accès refusé", "Vous n'êtes pas affecté à cette course.");
        assertThat(extensions(json.readTree(nonAffecte.body()))).isEmpty();

        assertThat(compterPassages()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : plusieurs refus d'anti-doublon : aucune ligne INFO ou supérieure, ni jeton, ni pseudo, ni nom de Course au journal ; la toString de l'exception ne contient pas de jeton")
    void ca7_journal_silencieux_pour_l_anti_doublon() throws Exception {
        String x = courseDemarree(NOM_X, 60);
        poserPassage(idInscription(x, idBruno), 1, T0.plusSeconds(59 * 60 + 50), "SCAN");
        horloge.fixer(T0.plusSeconds(60 * 60 + 5));
        demarrerJournal();

        List<HttpResponse<String>> reponses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            reponses.add(scanner(leo, x, corps(JB)));
        }
        horloge.fixer(T0.plusSeconds(60 * 60 + 10));
        reponses.add(scanner(leo, x, corps(JB)));

        assertThat(reponses).allSatisfy(reponse -> {
            assertThat(reponse.statusCode()).isEqualTo(409);
            assertThat(reponse.body()).contains("PASSAGE_TROP_RAPPROCHE").doesNotContain(JB).doesNotContain(NOM_X);
        });
        assertThat(journal.evenementsApplicatifs())
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> interdits = List.of(JB, JC, "Bruno", NOM_X, idBruno.toString());
        assertThat(messagesDuJournal()).allSatisfy(m -> assertThat(m).doesNotContain(interdits));
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);

        String texte = new fr.backyard.tracker.courses.domaine.PassageTropRapprocheException(2,
                Duration.ofMinutes(2), 105).toString();
        assertThat(texte).doesNotContain(JB).doesNotContain("Bruno");
    }

    // ---------------------------------------------------------------- utilitaires

    private static Set<String> extensions(JsonNode erreur) {
        Set<String> noms = new java.util.HashSet<>();
        erreur.propertyNames().forEach(noms::add);
        noms.removeAll(STANDARD);
        return noms;
    }

    private String corps(String jeton) {
        return corps(jeton, UUID.randomUUID().toString(), null);
    }

    private String corps(String jeton, String identifiantScan, Long age) {
        ObjectNode corps = json.createObjectNode();
        corps.put("jetonQr", jeton);
        corps.put("identifiantScan", identifiantScan);
        if (age != null) {
            corps.put("ageDuScanMillisecondes", age);
        }
        return corps.toString();
    }

    private HttpResponse<String> scanner(Session session, String idCourse, String corps) throws Exception {
        return api.requete("POST", "/api/benevole/courses/" + idCourse + "/passages", session.entetes(),
                "application/json", corps);
    }

    /** Course de la durée donnée, Léo affecté, Bruno (jeton JB) et Chloé (JC) inscrits, démarrée à DEMARRAGE. */
    private String courseDemarree(String nom, int dureeMinutes) throws Exception {
        return courseDemarree(nom, dureeMinutes, JB);
    }

    private String courseDemarree(String nom, int dureeMinutes, String jetonBruno) throws Exception {
        String id = creerCourse(nom, dureeMinutes);
        assertThat(affecter(id, List.of(idLeo)).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, id).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, id).statusCode()).isEqualTo(201);
        jdbc.update("update inscription set jeton_qr = ? where course_id = ? and compte_id = ?", jetonBruno,
                UUID.fromString(id), idBruno);
        jdbc.update("update inscription set jeton_qr = ? where course_id = ? and compte_id = ?",
                jetonBruno.equals(JB) ? JC : "Cc" + JC.substring(2), UUID.fromString(id), idChloe);
        horloge.fixer(DEMARRAGE);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN + "/" + id + "/demarrage", patron.entetes(), null,
                null);
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        return id;
    }

    private String creerCourse(String nom, int dureeMinutes) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", JOUR);
        corps.put("distanceBoucleMetres", 400);
        corps.put("dureeBoucleMinutes", dureeMinutes);
        corps.put("denivelePositifBoucleMetres", 5);
        corps.put("nombreMaxParticipants", 10);
        corps.put("nombreMaxBoucles", 5);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", "/api/coureur/courses/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> affecter(String idCourse, List<UUID> benevoles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        var liste = corps.putArray("benevoleIds");
        benevoles.forEach(b -> liste.add(b.toString()));
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", patron.entetes(), "application/json",
                corps.toString());
    }

    private void poserPassage(String idInscription, int boucle, Instant scanneLe, String origine) {
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), UUID.fromString(idInscription), boucle,
                scanneLe == null ? null : Timestamp.from(scanneLe), origine);
    }

    private String idInscription(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(idCourse), idCompte).toString();
    }

    private int compterPassages() {
        return jdbc.queryForObject("select count(*) from passage", Integer.class);
    }

    private int compterPassagesDe(UUID idCompte) {
        return jdbc.queryForObject("select count(*) from passage p join inscription i on i.id = p.inscription_id "
                + "where i.compte_id = ?", Integer.class, idCompte);
    }

    private UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(MOT_DE_PASSE),
                role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private UUID insererCourse(String nom, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(JOUR), statut);
        return id;
    }

    private void insererInscription(UUID idCourse, UUID idCompte, int dossard, String jeton, String statut) {
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), idCourse, idCompte, dossard, jeton, statut);
    }

    private List<String> messagesDuJournal() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : journal.evenements()) {
            if (!e.getLoggerName().startsWith("fr.backyard")) {
                continue; // journaux DEBUG des bibliothèques, hors application
            }
            if (e.getFormattedMessage() != null) {
                messages.add(e.getFormattedMessage());
            }
            if (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null) {
                messages.add(e.getThrowableProxy().getMessage());
            }
        }
        return messages;
    }

    private void demarrerJournal() {
        arreterJournal();
        journal = JournalApplicatif.demarrerEnDebug();
    }

    private void arreterJournal() {
        if (journal == null) {
            return;
        }
        journal.fermer();
        journal = null;
    }

    /** Refus 409 sans propriété d'extension autre que celles du coureur éventuellement fournies. */
    private void assertErreurSans(HttpResponse<String> reponse, int statut, String code, String detail)
            throws Exception {
        assertErreur(reponse, statut, code, statut == 404 ? "Introuvable" : "Conflit", detail);
        assertThat(extensions(json.readTree(reponse.body()))).isEmpty();
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code, String titre, String detail)
            throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("title").asString()).isEqualTo(titre);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
        assertThat(corps.get("detail").asString()).isEqualTo(detail);
    }
}
