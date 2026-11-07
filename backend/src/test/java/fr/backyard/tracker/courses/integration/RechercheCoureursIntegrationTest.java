package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.CompteJpaAdapter;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 9.5 : GET /api/administration/coureurs?q= (CA4 à CA11). Les Comptes sont insérés en base ;
 * l'action testée passe par l'API. Le tri n'est assert que pour des pseudos ASCII (tri de PostgreSQL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class RechercheCoureursIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_COUREUR = "mot-de-passe-coureur-1";
    static final String RECHERCHE = "/api/administration/coureurs";
    static final String SUPPRESSION = "/api/comptes/moi/suppression";
    static final String VIDE = "{\"coureurs\":[],\"plusDeResultats\":false}";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, 'EN_PREPARATION', 400, 60, 0, 10, 5, null)";
    static final String MESSAGE_JOURNAL = "Recherche de coureurs (appelant ";
    static final String LOGGER_CAS_D_USAGE = "fr.backyard.tracker.comptes.application.RechercherCoureurs";

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

    @MockitoSpyBean
    CompteJpaAdapter depotComptes;

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    JournalApplicatif journal;
    String empreinte;
    Session patron;
    Session nadia;
    Session leo;
    Session alice;
    UUID idNadia;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        empreinte = encodeur.encode(MOT_DE_PASSE_COUREUR);
        idNadia = inserer("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN, true);
        inserer("Léo", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, true);
        inserer("Alice", "COUREUR", MOT_DE_PASSE_COUREUR, true);
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        leo = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE_COUREUR);
        journal = JournalApplicatif.demarrer().niveau("fr.backyard.tracker", Level.DEBUG);
    }

    @AfterEach
    void nettoyer() {
        journal.fermer();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Nadia cherche en majuscules, minuscules, au milieu et par pseudo exact de 3 caractères : 200, {id, pseudo} à la casse d'origine, clés exactes, no-store, base inchangée")
    void ca4_recherche_nominale() throws Exception {
        UUID zoe = coureur("Zoé-k4x");
        UUID zoeCourt = coureur("Zoé");
        UUID eloise = coureur("Éloïse-k4x");
        List<Map<String, Object>> avant = jdbc.queryForList("select * from compte order by id");

        for (String fragment : List.of("ZOÉ-K4", "zoé-k4", "oé-k4", "É-K")) {
            HttpResponse<String> reponse = chercher(nadia, "q=" + enc(fragment));
            assertThat(reponse.statusCode()).as(fragment).isEqualTo(200);
            JsonNode corps = json.readTree(reponse.body());
            assertThat(pseudos(corps)).as(fragment).containsExactly("Zoé-k4x");
            JsonNode resultat = corps.get("coureurs").get(0);
            assertThat(resultat.get("id").asString()).isEqualTo(zoe.toString());
            assertThat(champs(resultat)).containsExactlyInAnyOrder("id", "pseudo");
            assertThat(champs(corps)).containsExactlyInAnyOrder("coureurs", "plusDeResultats");
            assertThat(corps.get("plusDeResultats").asBoolean()).isFalse();
        }
        HttpResponse<String> exact = chercher(nadia, "q=" + enc("Zoé"));
        assertThat(pseudos(json.readTree(exact.body()))).containsExactlyInAnyOrder("Zoé", "Zoé-k4x");
        assertThat(ids(json.readTree(exact.body()))).contains(zoeCourt.toString());

        HttpResponse<String> accents = chercher(nadia, "q=" + enc("éloï"));
        assertThat(pseudos(json.readTree(accents.body()))).containsExactly("Éloïse-k4x");
        assertThat(ids(json.readTree(accents.body()))).containsExactly(eloise.toString());
        assertThat(pseudos(json.readTree(chercher(nadia, "q=" + enc("ÉLOÏ")).body()))).containsExactly("Éloïse-k4x");
        assertThat(json.readTree(chercher(nadia, "q=eloi").body()).get("coureurs")).isEmpty();

        HttpResponse<String> reponse = chercher(nadia, "q=zoé-k4");
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("no-store"));
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/json"));
        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(avant);

        HttpResponse<String> parLeMaitre = chercher(patron, "q=zoé-k4");
        assertThat(parLeMaitre.statusCode()).isEqualTo(200);
        assertThat(json.readTree(parLeMaitre.body())).isEqualTo(json.readTree(reponse.body()));
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : seul le coureur actif est renvoyé (ni bénévole, ni admin, ni anonymisé, ni inactif) ; la clé « #… » de l'anonymisé, « anonyme » et « coureur » ne le trouvent pas")
    void ca5_exclusions() throws Exception {
        UUID actif = coureur("qx7-actif");
        inserer("qx7-bene", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, true);
        inserer("qx7-admin", "ADMIN", MOT_DE_PASSE_ADMIN, true);
        UUID inactif = coureur("qx7-inactif");
        jdbc.update("update compte set actif = false where id = ?", inactif);
        UUID supprime = coureur("qx7-supp");
        Session sessionSupprime = api.ouvrir("qx7-supp", MOT_DE_PASSE_COUREUR);
        assertThat(api.requete("POST", SUPPRESSION, sessionSupprime.entetes(), "application/json",
                "{\"motDePasseActuel\":\"" + MOT_DE_PASSE_COUREUR + "\"}").statusCode()).isEqualTo(204);
        String cle = jdbc.queryForObject("select pseudo_normalise from compte where id = ?", String.class, supprime);
        assertThat(cle).startsWith("#");

        JsonNode corps = json.readTree(chercher(nadia, "q=qx7").body());
        assertThat(pseudos(corps)).containsExactly("qx7-actif");
        assertThat(ids(corps)).containsExactly(actif.toString());

        for (String fragment : List.of(cle.substring(1, 4), cle.substring(10, 13), cle.substring(20, 23),
                "anonyme", "coureur")) {
            JsonNode trouves = json.readTree(chercher(nadia, "q=" + enc(fragment)).body());
            assertThat(ids(trouves)).as(fragment).doesNotContain(supprime.toString());
            assertThat(pseudos(trouves)).as(fragment).doesNotContain("Coureur anonyme");
        }
        assertThat(json.readTree(chercher(nadia, "q=anonyme").body()).get("coureurs")).isEmpty();
        assertThat(json.readTree(chercher(nadia, "q=" + enc("#" + cle.substring(1, 4))).body()).get("coureurs"))
                .isEmpty();
    }

    @Test
    @DisplayName("CA5 : le pseudo d'un coureur supprimé, recréé par un nouveau coureur : un seul résultat, le nouvel id")
    void ca5_pseudo_libere_puis_recree() throws Exception {
        UUID ancien = coureur("rec9-zed");
        Session session = api.ouvrir("rec9-zed", MOT_DE_PASSE_COUREUR);
        assertThat(api.requete("POST", SUPPRESSION, session.entetes(), "application/json",
                "{\"motDePasseActuel\":\"" + MOT_DE_PASSE_COUREUR + "\"}").statusCode()).isEqualTo(204);
        assertThat(json.readTree(chercher(nadia, "q=rec9").body()).get("coureurs")).isEmpty();

        String jeton = api.jetonValide();
        HttpResponse<String> creation = api.requete("POST", "/api/comptes",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), "application/json",
                "{\"pseudo\":\"rec9-zed\",\"motDePasse\":\"" + MOT_DE_PASSE_COUREUR + "\"}");
        assertThat(creation.statusCode()).isEqualTo(201);

        JsonNode corps = json.readTree(chercher(nadia, "q=rec9").body());
        assertThat(pseudos(corps)).containsExactly("rec9-zed");
        assertThat(ids(corps)).doesNotContain(ancien.toString());
    }

    @Test
    @DisplayName("CA5 : un coureur inscrit à une Course EN_PREPARATION est trouvé, comme un coureur sans inscription")
    void ca5_coureur_inscrit_trouve() throws Exception {
        UUID inscrit = coureur("ins5-a");
        coureur("ins5-b");
        UUID course = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, course, "Course d'inscription", Date.valueOf("2026-10-09"));
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, 1, ?, 'EN_COURSE')", UUID.randomUUID(), course, inscrit,
                UUID.randomUUID().toString());

        assertThat(pseudos(json.readTree(chercher(nadia, "q=ins5").body()))).containsExactly("ins5-a", "ins5-b");
    }

    @Test
    @DisplayName("CA5 : un Compte non éligible renvoyé par le dépôt n'est jamais exposé (recontrôle du cas d'usage)")
    void ca5_recontrole_du_cas_d_usage() throws Exception {
        UUID benevole = inserer("rcl3-bene", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, true);
        UUID coureur = coureur("rcl3-cour");
        var compteBenevole = depotComptes.trouverParId(benevole).orElseThrow();
        org.mockito.Mockito.doReturn(List.of(compteBenevole))
                .when(depotComptes).rechercherCoureursConnectables(any(), anyInt());

        JsonNode corps = json.readTree(chercher(nadia, "q=rcl3").body());

        assertThat(ids(corps)).doesNotContain(benevole.toString()).doesNotContain(coureur.toString());
        assertThat(corps.get("coureurs")).isEmpty();
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : 12 coureurs : le préfixe donne 01 à 10 dans l'ordre et plusDeResultats vrai ; préfixe + 1 donne 10, 11, 12 et faux")
    void ca6_limite_et_plus_de_resultats() throws Exception {
        for (int i = 1; i <= 12; i++) {
            coureur("pr6k%02d".formatted(i));
        }

        JsonNode dix = json.readTree(chercher(nadia, "q=pr6k").body());
        assertThat(pseudos(dix)).containsExactly("pr6k01", "pr6k02", "pr6k03", "pr6k04", "pr6k05", "pr6k06",
                "pr6k07", "pr6k08", "pr6k09", "pr6k10");
        assertThat(dix.get("plusDeResultats").asBoolean()).isTrue();

        JsonNode trois = json.readTree(chercher(nadia, "q=pr6k1").body());
        assertThat(pseudos(trois)).containsExactly("pr6k10", "pr6k11", "pr6k12");
        assertThat(trois.get("plusDeResultats").asBoolean()).isFalse();

        assertThat(pseudos(json.readTree(chercher(nadia, "q=PR6K12").body()))).containsExactly("pr6k12");
    }

    @Test
    @DisplayName("CA6 : exactement 10 correspondances : 10 résultats et plusDeResultats faux")
    void ca6_exactement_dix() throws Exception {
        for (int i = 1; i <= 10; i++) {
            coureur("ex6k%02d".formatted(i));
        }

        JsonNode corps = json.readTree(chercher(nadia, "q=ex6k").body());

        assertThat(corps.get("coureurs")).hasSize(10);
        assertThat(corps.get("plusDeResultats").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("CA6 : aucune correspondance : 200 {\"coureurs\":[],\"plusDeResultats\":false}")
    void ca6_aucune_correspondance() throws Exception {
        coureur("pr6k01");

        HttpResponse<String> reponse = chercher(nadia, "q=zzz9");

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body())).isEqualTo(json.readTree(VIDE));
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : q absent, vide, trop court, rogné trop court, NUL de bord, 31 caractères : 400 VALIDATION_ECHOUEE au format du contrat, base non interrogée, aucune ligne INFO")
    void ca7_validation() throws Exception {
        coureur("val7-a");
        int marque = journal.marque();
        Map<String, String> attendus = new java.util.LinkedHashMap<>();
        attendus.put("", "RECHERCHE_REQUISE");
        attendus.put("q=", "RECHERCHE_TROP_COURTE");
        attendus.put("q=ab", "RECHERCHE_TROP_COURTE");
        attendus.put("q=%20%20ab%20%20", "RECHERCHE_TROP_COURTE");
        attendus.put("q=%20%20%20", "RECHERCHE_TROP_COURTE");
        attendus.put("q=%00", "RECHERCHE_TROP_COURTE");
        attendus.put("q=%00ab", "RECHERCHE_TROP_COURTE");
        attendus.put("q=" + "a".repeat(31), "RECHERCHE_TROP_LONGUE");
        attendus.put("q=%20" + "a".repeat(31) + "%20", "RECHERCHE_TROP_LONGUE");
        Map<String, String> messages = Map.of(
                "RECHERCHE_REQUISE", "La recherche est obligatoire.",
                "RECHERCHE_TROP_COURTE", "La recherche doit contenir au moins 3 caractères.",
                "RECHERCHE_TROP_LONGUE", "La recherche ne peut pas dépasser 30 caractères.");

        for (Map.Entry<String, String> cas : attendus.entrySet()) {
            HttpResponse<String> reponse = chercher(nadia, cas.getKey());
            assertThat(reponse.statusCode()).as(cas.getKey()).isEqualTo(400);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    valeur -> assertThat(valeur).contains("application/problem+json"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.get("type").asString()).isEqualTo("about:blank");
            assertThat(corps.get("title").asString()).isEqualTo("Requête invalide");
            assertThat(corps.get("status").asInt()).isEqualTo(400);
            assertThat(corps.get("detail").asString()).isEqualTo("Certains champs sont invalides.");
            assertThat(corps.get("instance").asString()).isEqualTo(RECHERCHE);
            assertThat(corps.get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
            assertThat(corps.get("erreurs")).hasSize(1);
            JsonNode erreur = corps.get("erreurs").get(0);
            assertThat(erreur.get("champ").asString()).isEqualTo("q");
            assertThat(erreur.get("code").asString()).as(cas.getKey()).isEqualTo(cas.getValue());
            assertThat(erreur.get("message").asString()).isEqualTo(messages.get(cas.getValue()));
        }

        verify(depotComptes, never()).rechercherCoureursConnectables(any(), anyInt());
        assertThat(journal.applicatifsDepuis(marque).stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(MESSAGE_JOURNAL))).isEmpty();
    }

    @Test
    @DisplayName("CA7 : le détail et les messages d'un 400 ne répètent pas la saisie")
    void ca7_saisie_non_repetee() throws Exception {
        for (String saisie : List.of("zqj", "zq")) {
            String brut = saisie.length() < 3 ? saisie : saisie.repeat(11);
            HttpResponse<String> reponse = chercher(nadia, "q=" + brut);
            assertThat(reponse.statusCode()).isEqualTo(400);
            assertThat(reponse.body()).doesNotContain(brut).doesNotContain("zq");
        }
    }

    @Test
    @DisplayName("CA7 : abc, '  abc  ' et 30 caractères sont acceptés (200)")
    void ca7_valeurs_limites_acceptees() throws Exception {
        coureur("abc");
        coureur("b".repeat(30));

        JsonNode abc = json.readTree(chercher(nadia, "q=abc").body());
        assertThat(pseudos(abc)).containsExactly("abc");
        assertThat(json.readTree(chercher(nadia, "q=%20%20abc%20%20").body())).isEqualTo(abc);
        HttpResponse<String> trente = chercher(nadia, "q=" + "b".repeat(30));
        assertThat(trente.statusCode()).isEqualTo(200);
        assertThat(pseudos(json.readTree(trente.body()))).containsExactly("b".repeat(30));
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : le _ est littéral : p_bc… ne trouve pas pxbc… ; ___ ne trouve que les pseudos contenant trois _ ; . et - sont littéraux")
    void ca8_motif_litteral() throws Exception {
        coureur("p_bcm4z");
        coureur("pxbcm4z");
        coureur("tri___ple");
        coureur("du__o");
        coureur("zk.j9");
        coureur("zkxj9");
        coureur("zk-j9");

        assertThat(pseudos(json.readTree(chercher(nadia, "q=p_bcm4").body()))).containsExactly("p_bcm4z");
        assertThat(pseudos(json.readTree(chercher(nadia, "q=p_b").body()))).containsExactly("p_bcm4z");
        assertThat(pseudos(json.readTree(chercher(nadia, "q=___").body()))).containsExactly("tri___ple");
        assertThat(pseudos(json.readTree(chercher(nadia, "q=zk.j").body()))).containsExactly("zk.j9");
        assertThat(pseudos(json.readTree(chercher(nadia, "q=zk-j").body()))).containsExactly("zk-j9");
    }

    @Test
    @DisplayName("CA8 : %%%, a%c, \\\\\\, a b, ' OR 1=1 --, <script>, a NUL b, ab… : 200 liste vide, jamais 500 ni aucun Compte, base non interrogée")
    void ca8_caracteres_hors_alphabet() throws Exception {
        coureur("abc");
        coureur("a-c");
        coureur("a.c");
        coureur("a_c");

        for (String brut : List.of("%25%25%25", "a%25c", "%5C%5C%5C", "a%20b", "a%20%20b",
                "'%20OR%201=1%20--", "%3Cscript%3E", "a%00b", "ab%E2%80%A6", "a%22c", "a%5Cc")) {
            HttpResponse<String> reponse = chercher(nadia, "q=" + brut);
            assertThat(reponse.statusCode()).as(brut).isEqualTo(200);
            assertThat(json.readTree(reponse.body())).as(brut).isEqualTo(json.readTree(VIDE));
        }
        verify(depotComptes, never()).rechercherCoureursConnectables(any(), anyInt());
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : anonyme 401 NON_AUTHENTIFIE")
    void ca9_anonyme() throws Exception {
        HttpResponse<String> reponse = api.requete("GET", RECHERCHE + "?q=abc", Map.of(), null, null);

        assertProbleme(reponse, 401, "Authentification requise", "NON_AUTHENTIFIE", "Vous devez être connecté.");
        verify(depotComptes, never()).rechercherCoureursConnectables(any(), anyInt());
    }

    @Test
    @DisplayName("CA9 : Léo (BENEVOLE) et Alice (COUREUR) 403 ACCES_REFUSE, même pour q absent ou invalide")
    void ca9_roles_insuffisants() throws Exception {
        coureur("rol9-a");
        for (Session session : List.of(leo, alice)) {
            for (String requete : List.of("", "q=", "q=ab", "q=" + "a".repeat(31), "q=rol9", "q=%00")) {
                HttpResponse<String> reponse = chercher(session, requete);
                assertProbleme(reponse, 403, "Accès refusé", "ACCES_REFUSE", "Vous n'avez pas les droits nécessaires.");
                assertThat(json.readTree(reponse.body()).has("erreurs")).isFalse();
            }
        }
        verify(depotComptes, never()).rechercherCoureursConnectables(any(), anyInt());
    }

    @Test
    @DisplayName("CA9 : Nadia (ADMIN) et Patron (ADMIN_MASTER) obtiennent 200 ; les règles de /admins restent réservées à l'admin master")
    void ca9_roles_autorises() throws Exception {
        coureur("rol9-a");

        assertThat(chercher(nadia, "q=rol9").statusCode()).isEqualTo(200);
        assertThat(chercher(patron, "q=rol9").statusCode()).isEqualTo(200);
        assertThat(chercher(nadia, "q=ab").statusCode()).isEqualTo(400);
        assertThat(chercher(patron, "q=ab").statusCode()).isEqualTo(400);

        assertThat(api.requete("GET", "/api/administration/admins", nadia.enteteLecture(), null, null).statusCode())
                .isEqualTo(403);
        assertThat(api.requete("GET", "/api/administration/admins", patron.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("CA9 : POST, PUT, PATCH, DELETE avec jeton CSRF : 405 ou 404, jamais 2xx ; sans jeton : 403 CSRF_INVALIDE ; base inchangée")
    void ca9_autres_methodes() throws Exception {
        coureur("met9-a");
        List<Map<String, Object>> avant = jdbc.queryForList("select * from compte order by id");

        for (String methode : List.of("POST", "PUT", "PATCH", "DELETE")) {
            for (Session session : List.of(nadia, patron)) {
                HttpResponse<String> avecJeton = api.requete(methode, RECHERCHE + "?q=met9", session.entetes(),
                        "application/json", "{}");
                assertThat(avecJeton.statusCode()).as(methode).isIn(404, 405);
                HttpResponse<String> sansJeton = api.requete(methode, RECHERCHE + "?q=met9", session.enteteLecture(),
                        "application/json", "{}");
                assertProbleme(sansJeton, 403, "Accès refusé", "CSRF_INVALIDE", "Jeton CSRF absent ou invalide.");
            }
        }
        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA9 : GET /api/administration/coureurs/<uuid> : 404")
    void ca9_sous_chemin_inconnu() throws Exception {
        HttpResponse<String> reponse = api.requete("GET", RECHERCHE + "/" + UUID.randomUUID(),
                nadia.enteteLecture(), null, null);

        assertThat(reponse.statusCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : une recherche réussie journalise une seule ligne INFO « Recherche de coureurs (appelant <id>, <n> résultats) » sans fragment ni pseudo ni mot de passe")
    void ca10_journal() throws Exception {
        coureur("jrn7-aa");
        coureur("jrn7-bb");
        int marque = journal.marque();

        assertThat(chercher(nadia, "q=jrn7").statusCode()).isEqualTo(200);

        List<ILoggingEvent> lignes = journal.applicatifsDepuis(marque).stream()
                .filter(e -> e.getFormattedMessage().startsWith(MESSAGE_JOURNAL)).toList();
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(lignes.get(0).getLoggerName()).isEqualTo(LOGGER_CAS_D_USAGE);
        assertThat(lignes.get(0).getFormattedMessage())
                .isEqualTo("Recherche de coureurs (appelant " + idNadia + ", 2 résultats)");
        for (String message : journal.depuis(marque).stream().map(ILoggingEvent::getFormattedMessage).toList()) {
            assertThat(message).doesNotContain("jrn7").doesNotContain(MOT_DE_PASSE_COUREUR)
                    .doesNotContain(MOT_DE_PASSE_ADMIN).doesNotContain(MOT_DE_PASSE_PATRON);
        }
    }

    @Test
    @DisplayName("CA10 : panne simulée de la base : 500 ERREUR_INTERNE sans détail technique")
    void ca10_panne_de_la_base() throws Exception {
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotComptes)
                .rechercherCoureursConnectables(any(), anyInt());

        HttpResponse<String> reponse = chercher(nadia, "q=abc");

        assertThat(reponse.statusCode()).isEqualTo(500);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/problem+json"));
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("secret-technique").doesNotContain("IllegalState");
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : aucune migration ajoutée (0002 à 0012-photo-compte), schéma validé, aucune écriture par la recherche")
    void ca11_aucune_migration_ni_ecriture() throws Exception {
        List<String> avant = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        List<Map<String, Object>> comptes = jdbc.queryForList("select * from compte order by id");
        List<Map<String, Object>> inscriptions = jdbc.queryForList("select * from inscription order by id");

        chercher(nadia, "q=ali");
        chercher(nadia, "q=ab");

        List<String> apres = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        assertThat(apres).isEqualTo(avant);
        assertThat(apres.get(apres.size() - 1)).isEqualTo("0012-photo-compte");
        Set<String> identifiants = new HashSet<>(apres);
        for (int i = 2; i <= 10; i++) {
            String prefixe = "%04d-".formatted(i);
            assertThat(identifiants.stream().anyMatch(id -> id.startsWith(prefixe))).as(prefixe).isTrue();
        }
        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptes);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptions);
    }

    @Test
    @DisplayName("CA11 : non-régression, la recherche trouve un coureur créé par l'API publique et la connexion d'Alice reste valide")
    void ca11_non_regression() throws Exception {
        assertThat(pseudos(json.readTree(chercher(patron, "q=ali").body()))).containsExactly("Alice");
        assertThat(api.requete("GET", "/api/comptes/moi", alice.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- aides

    private UUID coureur(String pseudo) {
        return inserer(pseudo, "COUREUR", MOT_DE_PASSE_COUREUR, true);
    }

    private UUID inserer(String pseudo, String role, String motDePasse, boolean actif) {
        UUID id = UUID.randomUUID();
        String hash = MOT_DE_PASSE_COUREUR.equals(motDePasse) ? empreinte : encodeur.encode(motDePasse);
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), hash, role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        if (!actif) {
            jdbc.update("update compte set actif = false where id = ?", id);
        }
        return id;
    }

    private HttpResponse<String> chercher(Session session, String requete) throws Exception {
        String chemin = requete.isEmpty() ? RECHERCHE : RECHERCHE + "?" + requete;
        return api.requete("GET", chemin, session.enteteLecture(), null, null);
    }

    private static String enc(String valeur) {
        return URLEncoder.encode(valeur, StandardCharsets.UTF_8);
    }

    private List<String> pseudos(JsonNode corps) {
        List<String> valeurs = new ArrayList<>();
        corps.get("coureurs").forEach(c -> valeurs.add(c.get("pseudo").asString()));
        return valeurs;
    }

    private List<String> ids(JsonNode corps) {
        List<String> valeurs = new ArrayList<>();
        corps.get("coureurs").forEach(c -> valeurs.add(c.get("id").asString()));
        return valeurs;
    }

    private List<String> champs(JsonNode noeud) {
        return new ArrayList<>(noeud.propertyNames());
    }

    private void assertProbleme(HttpResponse<String> reponse, int statut, String titre, String code, String detail) {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("title").asString()).isEqualTo(titre);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
        assertThat(corps.get("detail").asString()).isEqualTo(detail);
    }
}
