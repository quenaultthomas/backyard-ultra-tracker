package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Contrat de l'incrément 4.7 : bouclage de la Boucle courante au pilotage (CA3 à CA6). Le contrat vient de la spec.
 * La Course X (boucles de 1 min, 5 boucles max) est démarrée par l'API à 14:03:27.654Z, soit t0 = 14:03:27Z après
 * troncature ; Bruno (dossard 1), Chloé (2) et Tom (3) y sont inscrits, Léo est affecté. Les Passages sont posés
 * par SQL (l'anti-doublon de 4.6 ne concerne que le scan).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class PilotageBouclageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final String NOM_X = "Backyard des Crêtes";
    static final Instant DEMARRAGE = Instant.parse("2026-10-12T14:03:27.654Z");
    static final Instant T0 = Instant.parse("2026-10-12T14:03:27Z");
    static final String JOUR = "2026-10-12";
    static final String JETON_BRUNO = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    static final List<String> CHAMPS = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur", "demarreeLe",
            "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle", "boucleMaxTerminee",
            "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "nombreAyantBoucle", "nombreEnBoucle", "inscritsEnCourse",
            "nombreAbandons", "inscritsEnAbandon", "nombreVainqueurs", "inscritsVainqueurs");
    static final List<String> CHAMPS_INSCRIT = List.of("inscriptionId", "dossard", "pseudo", "statut",
            "nombreBouclesTerminees", "boucleCouranteTerminee", "nombreBouclesCorrigees");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, ?, ?, ?, 400, 1, 5, 100, 5)";
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

    @Autowired
    EntityManagerFactory entityManagerFactory;

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session bruno;
    Session chloe;
    Session tom;
    Session leo;
    UUID idAlice;
    UUID idBruno;
    UUID idChloe;
    UUID idTom;
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
        insererCompte("Nadia", "ADMIN");
        idAlice = insererCompte("Alice", "COUREUR");
        idBruno = insererCompte("Bruno", "COUREUR");
        idChloe = insererCompte("Chloé", "COUREUR");
        idTom = insererCompte("Tom", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
        tom = api.ouvrir("Tom", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    /** X démarrée par l'API (t0 = 14:03:27Z), Bruno 1 (jeton fixé), Chloé 2 et Tom 3 inscrits, Léo affecté. */
    private String courseXDemarree() throws Exception {
        String x = creerCourse(NOM_X, 1, 5);
        assertThat(affecter(x, idLeo).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(tom, x).statusCode()).isEqualTo(201);
        jdbc.update("update inscription set jeton_qr = ? where course_id = ? and compte_id = ?", JETON_BRUNO,
                UUID.fromString(x), idBruno);
        horloge.fixer(DEMARRAGE);
        assertThat(api.requete("POST", CHEMIN + "/" + x + "/demarrage", patron.entetes(), null, null).statusCode())
                .isEqualTo(200);
        return x;
    }

    // ---------------------------------------------------------------- CA3

    @Test
    @DisplayName("CA3 : Patron lit le pilotage à t0 + 90 s (Bruno 1 et 2, Chloé 1, Tom aucun) : 200, no-store, 19 champs, 17 d'origine inchangés, 1 / 2, inscrits triés à 7 champs, aucune fuite, aucune écriture")
    void ca3_instantane_avec_bouclage() throws Exception {
        String x = courseXDemarree();
        poserPassage(x, idBruno, 1, "SCAN");
        poserPassage(x, idBruno, 2, "SCAN");
        poserPassage(x, idChloe, 1, "SCAN");
        horloge.fixer(T0.plusSeconds(90));
        List<Map<String, Object>> passagesAvant = jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");

        HttpResponse<String> reponse = pilotage(patron, x);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json").doesNotContain("problem"));
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(23).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("courseId").asString()).isEqualTo(x);
        assertThat(corps.get("nom").asString()).isEqualTo(NOM_X);
        assertThat(corps.get("logoUrl").isNull()).isTrue();
        assertThat(corps.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(corps.get("heureServeur").asString()).isEqualTo("2026-10-12T14:04:57.000Z");
        assertThat(corps.get("demarreeLe").asString()).isEqualTo("2026-10-12T14:03:27Z");
        assertThat(corps.get("dureeBoucleMinutes").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreMaxBoucles").asInt()).isEqualTo(5);
        assertThat(corps.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(corps.get("derniereBoucle").asBoolean()).isFalse();
        assertThat(corps.get("boucleMaxTerminee").asBoolean()).isFalse();
        assertThat(corps.get("debutBoucleCourante").asString()).isEqualTo("2026-10-12T14:04:27Z");
        assertThat(corps.get("finBoucleCourante").asString()).isEqualTo("2026-10-12T14:05:27Z");
        assertThat(corps.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(30_000L);
        assertThat(corps.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(90_000L);
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(corps.get("nombreAyantBoucle").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(2);
        JsonNode inscrits = corps.get("inscritsEnCourse");
        assertThat(inscrits).hasSize(3);
        assertThat(inscrits).allSatisfy(n -> assertThat(n.propertyNames()).hasSize(7)
                .containsExactlyInAnyOrderElementsOf(CHAMPS_INSCRIT));
        assertThat(inscrits).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2, 3);
        assertThat(inscrits).extracting(n -> n.get("pseudo").asString()).containsExactly("Bruno", "Chloé", "Tom");
        assertThat(inscrits).extracting(n -> n.get("statut").asString()).containsOnly("EN_COURSE");
        assertThat(inscrits).extracting(n -> n.get("nombreBouclesTerminees").asInt()).containsExactly(2, 1, 0);
        assertThat(inscrits).extracting(n -> n.get("boucleCouranteTerminee").asBoolean())
                .containsExactly(true, false, false);
        assertThat(inscrits).extracting(n -> n.get("inscriptionId").asString()).containsExactly(
                idInscription(x, idBruno), idInscription(x, idChloe), idInscription(x, idTom));
        assertAucuneFuite(reponse.body());

        HttpResponse<String> parNadia = pilotage(nadia, x);
        assertThat(parNadia.statusCode()).isEqualTo(200);
        assertThat(json.readTree(parNadia.body())).isEqualTo(corps);

        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : bascule exacte : t0 + 59 999 ms Boucle 1 (2 / 1), t0 + 60 000 ms Boucle 2 (1 / 2), t0 + 120 000 ms Boucle 3 (0 / 3), Boucles terminées inchangées")
    void ca4_bascules_de_boucle() throws Exception {
        String x = courseXDemarree();
        poserPassage(x, idBruno, 1, "SCAN");
        poserPassage(x, idBruno, 2, "SCAN");
        poserPassage(x, idChloe, 1, "SCAN");

        verifierBouclage(x, T0.plusMillis(59_999), 1, 2, 1, true, true, false);
        verifierBouclage(x, T0.plusMillis(60_000), 2, 1, 2, true, false, false);
        verifierBouclage(x, T0.plusMillis(120_000), 3, 0, 3, false, false, false);
        for (Instant instant : List.of(T0.plusMillis(59_999), T0.plusMillis(60_000), T0.plusMillis(120_000))) {
            horloge.fixer(instant);
            JsonNode inscrits = json.readTree(pilotage(patron, x).body()).get("inscritsEnCourse");
            assertThat(inscrits).as(instant.toString()).extracting(n -> n.get("nombreBouclesTerminees").asInt())
                    .containsExactly(2, 1, 0);
        }
    }

    @Test
    @DisplayName("CA4 : Passages 1 à 5 de Bruno : Boucle 5 à t0 + 240 s, plafond figé à t0 + 300 s et t0 + 3 600 s : Bruno a bouclé (5 Boucles terminées), Chloé non")
    void ca4_derniere_boucle_et_plafond() throws Exception {
        String x = courseXDemarree();
        for (int boucle = 1; boucle <= 5; boucle++) {
            poserPassage(x, idBruno, boucle, "SCAN");
        }
        poserPassage(x, idChloe, 1, "SCAN");

        for (long millisecondes : List.of(240_000L, 300_000L, 3_600_000L)) {
            horloge.fixer(T0.plusMillis(millisecondes));
            JsonNode corps = json.readTree(pilotage(patron, x).body());
            String contexte = "t0 + " + millisecondes + " ms";
            assertThat(corps.get("boucleCourante").asInt()).as(contexte).isEqualTo(5);
            assertThat(corps.get("nombreAyantBoucle").asInt()).as(contexte).isEqualTo(1);
            assertThat(corps.get("nombreEnBoucle").asInt()).as(contexte).isEqualTo(2);
            JsonNode inscrits = corps.get("inscritsEnCourse");
            assertThat(inscrits.get(0).get("pseudo").asString()).isEqualTo("Bruno");
            assertThat(inscrits.get(0).get("boucleCouranteTerminee").asBoolean()).as(contexte).isTrue();
            assertThat(inscrits.get(0).get("nombreBouclesTerminees").asInt()).as(contexte).isEqualTo(5);
            assertThat(inscrits.get(1).get("boucleCouranteTerminee").asBoolean()).as(contexte).isFalse();
            assertThat(corps.get("statut").asString()).isEqualTo("EN_COURS");
        }
    }

    @Test
    @DisplayName("CA4 : Passage CORRECTION (scanne_le nul) de Chloé en Boucle 2 à t0 + 90 s : Chloé a bouclé avec 2 Boucles terminées")
    void ca4_passage_correction_compte_comme_un_scan() throws Exception {
        String x = courseXDemarree();
        poserPassage(x, idChloe, 1, "SCAN");
        poserPassage(x, idChloe, 2, "CORRECTION");
        horloge.fixer(T0.plusSeconds(90));

        JsonNode corps = json.readTree(pilotage(patron, x).body());

        JsonNode ligneChloe = corps.get("inscritsEnCourse").get(1);
        assertThat(ligneChloe.get("pseudo").asString()).isEqualTo("Chloé");
        assertThat(ligneChloe.get("boucleCouranteTerminee").asBoolean()).isTrue();
        assertThat(ligneChloe.get("nombreBouclesTerminees").asInt()).isEqualTo(2);
        assertThat(corps.get("nombreAyantBoucle").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA4 : Passage de numéro 4 à t0 + 90 s (Boucle 2) : compté dans les Boucles terminées, jamais a bouclé")
    void ca4_passage_de_numero_superieur_a_la_boucle_courante() throws Exception {
        String x = courseXDemarree();
        poserPassage(x, idChloe, 1, "SCAN");
        poserPassage(x, idChloe, 4, "SCAN");
        horloge.fixer(T0.plusSeconds(90));

        JsonNode corps = json.readTree(pilotage(patron, x).body());

        JsonNode ligneChloe = corps.get("inscritsEnCourse").get(1);
        assertThat(ligneChloe.get("nombreBouclesTerminees").asInt()).isEqualTo(2);
        assertThat(ligneChloe.get("boucleCouranteTerminee").asBoolean()).isFalse();
        assertThat(corps.get("nombreAyantBoucle").asInt()).isZero();
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : POST passages (jeton de Bruno) par Léo puis GET pilotage : Bruno a bouclé, 1 Boucle terminée, 1 / 2")
    void ca5_un_scan_accepte_rend_l_inscrit_a_boucle() throws Exception {
        String x = courseXDemarree();
        horloge.fixer(T0.plusSeconds(30));
        ObjectNode corpsScan = json.createObjectNode();
        corpsScan.put("jetonQr", JETON_BRUNO);
        corpsScan.put("identifiantScan", UUID.randomUUID().toString());

        HttpResponse<String> scan = api.requete("POST", "/api/benevole/courses/" + x + "/passages", leo.entetes(),
                "application/json", corpsScan.toString());
        assertThat(scan.statusCode()).as(scan.body()).isEqualTo(201);
        JsonNode corps = json.readTree(pilotage(patron, x).body());

        JsonNode ligneBruno = corps.get("inscritsEnCourse").get(0);
        assertThat(ligneBruno.get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(ligneBruno.get("boucleCouranteTerminee").asBoolean()).isTrue();
        assertThat(ligneBruno.get("nombreBouclesTerminees").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreAyantBoucle").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA5 : Bruno passé ABANDON en base (avec Passages) : absent de inscritsEnCourse et des compteurs (0 / 2), nombreInscrits 3")
    void ca5_inscription_abandon_hors_du_tableau_et_des_compteurs() throws Exception {
        String x = courseXDemarree();
        poserPassage(x, idBruno, 1, "SCAN");
        horloge.fixer(T0.plusSeconds(30));
        jdbc.update("update inscription set statut = 'ABANDON' where course_id = ? and compte_id = ?",
                UUID.fromString(x), idBruno);

        JsonNode corps = json.readTree(pilotage(patron, x).body());

        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(corps.get("inscritsEnCourse")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Chloé", "Tom");
        assertThat(corps.get("nombreAyantBoucle").asInt()).isZero();
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA5 : Course TERMINEE (SQL) et EN_COURS sans demarree_le : 200, compteurs et boucleCouranteTerminee à null, nombreBouclesTerminees renseigné")
    void ca5_etats_sans_chronometrage() throws Exception {
        UUID z = insererCourse("Terminée", "TERMINEE");
        UUID inscriptionZ = insererInscription(z, idBruno, 1, "EN_COURSE");
        poserPassageSql(inscriptionZ, 1);
        poserPassageSql(inscriptionZ, 2);
        UUID u = insererCourse("Sans heure", "EN_COURS");
        insererInscription(u, idChloe, 1, "EN_COURSE");
        UUID inscriptionTom = insererInscription(u, idTom, 2, "EN_COURSE");
        poserPassageSql(inscriptionTom, 1);

        JsonNode terminee = json.readTree(pilotage(patron, z.toString()).body());
        JsonNode sansHeure = json.readTree(pilotage(patron, u.toString()).body());

        assertThat(terminee.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(terminee.get("boucleCourante").isNull()).isTrue();
        assertThat(terminee.get("nombreAyantBoucle").isNull()).isTrue();
        assertThat(terminee.get("nombreEnBoucle").isNull()).isTrue();
        JsonNode ligneTerminee = terminee.get("inscritsEnCourse").get(0);
        assertThat(ligneTerminee.get("boucleCouranteTerminee").isNull()).isTrue();
        assertThat(ligneTerminee.get("nombreBouclesTerminees").asInt()).isEqualTo(2);
        assertThat(sansHeure.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(sansHeure.get("boucleCourante").isNull()).isTrue();
        assertThat(sansHeure.get("nombreAyantBoucle").isNull()).isTrue();
        assertThat(sansHeure.get("nombreEnBoucle").isNull()).isTrue();
        assertThat(sansHeure.get("inscritsEnCourse")).allSatisfy(
                n -> assertThat(n.get("boucleCouranteTerminee").isNull()).isTrue());
        assertThat(sansHeure.get("inscritsEnCourse")).extracting(n -> n.get("nombreBouclesTerminees").asInt())
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("CA5 : Course EN_PREPARATION : 409 COURSE_NON_DEMARREE ; anonyme 401 ; Alice et Léo 403 ACCES_REFUSE (inchangés)")
    void ca5_erreurs_inchangees() throws Exception {
        String x = courseXDemarree();
        String y = creerCourse("En préparation", 1, 5);

        HttpResponse<String> nonDemarree = pilotage(patron, y);
        HttpResponse<String> anonyme = api.requete("GET", CHEMIN + "/" + x + "/pilotage", Map.of(), null, null);

        assertErreur(nonDemarree, 409, "COURSE_NON_DEMARREE");
        assertErreur(anonyme, 401, "NON_AUTHENTIFIE");
        for (Session session : List.of(alice, leo)) {
            assertErreur(pilotage(session, x), 403, "ACCES_REFUSE");
        }
    }

    @Test
    @DisplayName("CA5 : deux Courses de durées différentes au même Clock : compteurs de bouclage indépendants")
    void ca5_courses_independantes() throws Exception {
        String x = courseXDemarree();
        poserPassage(x, idBruno, 1, "SCAN");
        poserPassage(x, idBruno, 2, "SCAN");
        String lente = creerCourse("Backyard lent", 60, 24);
        assertThat(sinscrire(alice, lente).statusCode()).isEqualTo(201);
        horloge.fixer(Instant.parse("2026-10-12T14:03:57Z"));
        assertThat(api.requete("POST", CHEMIN + "/" + lente + "/demarrage", patron.entetes(), null, null)
                .statusCode()).isEqualTo(200);
        poserPassage(lente, idAlice, 1, "SCAN");
        horloge.fixer(Instant.parse("2026-10-12T14:04:57Z"));

        JsonNode rapide = json.readTree(pilotage(patron, x).body());
        JsonNode longue = json.readTree(pilotage(patron, lente).body());

        assertThat(rapide.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(rapide.get("nombreAyantBoucle").asInt()).isEqualTo(1);
        assertThat(rapide.get("nombreEnBoucle").asInt()).isEqualTo(2);
        assertThat(longue.get("boucleCourante").asInt()).isEqualTo(1);
        assertThat(longue.get("nombreAyantBoucle").asInt()).isEqualTo(1);
        assertThat(longue.get("nombreEnBoucle").asInt()).isZero();
        assertThat(longue.get("inscritsEnCourse")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Alice");
        assertThat(longue.get("inscritsEnCourse").get(0).get("nombreBouclesTerminees").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : le nombre de requêtes SQL d'un GET pilotage est identique avec 3 et avec 30 inscrits ayant des Passages (pas de N+1)")
    void ca6_nombre_de_requetes_constant() throws Exception {
        UUID petite = courseEnCoursAvecInscrits("Petite course", 3);
        UUID grande = courseEnCoursAvecInscrits("Grande course", 30);
        horloge.fixer(T0.plusSeconds(90));
        assertThat(pilotage(patron, petite.toString()).statusCode()).isEqualTo(200); // réchauffe caches et plans

        long avecTrois = requetesPourUnPilotage(petite);
        long avecTrente = requetesPourUnPilotage(grande);

        assertThat(avecTrois).isPositive();
        assertThat(avecTrente).isEqualTo(avecTrois);
        JsonNode corps = json.readTree(pilotage(patron, grande.toString()).body());
        assertThat(corps.get("inscritsEnCourse")).hasSize(30);
        assertThat(corps.get("nombreAyantBoucle").asInt()).isEqualTo(15);
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(15);
    }

    @Test
    @DisplayName("CA6 : après des GET en 200, 403, 404 et 409, aucune ligne INFO ou plus ; ni pseudo, ni nom de Course, ni identifiant de Compte au journal")
    void ca6_journal_silencieux() throws Exception {
        String x = courseXDemarree();
        String preparation = creerCourse("Préparation discrète", 1, 5);
        poserPassage(x, idBruno, 1, "SCAN");
        horloge.fixer(T0.plusSeconds(90));
        demarrerJournal();

        assertThat(pilotage(patron, x).statusCode()).isEqualTo(200);
        assertThat(pilotage(nadia, x).statusCode()).isEqualTo(200);
        assertThat(pilotage(alice, x).statusCode()).isEqualTo(403);
        assertThat(pilotage(patron, ID_INCONNU).statusCode()).isEqualTo(404);
        assertThat(pilotage(patron, preparation).statusCode()).isEqualTo(409);

        assertThat(journal.list).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> interdits = List.of(NOM_X, "Préparation discrète", "Bruno", "Chloé", "Tom", "Patron", "Nadia",
                idBruno.toString(), idChloe.toString(), idTom.toString(), idAlice.toString(), idLeo.toString());
        assertThat(messagesDuJournal()).allSatisfy(m -> assertThat(m).doesNotContain(interdits));
    }

    @Test
    @DisplayName("CA6 : aucun changeset ajouté par 4.7 : databasechangelog se termine par 0009-passage")
    void ca6_aucune_migration() {
        List<String> ids = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);

        assertThat(ids).isNotEmpty();
        assertThat(ids.get(ids.size() - 1)).isEqualTo("0010-abandon-inscription");
    }

    @Test
    @DisplayName("CA6 : ArchUnit : la règle de progression (ProgressionCoureur, Bouclage) ne dépend ni de Spring ni de la persistance")
    void ca6_regle_de_progression_dans_le_domaine_pur() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("fr.backyard.tracker.courses");

        ArchRuleDefinition.classes().that().haveSimpleNameStartingWith("ProgressionCoureur")
                .or().haveSimpleNameStartingWith("Bouclage")
                .should().resideInAPackage("fr.backyard.tracker.courses.domaine..")
                .andShould().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "fr.backyard.tracker.courses.domaine..")
                .allowEmptyShould(false)
                .check(classes);
    }

    // ---------------------------------------------------------------- utilitaires

    private long requetesPourUnPilotage(UUID idCourse) throws Exception {
        var statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();
        assertThat(pilotage(patron, idCourse.toString()).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    /** Course EN_COURS (SQL, départ t0) avec ce nombre d'inscrits : Passage 1 pour tous, Passage 2 pour la moitié. */
    private UUID courseEnCoursAvecInscrits(String nom, int nombre) {
        UUID course = insererCourse(nom, "EN_COURS");
        jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(T0), course);
        for (int i = 1; i <= nombre; i++) {
            UUID compte = insererCompte("Coureur" + nom.charAt(0) + i, "COUREUR");
            UUID inscription = insererInscription(course, compte, i, "EN_COURSE");
            poserPassageSql(inscription, 1);
            if (i % 2 == 0) {
                poserPassageSql(inscription, 2);
            }
        }
        return course;
    }

    private void verifierBouclage(String idCourse, Instant instant, int boucle, int ayantBoucle, int enBoucle,
                                  boolean bruno, boolean chloe, boolean tom) throws Exception {
        horloge.fixer(instant);
        JsonNode corps = json.readTree(pilotage(patron, idCourse).body());
        String contexte = instant.toString();
        assertThat(corps.get("boucleCourante").asInt()).as(contexte).isEqualTo(boucle);
        assertThat(corps.get("nombreAyantBoucle").asInt()).as(contexte).isEqualTo(ayantBoucle);
        assertThat(corps.get("nombreEnBoucle").asInt()).as(contexte).isEqualTo(enBoucle);
        assertThat(corps.get("inscritsEnCourse")).as(contexte)
                .extracting(n -> n.get("boucleCouranteTerminee").asBoolean()).containsExactly(bruno, chloe, tom);
    }

    private void assertAucuneFuite(String corps) {
        List<String> jetons = jdbc.queryForList("select jeton_qr from inscription", String.class);
        List<String> idsPassages = jdbc.queryForList("select id::text from passage", String.class);
        assertThat(corps).doesNotContain(jetons).doesNotContain(idsPassages).doesNotContain("jetonQr")
                .doesNotContain("passageId").doesNotContain("scanneLe").doesNotContain("compteId");
        for (UUID compte : List.of(idAlice, idBruno, idChloe, idTom, idLeo)) {
            assertThat(corps).doesNotContain(compte.toString());
        }
        for (String heureScan : jdbc.queryForList("select to_char(scanne_le at time zone 'UTC', "
                + "'HH24:MI:SS') from passage where scanne_le is not null", String.class)) {
            assertThat(corps).as("heure de scan").doesNotContain("T" + heureScan);
        }
    }

    private HttpResponse<String> pilotage(Session session, String idCourse) throws Exception {
        return api.requete("GET", CHEMIN + "/" + idCourse + "/pilotage", session.enteteLecture(), null, null);
    }

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", "/api/coureur/courses/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> affecter(String idCourse, UUID benevole) throws Exception {
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + benevole + "\"]}");
    }

    private String idInscription(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(idCourse), idCompte).toString();
    }

    private void poserPassage(String idCourse, UUID idCompte, int boucle, String origine) {
        UUID inscription = UUID.fromString(idInscription(idCourse, idCompte));
        Timestamp scanneLe = "SCAN".equals(origine)
                ? Timestamp.from(T0.plusSeconds(60L * (boucle - 1) + 5)) : null;
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, boucle, scanneLe, origine);
    }

    private void poserPassageSql(UUID inscription, int boucle) {
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, boucle,
                Timestamp.from(T0.plusSeconds(60L * (boucle - 1) + 5)), "SCAN");
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

    private UUID insererInscription(UUID idCourse, UUID idCompte, int dossard, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, ?)", id, idCourse, idCompte, dossard, UUID.randomUUID().toString(), statut);
        return id;
    }

    private String creerCourse(String nom, int dureeMinutes, int maxBoucles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", JOUR);
        corps.put("distanceBoucleMetres", 400);
        corps.put("dureeBoucleMinutes", dureeMinutes);
        corps.put("denivelePositifBoucleMetres", 5);
        corps.put("nombreMaxParticipants", 10);
        corps.put("nombreMaxBoucles", maxBoucles);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private List<String> messagesDuJournal() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : journal.list) {
            if (!e.getLoggerName().startsWith("fr.backyard")) {
                continue; // journaux DEBUG des bibliothèques (Spring Security trace le principal), hors application
            }
            messages.add(e.getFormattedMessage());
            if (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null) {
                messages.add(e.getThrowableProxy().getMessage());
            }
        }
        return messages;
    }

    private void demarrerJournal() {
        journal = new ListAppender<>();
        journal.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        journal.start();
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.addAppender(journal);
        racine.setLevel(Level.DEBUG);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(Level.INFO);
    }

    private void arreterJournal() {
        if (journal == null) {
            return;
        }
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(null);
        journal = null;
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo(code);
    }
}
