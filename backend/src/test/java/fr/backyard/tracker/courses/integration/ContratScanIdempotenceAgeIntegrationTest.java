package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 5.1a (CA1) : le contrat d'API du scan, que la file hors ligne consomme, est verrouillé.
 * Idempotence sur l'identifiant de scan quel que soit l'âge renvoyé au rejeu, bornes de l'âge du scan (0 à
 * 86 400 000 ms) et replacement du scan sur sa Boucle par l'âge. La Course X (60 min, 5 Boucles max) est démarrée à
 * t0 = 14:03:27Z ; Bruno (jeton j1) et Chloé (jeton j2) sont inscrits, Léo est affecté.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class ContratScanIdempotenceAgeIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final Instant DEMARRAGE = Instant.parse("2026-10-12T14:03:27.654Z");
    static final Instant T0 = Instant.parse("2026-10-12T14:03:27Z");
    static final String J1 = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    static final String J2 = new StringBuilder(J1).reverse().toString();
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";

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
    Session patron;
    Session leo;
    UUID idBruno;
    UUID idChloe;
    UUID idLeo;
    String x;

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
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        Session bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        Session chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
        x = creerCourse();
        assertThat(affecter(x, List.of(idLeo)).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, x).statusCode()).isEqualTo(201);
        jdbc.update("update inscription set jeton_qr = ? where course_id = ? and compte_id = ?", J1,
                UUID.fromString(x), idBruno);
        jdbc.update("update inscription set jeton_qr = ? where course_id = ? and compte_id = ?", J2,
                UUID.fromString(x), idChloe);
        horloge.fixer(DEMARRAGE);
        HttpResponse<String> demarrage = api.requete("POST", "/api/administration/courses/" + x + "/demarrage",
                patron.entetes(), null, null);
        assertThat(demarrage.statusCode()).as(demarrage.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA1 : rejeu de l'identifiant de scan avec un autre âge : 200 identique, une seule ligne ; autre identifiant : 409 PASSAGE_DEJA_ENREGISTRE")
    void ca1_rejeu_avec_un_age_different_est_idempotent() throws Exception {
        horloge.fixer(T0.plusSeconds(30));
        UUID u1 = UUID.randomUUID();
        HttpResponse<String> creation = scanner(corps(J1, u1.toString(), 0L));
        assertThat(creation.statusCode()).as(creation.body()).isEqualTo(201);
        JsonNode cree = json.readTree(creation.body());
        assertThat(cree.get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(cree.get("passageId").asString()).isEqualTo(u1.toString());

        HttpResponse<String> rejeu = scanner(corps(J1, u1.toString(), 120_000L));
        assertThat(rejeu.statusCode()).as(rejeu.body()).isEqualTo(200);
        assertThat(json.readTree(rejeu.body())).isEqualTo(cree);
        assertThat(json.readTree(rejeu.body()).get("scanneLe").asString())
                .isEqualTo(cree.get("scanneLe").asString());
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);

        HttpResponse<String> autreIdentifiant = scanner(corps(J1, UUID.randomUUID().toString(), 0L));
        assertErreur(autreIdentifiant, 409, "PASSAGE_DEJA_ENREGISTRE");
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 (contrat 7.3 RG5) : âge 86400000 recevable (409 SCAN_ANTERIEUR_AU_DEPART), 86400001 refusé en 409 SCAN_TROP_ANCIEN sans dossard ni pseudo, aucun passage créé")
    void ca1_bornes_de_l_age_du_scan() throws Exception {
        horloge.fixer(T0.plusSeconds(30));

        HttpResponse<String> borneHaute = scanner(corps(J2, UUID.randomUUID().toString(), 86_400_000L));
        assertErreur(borneHaute, 409, "SCAN_ANTERIEUR_AU_DEPART");

        HttpResponse<String> auDessus = scanner(corps(J2, UUID.randomUUID().toString(), 86_400_001L));
        assertErreur(auDessus, 409, "SCAN_TROP_ANCIEN");
        JsonNode probleme = json.readTree(auDessus.body());
        assertThat(probleme.get("detail").asString())
                .isEqualTo("Ce scan date de plus de 24 heures et n'a pas été envoyé.");
        assertThat(probleme.has("dossard")).isFalse();
        assertThat(probleme.has("pseudo")).isFalse();

        assertThat(compterPassagesDe(idChloe)).isZero();
    }

    @Test
    @DisplayName("CA2 (contrat 7.3 RG5a) : rejeu d'un scan accepté avec un âge > 24 h : 200 identique sans doublon ; un autre identifiant avec ce même âge : 409 SCAN_TROP_ANCIEN")
    void ca2_rejeu_avec_un_age_superieur_a_24h_reste_idempotent() throws Exception {
        horloge.fixer(T0.plusSeconds(30));
        UUID u1 = UUID.randomUUID();
        HttpResponse<String> creation = scanner(corps(J1, u1.toString(), 0L));
        assertThat(creation.statusCode()).as(creation.body()).isEqualTo(201);

        HttpResponse<String> rejeu = scanner(corps(J1, u1.toString(), 90_000_000L));
        assertThat(rejeu.statusCode()).as(rejeu.body()).isEqualTo(200);
        assertThat(json.readTree(rejeu.body())).isEqualTo(json.readTree(creation.body()));
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);

        assertErreur(scanner(corps(J1, UUID.randomUUID().toString(), 90_000_000L)), 409, "SCAN_TROP_ANCIEN");
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 (contrat 7.3 RG5b) : avec un âge > 24 h, jeton inconnu 404 JETON_INCONNU, identifiant utilisé par une autre inscription 409 IDENTIFIANT_SCAN_DEJA_UTILISE, Course terminée 409 SCAN_TROP_ANCIEN, aucun passage écrit")
    void ca2_priorites_du_refus_d_age() throws Exception {
        horloge.fixer(T0.plusSeconds(30));
        UUID u1 = UUID.randomUUID();
        assertThat(scanner(corps(J1, u1.toString(), 0L)).statusCode()).isEqualTo(201);

        assertErreur(scanner(corps("jeton-inconnu-" + J1, UUID.randomUUID().toString(), 90_000_000L)), 404,
                "JETON_INCONNU");
        assertErreur(scanner(corps(J2, u1.toString(), 90_000_000L)), 409, "IDENTIFIANT_SCAN_DEJA_UTILISE");

        jdbc.update("update course set statut = 'TERMINEE' where id = ?", UUID.fromString(x));
        assertErreur(scanner(corps(J2, UUID.randomUUID().toString(), 90_000_000L)), 409, "SCAN_TROP_ANCIEN");
        assertThat(compterPassagesDe(idChloe)).isZero();
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 : à t0 + 60 min 5 s, l'âge 10000 replace le scan sur la Boucle 1 (201) alors qu'un âge 0 le placerait en Boucle 2 (409 PASSAGE_PRECEDENT_MANQUANT)")
    void ca1_l_age_replace_le_scan_sur_sa_boucle() throws Exception {
        horloge.fixer(T0.plusSeconds(60 * 60 + 5));

        HttpResponse<String> sansAge = scanner(corps(J2, UUID.randomUUID().toString(), 0L));
        assertErreur(sansAge, 409, "PASSAGE_PRECEDENT_MANQUANT");
        assertThat(json.readTree(sansAge.body()).get("numeroBoucle").asInt()).isEqualTo(2);
        assertThat(compterPassagesDe(idChloe)).isZero();

        HttpResponse<String> avecAge = scanner(corps(J2, UUID.randomUUID().toString(), 10_000L));
        assertThat(avecAge.statusCode()).as(avecAge.body()).isEqualTo(201);
        JsonNode passage = json.readTree(avecAge.body());
        assertThat(passage.get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(passage.get("dossard").asInt()).isEqualTo(2);
        assertThat(Instant.parse(passage.get("scanneLe").asString()))
                .isEqualTo(T0.plusSeconds(60 * 60 + 5).minusMillis(10_000));
        assertThat(compterPassagesDe(idChloe)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 : l'anti-doublon se mesure sur scanneLe (instant de réception moins l'âge)")
    void ca1_anti_doublon_mesure_sur_scanne_le() throws Exception {
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) values (?, ?, 1, ?, "
                + "'SCAN')", UUID.randomUUID(), idInscription(idBruno), Timestamp.from(T0.plusSeconds(59 * 60 + 50)));
        horloge.fixer(T0.plusSeconds(62 * 60));

        // Âge 11 s : scanneLe = t0 + 61 min 49 s, écart 119 s : refusé.
        HttpResponse<String> refus = scanner(corps(J1, UUID.randomUUID().toString(), 11_000L));
        assertErreur(refus, 409, "PASSAGE_TROP_RAPPROCHE");
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);

        // Âge 10 s : scanneLe = t0 + 61 min 50 s, écart 120 s : accepté, Boucle 2.
        HttpResponse<String> accepte = scanner(corps(J1, UUID.randomUUID().toString(), 10_000L));
        assertThat(accepte.statusCode()).as(accepte.body()).isEqualTo(201);
        assertThat(json.readTree(accepte.body()).get("numeroBoucle").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- utilitaires

    private String corps(String jeton, String identifiantScan, long age) {
        ObjectNode corps = json.createObjectNode();
        corps.put("jetonQr", jeton);
        corps.put("identifiantScan", identifiantScan);
        corps.put("ageDuScanMillisecondes", age);
        return corps.toString();
    }

    private HttpResponse<String> scanner(String corps) throws Exception {
        return api.requete("POST", "/api/benevole/courses/" + x + "/passages", leo.entetes(), "application/json",
                corps);
    }

    private String creerCourse() throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", "Backyard des Crêtes");
        corps.put("date", "2026-10-12");
        corps.put("distanceBoucleMetres", 400);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 5);
        corps.put("nombreMaxParticipants", 10);
        corps.put("nombreMaxBoucles", 5);
        HttpResponse<String> reponse = api.requete("POST", "/api/administration/courses", patron.entetes(),
                "application/json", corps.toString());
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
        return api.requete("PUT", "/api/administration/courses/" + idCourse + "/benevoles", patron.entetes(),
                "application/json", corps.toString());
    }

    private UUID idInscription(UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(x), idCompte);
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

    private void assertErreur(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
    }
}
