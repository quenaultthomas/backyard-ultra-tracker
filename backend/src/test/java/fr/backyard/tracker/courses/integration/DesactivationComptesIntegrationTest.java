package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.ConsulterCompteConnecte;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.CompteJpaAdapter;
import fr.backyard.tracker.comptes.infrastructure.RegistreSessionsEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 8.1 : désactivation et réactivation des Comptes ADMIN et BENEVOLE (CA8 à CA18).
 * Les Comptes sont insérés en base ; les actions testées passent par l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class DesactivationComptesIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_ALICE = "un-mot-de-passe-12";
    static final String ADMINS = "/api/administration/admins";
    static final String BENEVOLES = "/api/administration/benevoles";
    static final String COURSES = "/api/administration/courses";
    static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27Z");
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String CSRF_DETAIL = "Jeton CSRF absent ou invalide.";
    static final String INTROUVABLE_DETAIL = "Ce compte est introuvable.";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, ?, 400, 60, 0, 10, 5, ?)";
    static final String NOM_P = "Course Secrete Preparation";

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

    @MockitoSpyBean
    CompteJpaAdapter depotComptes;

    @MockitoSpyBean
    RegistreSessionsEnMemoire registreSessions;

    @MockitoSpyBean
    ConsulterCompteConnecte consulterCompteConnecte;

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    JournalApplicatif journal;
    Session patron;
    Session nadia;
    UUID idPatron;
    UUID idNadia;
    UUID idPaul;
    UUID idLeo;
    UUID idMarc;
    UUID idAlice;
    UUID p;
    UUID c;
    UUID t;
    UUID inscriptionAlice;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(MAINTENANT);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        idPatron = jdbc.queryForObject("select id from compte where role = 'ADMIN_MASTER'", UUID.class);
        idNadia = insererCompte("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN, 0);
        idPaul = insererCompte("Paul", "ADMIN", MOT_DE_PASSE_ADMIN, 1);
        idLeo = insererCompte("Léo", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 2);
        idMarc = insererCompte("Marc", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 3);
        idAlice = insererCompte("Alice", "COUREUR", MOT_DE_PASSE_ALICE, 4);
        p = insererCourse(NOM_P, "2026-10-09", "EN_PREPARATION", null);
        c = insererCourse("Course en cours", "2026-10-09", "EN_COURS", Instant.parse("2026-10-09T13:30:00Z"));
        t = insererCourse("Course terminee", "2026-10-08", "TERMINEE", Instant.parse("2026-10-08T08:00:00Z"));
        for (UUID course : List.of(p, c, t)) {
            affecter(course, idLeo);
        }
        affecter(p, idMarc);
        inscriptionAlice = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, 1, ?, 'EN_COURSE')", inscriptionAlice, c, idAlice, UUID.randomUUID().toString());
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscriptionAlice,
                Timestamp.from(Instant.parse("2026-10-09T13:50:00Z")));
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        journal = JournalApplicatif.demarrer().niveau("fr.backyard.tracker", Level.DEBUG);
    }

    @AfterEach
    void nettoyer() {
        journal.fermer();
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : Nadia désactive Léo : 204 sans corps, colonne actif fausse, autres colonnes inchangées, affectation à P retirée, C et T, Passage SCAN et affectation de Marc conservés, Léo reste en tête de la liste avec actif faux")
    void ca8_desactivation_nominale_d_un_benevole() throws Exception {
        Map<String, Object> avant = jdbc.queryForMap("select pseudo, pseudo_normalise, empreinte_mot_de_passe, role, "
                + "cree_le from compte where id = ?", idLeo);
        List<Map<String, Object>> passagesAvant = jdbc.queryForList("select * from passage order by id");

        HttpResponse<String> reponse = desactiver(nadia, "benevoles", idLeo);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        assertThat(actif(idLeo)).isFalse();
        assertThat(jdbc.queryForMap("select pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le "
                + "from compte where id = ?", idLeo)).isEqualTo(avant);
        assertThat(affectationsDe(p)).containsExactly(idMarc);
        assertThat(affectationsDe(c)).containsExactly(idLeo);
        assertThat(affectationsDe(t)).containsExactly(idLeo);
        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(jdbc.queryForObject("select origine from passage", String.class)).isEqualTo("SCAN");
        assertThat(actif(idMarc)).isTrue();
        JsonNode liste = json.readTree(lire(nadia, BENEVOLES).body());
        assertThat(liste.get(0).get("pseudo").asString()).isEqualTo("Léo");
        assertThat(liste.get(0).get("actif").asBoolean()).isFalse();
        assertThat(liste.get(1).get("actif").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : Patron désactive puis réactive Paul (204), actif visible dans la liste ; Nadia reste active ; actif présent et vrai dans les créations, la connexion et /api/comptes/moi")
    void ca9_desactivation_et_reactivation_d_un_admin_et_champ_actif() throws Exception {
        assertThat(desactiver(patron, "admins", idPaul).statusCode()).isEqualTo(204);

        JsonNode admins = json.readTree(lire(patron, ADMINS).body());
        assertThat(trouver(admins, "Paul").get("actif").asBoolean()).isFalse();
        assertThat(trouver(admins, "Nadia").get("actif").asBoolean()).isTrue();
        assertThat(trouver(admins, "Paul").propertyNames())
                .containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe", "actif");

        HttpResponse<String> reactivation = reactiver(patron, "admins", idPaul);
        assertThat(reactivation.statusCode()).isEqualTo(204);
        assertThat(reactivation.body()).isEmpty();
        assertThat(trouver(json.readTree(lire(patron, ADMINS).body()), "Paul").get("actif").asBoolean()).isTrue();
        assertThat(actif(idPaul)).isTrue();

        JsonNode benevoles = json.readTree(lire(nadia, BENEVOLES).body());
        assertThat(benevoles).allSatisfy(b -> assertThat(b.get("actif").asBoolean()).isTrue());
        HttpResponse<String> admin = api.requete("POST", ADMINS, patron.entetes(), "application/json",
                "{\"pseudo\":\"Zoe\",\"motDePasse\":\"mot-de-passe-zoe-12\"}");
        assertThat(admin.statusCode()).isEqualTo(201);
        assertThat(json.readTree(admin.body()).get("actif").asBoolean()).isTrue();
        HttpResponse<String> benevole = api.requete("POST", BENEVOLES, nadia.entetes(), "application/json",
                "{\"pseudo\":\"Yann\",\"motDePasse\":\"mot-de-passe-yann-1\"}");
        assertThat(benevole.statusCode()).isEqualTo(201);
        assertThat(json.readTree(benevole.body()).get("actif").asBoolean()).isTrue();
        assertThat(json.readTree(lire(nadia, "/api/comptes/moi").body()).get("actif").asBoolean()).isTrue();
        HttpResponse<String> connexion = connexion("Nadia", MOT_DE_PASSE_ADMIN);
        assertThat(connexion.statusCode()).isEqualTo(200);
        assertThat(json.readTree(connexion.body()).get("actif").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : admin master non désactivable (409), dernier admin actif (409), réactivation de l'admin master sans effet (204), aucun pseudo ni identifiant dans le detail ; réactiver Paul puis désactiver Nadia réussit")
    void ca10_refus_metier_de_desactivation() throws Exception {
        HttpResponse<String> master = desactiver(patron, "admins", idPatron);
        assertProbleme(master, 409, "Conflit", "ADMIN_MASTER_NON_DESACTIVABLE",
                "L'administrateur principal ne peut pas être désactivé.");
        assertThat(json.readTree(master.body()).get("type").asString()).isEqualTo("about:blank");

        assertThat(desactiver(patron, "admins", idPaul).statusCode()).isEqualTo(204);
        HttpResponse<String> dernier = desactiver(patron, "admins", idNadia);
        assertProbleme(dernier, 409, "Conflit", "DERNIER_ADMIN_ACTIF",
                "Impossible de désactiver le dernier administrateur actif.");
        for (HttpResponse<String> refus : List.of(master, dernier)) {
            String detail = json.readTree(refus.body()).get("detail").asString();
            assertThat(detail).doesNotContain("Patron").doesNotContain("Nadia").doesNotContain("Paul")
                    .doesNotContain(idPatron.toString()).doesNotContain(idNadia.toString());
        }
        assertThat(actif(idPatron)).isTrue();
        assertThat(actif(idNadia)).isTrue();
        assertThat(connexion("Nadia", MOT_DE_PASSE_ADMIN).statusCode()).isEqualTo(200);

        Object xminAvant = jdbc.queryForObject("select xmin::text from compte where id = ?", String.class, idPatron);
        assertThat(reactiver(patron, "admins", idPatron).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select xmin::text from compte where id = ?", String.class, idPatron))
                .isEqualTo(xminAvant);
        assertThat(actif(idPatron)).isTrue();

        assertThat(reactiver(patron, "admins", idPaul).statusCode()).isEqualTo(204);
        assertThat(desactiver(patron, "admins", idNadia).statusCode()).isEqualTo(204);
        assertThat(actif(idNadia)).isFalse();
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : matrice de rôles : anonyme 401, ADMIN 403 sur admins (même réponse pour un id inconnu) et 204 sur bénévoles, master 204 partout, BENEVOLE et COUREUR 403 sur les quatre routes")
    void ca11_matrice_des_roles() throws Exception {
        Session leo = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session alice = api.ouvrir("Alice", MOT_DE_PASSE_ALICE);
        List<Map<String, Object>> avant = etatDesComptes();

        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);
        for (String chemin : quatreRoutes(idPaul, idMarc)) {
            assertProbleme(api.requete("POST", chemin, anonyme, null, null), 401, "Authentification requise",
                    "NON_AUTHENTIFIE", "Vous devez être connecté.");
        }
        for (Session interdit : List.of(leo, alice)) {
            for (String chemin : quatreRoutes(idPaul, idMarc)) {
                assertProbleme(api.requete("POST", chemin, interdit.entetes(), null, null), 403, "Accès refusé",
                        "ACCES_REFUSE", ACCES_REFUSE_DETAIL);
            }
        }
        HttpResponse<String> connu = desactiver(nadia, "admins", idPaul);
        HttpResponse<String> inconnu = desactiver(nadia, "admins", UUID.fromString(ID_INCONNU));
        assertProbleme(connu, 403, "Accès refusé", "ACCES_REFUSE", ACCES_REFUSE_DETAIL);
        assertThat(sansInstance(inconnu)).isEqualTo(sansInstance(connu));
        assertProbleme(reactiver(nadia, "admins", idPaul), 403, "Accès refusé", "ACCES_REFUSE", ACCES_REFUSE_DETAIL);
        assertThat(etatDesComptes()).isEqualTo(avant);

        assertThat(desactiver(nadia, "benevoles", idMarc).statusCode()).isEqualTo(204);
        assertThat(actif(idMarc)).isFalse();
        assertThat(reactiver(nadia, "benevoles", idMarc).statusCode()).isEqualTo(204);
        assertThat(actif(idMarc)).isTrue();

        assertThat(desactiver(patron, "admins", idPaul).statusCode()).isEqualTo(204);
        assertThat(actif(idPaul)).isFalse();
        assertThat(reactiver(patron, "admins", idPaul).statusCode()).isEqualTo(204);
        assertThat(actif(idPaul)).isTrue();
        assertThat(desactiver(patron, "benevoles", idMarc).statusCode()).isEqualTo(204);
        assertThat(reactiver(patron, "benevoles", idMarc).statusCode()).isEqualTo(204);
        assertThat(actif(idMarc)).isTrue();
    }

    @Test
    @DisplayName("CA11 : sans jeton CSRF ou avec un jeton différent, 403 CSRF_INVALIDE sur les quatre routes et base inchangée")
    void ca11_csrf_absent_ou_invalide() throws Exception {
        List<Map<String, Object>> avant = etatDesComptes();
        for (Session session : List.of(patron, nadia)) {
            for (String chemin : quatreRoutes(idPaul, idMarc)) {
                HttpResponse<String> sansEntete = api.requete("POST", chemin,
                        Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf()), null, null);
                assertProbleme(sansEntete, 403, "Accès refusé", "CSRF_INVALIDE", CSRF_DETAIL);
                HttpResponse<String> different = api.requete("POST", chemin,
                        Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf(),
                                "X-XSRF-TOKEN", "autre-valeur"), null, null);
                assertProbleme(different, 403, "Accès refusé", "CSRF_INVALIDE", CSRF_DETAIL);
            }
        }
        assertThat(etatDesComptes()).isEqualTo(avant);
        assertThat(affectationsDe(p)).containsExactlyInAnyOrder(idLeo, idMarc);
    }

    @Test
    @DisplayName("CA11 : identifiant inconnu, mal formé ou de mauvais rôle : 404 COMPTE_INTROUVABLE de corps identique, rien n'est modifié")
    void ca11_cibles_introuvables() throws Exception {
        List<Map<String, Object>> avant = etatDesComptes();
        List<String> cibles = List.of(ID_INCONNU, "not-un-uuid", idAlice.toString(), idNadia.toString(),
                idPatron.toString());
        for (String action : List.of("desactivation", "reactivation")) {
            JsonNode reference = null;
            for (String cible : cibles) {
                HttpResponse<String> reponse = api.requete("POST", BENEVOLES + "/" + cible + "/" + action,
                        nadia.entetes(), null, null);
                assertProbleme(reponse, 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
                JsonNode corps = sansInstance(reponse);
                reference = reference == null ? corps : reference;
                assertThat(corps).isEqualTo(reference);
                assertThat(json.readTree(reponse.body()).get("detail").asString()).doesNotContain(cible);
            }
            for (String cible : List.of(ID_INCONNU, "not-un-uuid", idAlice.toString(), idLeo.toString(),
                    idMarc.toString())) {
                HttpResponse<String> reponse = api.requete("POST", ADMINS + "/" + cible + "/" + action,
                        patron.entetes(), null, null);
                assertProbleme(reponse, 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
                assertThat(sansInstance(reponse)).isEqualTo(reference);
            }
        }
        assertThat(etatDesComptes()).isEqualTo(avant);
        assertThat(affectationsDe(p)).containsExactlyInAnyOrder(idLeo, idMarc);
    }

    @Test
    @DisplayName("CA11 : GET, PUT, PATCH et DELETE sur les quatre chemins : 404 ou 405, jamais 2xx, aucune modification")
    void ca11_autres_methodes() throws Exception {
        List<Map<String, Object>> avant = etatDesComptes();
        for (String chemin : quatreRoutes(idPaul, idLeo)) {
            for (String methode : List.of("GET", "PUT", "PATCH", "DELETE")) {
                HttpResponse<String> reponse = api.requete(methode, chemin, patron.entetes(), null, null);
                assertThat(reponse.statusCode()).as(methode + " " + chemin).isIn(404, 405);
            }
        }
        assertThat(etatDesComptes()).isEqualTo(avant);
        assertThat(affectationsDe(p)).containsExactlyInAnyOrder(idLeo, idMarc);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : un Compte désactivé obtient le même 401 IDENTIFIANTS_INVALIDES qu'un pseudo inconnu ou qu'un mauvais mot de passe, même avec le bon mot de passe ; le blocage 429 est identique à celui d'un pseudo quelconque ; journal sans pseudo ; la réactivation rétablit la connexion")
    void ca12_connexion_d_un_compte_desactive() throws Exception {
        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);
        registre.vider();

        HttpResponse<String> desactive = connexion("Léo", MOT_DE_PASSE_BENEVOLE);
        HttpResponse<String> inconnu = connexion("Inconnu", MOT_DE_PASSE_BENEVOLE);
        HttpResponse<String> mauvais = connexion("Marc", "mauvais-mot-de-passe-1");
        assertProbleme(desactive, 401, "Authentification échouée", "IDENTIFIANTS_INVALIDES",
                "Pseudo ou mot de passe incorrect.");
        for (HttpResponse<String> autre : List.of(inconnu, mauvais)) {
            assertThat(autre.statusCode()).isEqualTo(401);
            assertThat(autre.body()).isEqualTo(desactive.body());
        }
        assertThat(ApiHttpHelpers.cookiesDeSession(desactive)).isEmpty();

        for (int i = 0; i < 4; i++) {
            assertThat(connexion("Léo", MOT_DE_PASSE_BENEVOLE).statusCode()).as("essai " + (i + 2)).isEqualTo(401);
        }
        HttpResponse<String> bloque = connexion("Léo", MOT_DE_PASSE_BENEVOLE);
        for (int i = 0; i < 5; i++) {
            assertThat(connexion("Quelconque", MOT_DE_PASSE_BENEVOLE).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> bloqueQuelconque = connexion("Quelconque", MOT_DE_PASSE_BENEVOLE);
        assertThat(bloque.statusCode()).isEqualTo(429);
        assertThat(bloque.headers().firstValue("retry-after")).hasValue("900");
        assertThat(bloqueQuelconque.statusCode()).isEqualTo(429);
        assertThat(bloqueQuelconque.headers().firstValue("retry-after")).hasValue("900");
        assertThat(json.readTree(bloque.body()).get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");
        assertThat(sansInstance(bloque)).isEqualTo(sansInstance(bloqueQuelconque));

        List<ILoggingEvent> applicatifs = journal.evenementsApplicatifs();
        assertThat(applicatifs).anyMatch(e -> e.getFormattedMessage().equals("Échec de connexion"));
        assertThat(applicatifs).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .noneMatch(e -> e.getFormattedMessage().contains("Léo")
                        || e.getFormattedMessage().contains(MOT_DE_PASSE_BENEVOLE));

        assertThat(reactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);
        registre.vider();
        HttpResponse<String> reussie = connexion("Léo", MOT_DE_PASSE_BENEVOLE);
        assertThat(reussie.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reussie.body()).get("actif").asBoolean()).isTrue();
        assertThat(connexion("Marc", MOT_DE_PASSE_BENEVOLE).statusCode()).isEqualTo(200);
        assertThat(connexion("Nadia", MOT_DE_PASSE_ADMIN).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : les deux sessions de Léo sont fermées (401 NON_AUTHENTIFIE), celles de Marc et de Nadia restent valides ; la fermeture a lieu après validation (Compte déjà lu inactif)")
    void ca13_fermeture_de_toutes_les_sessions() throws Exception {
        Session s1 = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session s2 = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session s3 = api.ouvrir("Marc", MOT_DE_PASSE_BENEVOLE);
        assertThat(lire(s1, "/api/benevole/courses").statusCode()).isEqualTo(200);
        AtomicReference<Boolean> actifALaFermeture = new AtomicReference<>();
        doAnswer(invocation -> {
            actifALaFermeture.set(jdbc.queryForObject("select actif from compte where id = ?", Boolean.class, idLeo));
            return invocation.callRealMethod();
        }).when(registreSessions).invaliderToutesLesSessions(idLeo);

        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);

        assertThat(actifALaFermeture.get()).isFalse();
        for (Session fermee : List.of(s1, s2)) {
            assertProbleme(lire(fermee, "/api/benevole/courses"), 401, "Authentification requise", "NON_AUTHENTIFIE",
                    "Vous devez être connecté.");
            assertThat(lire(fermee, "/api/comptes/moi").statusCode()).isEqualTo(401);
        }
        assertThat(lire(s3, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA13 : une désactivation annulée par une panne laisse les sessions valides, le Compte actif et les affectations intactes (500 ERREUR_INTERNE)")
    void ca13_panne_pendant_la_desactivation() throws Exception {
        Session s1 = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotComptes).mettreAJour(any());

        HttpResponse<String> reponse = desactiver(nadia, "benevoles", idLeo);

        assertThat(reponse.statusCode()).isEqualTo(500);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("secret-technique");
        assertThat(actif(idLeo)).isTrue();
        assertThat(affectationsDe(p)).containsExactlyInAnyOrder(idLeo, idMarc);
        assertThat(lire(s1, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lire(s1, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA13 : une connexion finalisée pendant la désactivation n'aboutit à aucune session valide : 401 IDENTIFIANTS_INVALIDES")
    void ca13_connexion_concurrente_d_une_desactivation() throws Exception {
        doAnswer(invocation -> {
            // Connexion JDBC distincte : la lecture espionnée s'exécute dans une transaction en lecture seule.
            horsTransaction().update("update compte set actif = false where id = ?", idLeo);
            return invocation.callRealMethod();
        }).when(consulterCompteConnecte).estConnectable(idLeo);

        HttpResponse<String> reponse = connexion("Léo", MOT_DE_PASSE_BENEVOLE);

        assertProbleme(reponse, 401, "Authentification échouée", "IDENTIFIANTS_INVALIDES",
                "Pseudo ou mot de passe incorrect.");
        for (String identifiant : ApiHttpHelpers.cookiesDeSession(reponse)) {
            assertThat(lire(new Session(identifiant, "x"), "/api/comptes/moi").statusCode()).isEqualTo(401);
            assertThat(lire(new Session(identifiant, "x"), "/api/benevole/courses").statusCode()).isEqualTo(401);
        }
        assertThat(actif(idLeo)).isFalse();
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : Léo affecté à deux Courses EN_PREPARATION et à une EN_COURS : seules les premières sont retirées, la fiche de la Course démarrée liste encore Léo")
    void ca14_desaffectation_des_courses_en_preparation_seulement() throws Exception {
        UUID p2 = insererCourse("Deuxieme preparation", "2026-11-01", "EN_PREPARATION", null);
        affecter(p2, idLeo);
        affecter(p2, idMarc);

        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForList("select course_id from affectation_benevole where benevole_id = ?", UUID.class,
                idLeo)).containsExactlyInAnyOrder(c, t);
        assertThat(affectationsDe(p)).containsExactly(idMarc);
        assertThat(affectationsDe(p2)).containsExactly(idMarc);
        JsonNode fiche = json.readTree(lire(nadia, COURSES + "/" + c).body());
        assertThat(textes(fiche.get("benevoleIds"))).contains(idLeo.toString());
    }

    @Test
    @DisplayName("CA14 : une Course supprimée pendant la désactivation ne provoque jamais de 500")
    void ca14_suppression_de_course_concurrente() throws Exception {
        for (int i = 0; i < 10; i++) {
            UUID p3 = insererCourse("Troisieme " + i, "2026-12-01", "EN_PREPARATION", null);
            affecter(p3, idLeo);
            CountDownLatch depart = new CountDownLatch(1);
            Callable<HttpResponse<String>> desactivation = () -> {
                depart.await();
                return desactiver(nadia, "benevoles", idLeo);
            };
            Callable<HttpResponse<String>> suppression = () -> {
                depart.await();
                return api.requete("DELETE", COURSES + "/" + p3, patron.entetes(), null, null);
            };
            List<HttpResponse<String>> reponses = enParallele(depart, desactivation, suppression);

            assertThat(reponses.get(0).statusCode()).as("désactivation " + i).isEqualTo(204);
            assertThat(reponses.get(1).statusCode()).as("suppression " + i).isEqualTo(204);
            assertThat(jdbc.queryForList("select 1 from affectation_benevole where course_id = ?", p3)).isEmpty();
            reinitialiserLeo();
        }
    }

    @Test
    @DisplayName("CA14 : le démarrage simultané de la Course est sérialisé avec la désactivation : jamais de 500, Course EN_COURS, Léo désactivé")
    void ca14_demarrage_concurrent() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID p1 = insererCourse("Demarrage " + i, "2026-10-09", "EN_PREPARATION", null);
            affecter(p1, idLeo);
            jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                    + "values (?, ?, ?, 1, ?, 'EN_COURSE')", UUID.randomUUID(), p1, idAlice,
                    UUID.randomUUID().toString());
            CountDownLatch depart = new CountDownLatch(1);
            Callable<HttpResponse<String>> desactivation = () -> {
                depart.await();
                return desactiver(nadia, "benevoles", idLeo);
            };
            Callable<HttpResponse<String>> demarrage = () -> {
                depart.await();
                return api.requete("POST", COURSES + "/" + p1 + "/demarrage", nadia.entetes(), null, null);
            };
            List<HttpResponse<String>> reponses = enParallele(depart, desactivation, demarrage);

            assertThat(reponses.get(0).statusCode()).as("désactivation " + i).isEqualTo(204);
            assertThat(reponses.get(1).statusCode()).as("démarrage " + i).isEqualTo(200);
            assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, p1))
                    .isEqualTo("EN_COURS");
            assertThat(actif(idLeo)).isFalse();
            reinitialiserLeo();
        }
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : un bénévole désactivé ne peut pas être ajouté à une Course (409 BENEVOLE_DESACTIVE), peut rester ou être retiré d'une Course où il est déjà affecté, et peut être ajouté de nouveau après réactivation")
    void ca15_affectation_d_un_benevole_desactive() throws Exception {
        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);

        HttpResponse<String> refus = affecterVia(nadia, p, idLeo, idMarc);
        assertProbleme(refus, 409, "Conflit", "BENEVOLE_DESACTIVE", "Un des bénévoles choisis est désactivé.");
        assertThat(refus.body()).doesNotContain(idLeo.toString()).doesNotContain("Léo");
        assertThat(affectationsDe(p)).containsExactly(idMarc);
        assertThat(affecterVia(nadia, p, idMarc).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(p)).containsExactly(idMarc);

        assertThat(affecterVia(nadia, c, idLeo, idMarc).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(c)).containsExactlyInAnyOrder(idLeo, idMarc);
        assertThat(affecterVia(nadia, c, idMarc).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(c)).containsExactly(idMarc);

        HttpResponse<String> inconnu = affecterVia(nadia, p, UUID.fromString(ID_INCONNU), idLeo);
        assertThat(inconnu.statusCode()).isEqualTo(400);
        assertThat(json.readTree(inconnu.body()).get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
        assertThat(inconnu.body()).contains("BENEVOLE_INCONNU");

        HttpResponse<String> terminee = affecterVia(nadia, t, idMarc);
        assertThat(json.readTree(terminee.body()).get("code").asString()).isEqualTo("COURSE_TERMINEE");
        assertThat(terminee.statusCode()).isEqualTo(409);

        assertThat(reactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);
        assertThat(affecterVia(nadia, p, idLeo, idMarc).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(p)).containsExactlyInAnyOrder(idLeo, idMarc);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : deux désactivations simultanées des deux derniers admins actifs : exactement un 204 et un 409 DERNIER_ADMIN_ACTIF, jamais deux 204 ni de 500, un seul admin actif en base (20 répétitions)")
    void ca16_derniers_admins_actifs_simultanes() throws Exception {
        for (int i = 0; i < 20; i++) {
            CountDownLatch depart = new CountDownLatch(1);
            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return desactiver(patron, "admins", idNadia);
                    },
                    () -> {
                        depart.await();
                        return desactiver(patron, "admins", idPaul);
                    });

            List<Integer> statuts = reponses.stream().map(HttpResponse::statusCode).sorted().toList();
            assertThat(statuts).as("répétition " + i).containsExactly(204, 409);
            HttpResponse<String> refus = reponses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
            assertThat(json.readTree(refus.body()).get("code").asString()).isEqualTo("DERNIER_ADMIN_ACTIF");
            assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN' and actif", Integer.class))
                    .isEqualTo(1);
            jdbc.update("update compte set actif = true where role = 'ADMIN'");
        }
    }

    @Test
    @DisplayName("CA16 : deux désactivations simultanées de Léo : deux 204 et une seule ligne de journal « Compte désactivé »")
    void ca16_desactivations_simultanees_de_la_meme_cible() throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        Callable<HttpResponse<String>> action = () -> {
            depart.await();
            return desactiver(nadia, "benevoles", idLeo);
        };

        List<HttpResponse<String>> reponses = enParallele(depart, action, action);

        assertThat(reponses).extracting(HttpResponse::statusCode).containsExactly(204, 204);
        assertThat(actif(idLeo)).isFalse();
        assertThat(lignes("Compte désactivé")).hasSize(1);
    }

    @Test
    @DisplayName("CA16 : désactivation et réactivation simultanées de Léo : deux 204, état final cohérent, affectation à P retirée dans les deux ordres, jamais de 500")
    void ca16_desactivation_et_reactivation_simultanees() throws Exception {
        for (int i = 0; i < 10; i++) {
            CountDownLatch depart = new CountDownLatch(1);
            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return desactiver(nadia, "benevoles", idLeo);
                    },
                    () -> {
                        depart.await();
                        return reactiver(nadia, "benevoles", idLeo);
                    });

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode).containsExactly(204, 204);
            assertThat(affectationsDe(p)).as("répétition " + i).containsExactly(idMarc);
            reinitialiserLeo();
        }
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : désactivation puis réactivation effectives journalisées avec identifiants seuls ; répétées, elles sont des no-op : 204, aucune écriture, aucune ligne de journal, aucune session fermée")
    void ca17_idempotence_et_journal() throws Exception {
        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);
        assertThat(lignes("Compte désactivé")).containsExactly("Compte désactivé (compte " + idLeo + " par " + idNadia
                + ", 1 affectation(s) retirée(s))");

        assertThat(reactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);
        assertThat(lignes("Compte réactivé")).containsExactly("Compte réactivé (compte " + idLeo + " par " + idNadia
                + ")");

        Session sessionLeo = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        jdbc.update("update compte set actif = false where id = ?", idLeo);
        String xminLeo = xmin(idLeo);
        Session sessionMarc = api.ouvrir("Marc", MOT_DE_PASSE_BENEVOLE);
        String xminMarc = xmin(idMarc);
        int marque = journal.marque();

        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);
        assertThat(reactiver(nadia, "benevoles", idMarc).statusCode()).isEqualTo(204);

        assertThat(xmin(idLeo)).isEqualTo(xminLeo);
        assertThat(xmin(idMarc)).isEqualTo(xminMarc);
        assertThat(journal.applicatifsDepuis(marque)).filteredOn(e -> e.getLevel() == Level.INFO)
                .noneMatch(e -> e.getFormattedMessage().startsWith("Compte désactivé")
                        || e.getFormattedMessage().startsWith("Compte réactivé"));
        assertThat(lire(sessionLeo, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lire(sessionMarc, "/api/benevole/courses").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA17 : un refus métier produit « Désactivation refusée (compte <id>, motif DERNIER_ADMIN_ACTIF) » ; aucun journal INFO+ ne contient pseudo, mot de passe ni nom de Course")
    void ca17_journal_du_refus_sans_donnee_sensible() throws Exception {
        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);
        assertThat(desactiver(patron, "admins", idPaul).statusCode()).isEqualTo(204);
        assertProbleme(desactiver(patron, "admins", idNadia), 409, "Conflit", "DERNIER_ADMIN_ACTIF",
                "Impossible de désactiver le dernier administrateur actif.");
        assertThat(reactiver(patron, "admins", idPaul).statusCode()).isEqualTo(204);
        connexion("Léo", MOT_DE_PASSE_BENEVOLE);

        assertThat(lignes("Désactivation refusée"))
                .containsExactly("Désactivation refusée (compte " + idNadia + ", motif DERNIER_ADMIN_ACTIF)");
        assertThat(lignes("Compte réactivé")).containsExactly("Compte réactivé (compte " + idPaul + " par " + idPatron
                + ")");
        for (ILoggingEvent evenement : journal.evenements()) {
            String texte = evenement.getFormattedMessage() + " " + evenement.getMDCPropertyMap()
                    + (evenement.getThrowableProxy() == null ? "" : evenement.getThrowableProxy().getMessage());
            assertThat(texte).as(evenement.getLevel() + " " + evenement.getLoggerName())
                    .doesNotContain(MOT_DE_PASSE_BENEVOLE).doesNotContain(MOT_DE_PASSE_ADMIN)
                    .doesNotContain(MOT_DE_PASSE_PATRON);
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)
                    && evenement.getLoggerName().startsWith("fr.backyard.tracker")) {
                assertThat(texte).as("INFO+ " + evenement.getLoggerName()).doesNotContain("Léo").doesNotContain("Marc")
                        .doesNotContain("Nadia").doesNotContain("Paul").doesNotContain("Alice")
                        .doesNotContain(NOM_P);
            }
        }
    }

    @Test
    @DisplayName("CA17 : le pseudo d'un Compte désactivé reste réservé (409 PSEUDO_DEJA_UTILISE à la création)")
    void ca17_pseudo_reserve() throws Exception {
        assertThat(desactiver(nadia, "benevoles", idLeo).statusCode()).isEqualTo(204);

        HttpResponse<String> reponse = api.requete("POST", BENEVOLES, nadia.entetes(), "application/json",
                "{\"pseudo\":\"Léo\",\"motDePasse\":\"mot-de-passe-autre-1\"}");

        assertThat(reponse.statusCode()).isEqualTo(409);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("PSEUDO_DEJA_UTILISE");
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : changeset 0011-compte-actif appliqué (0002 à 0011, dans l'ordre), colonne actif booléenne non nulle à true par défaut, tous les Comptes existants actifs, ddl-auto=validate")
    void ca18_changeset_0011() {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course", "0009-passage",
                        "0010-abandon-inscription", "0011-compte-actif");
        Map<String, Object> colonne = jdbc.queryForMap("select data_type, is_nullable, column_default "
                + "from information_schema.columns where table_name = 'compte' and column_name = 'actif'");
        assertThat(colonne.get("data_type")).isEqualTo("boolean");
        assertThat(colonne.get("is_nullable")).isEqualTo("NO");
        assertThat(colonne.get("column_default").toString()).isEqualTo("true");
        assertThat(jdbc.queryForObject("select count(*) from compte where not actif", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA18 : le référentiel d'audit contient la ligne SEC-13")
    void ca18_referentiel_sec_13() throws Exception {
        String referentiel = Files.readString(Path.of("..", "docs", "audits", "referentiel.md"));

        assertThat(referentiel).contains("| SEC-13 |").contains("| P1 | 8.1 |");
    }

    // ---------------------------------------------------------------- utilitaires

    /** Aides sur les réponses HTTP. */
    static final class ApiHttpHelpers {
        private ApiHttpHelpers() {
        }

        /** Valeurs non vides des cookies JSESSIONID posés par la réponse. */
        static List<String> cookiesDeSession(HttpResponse<?> reponse) {
            return reponse.headers().allValues("set-cookie").stream().filter(cookie -> cookie.startsWith("JSESSIONID="))
                    .map(cookie -> cookie.substring("JSESSIONID=".length(),
                            cookie.indexOf(';') < 0 ? cookie.length() : cookie.indexOf(';')))
                    .filter(valeur -> !valeur.isEmpty()).toList();
        }
    }

    private JdbcTemplate horsTransaction() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private UUID insererCompte(String pseudo, String role, String motDePasse, int minutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(motDePasse), role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z").plusSeconds(60L * minutes)));
        return id;
    }

    private UUID insererCourse(String nom, String date, String statut, Instant demarreeLe) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(date), statut,
                demarreeLe == null ? null : Timestamp.from(demarreeLe));
        return id;
    }

    private void affecter(UUID course, UUID benevole) {
        jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", course, benevole);
    }

    private List<UUID> affectationsDe(UUID course) {
        return jdbc.queryForList("select benevole_id from affectation_benevole where course_id = ?", UUID.class,
                course);
    }

    private boolean actif(UUID compte) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select actif from compte where id = ?", Boolean.class, compte));
    }

    private String xmin(UUID compte) {
        return jdbc.queryForObject("select xmin::text from compte where id = ?", String.class, compte);
    }

    private List<Map<String, Object>> etatDesComptes() {
        return jdbc.queryForList("select id, actif from compte order by id");
    }

    /** Léo redevient actif et affecté à P uniquement comme au départ, pour répéter une course de threads. */
    private void reinitialiserLeo() {
        jdbc.update("update compte set actif = true where id = ?", idLeo);
        jdbc.update("delete from affectation_benevole where benevole_id = ? and course_id not in (?, ?)", idLeo, c, t);
        jdbc.update("delete from affectation_benevole where benevole_id = ? and course_id = ?", idLeo, p);
        affecter(p, idLeo);
    }

    private HttpResponse<String> desactiver(Session session, String route, UUID id) throws Exception {
        return api.requete("POST", "/api/administration/" + route + "/" + id + "/desactivation", session.entetes(),
                null, null);
    }

    private HttpResponse<String> reactiver(Session session, String route, UUID id) throws Exception {
        return api.requete("POST", "/api/administration/" + route + "/" + id + "/reactivation", session.entetes(),
                null, null);
    }

    private List<String> quatreRoutes(UUID idAdmin, UUID idBenevole) {
        return List.of(ADMINS + "/" + idAdmin + "/desactivation", ADMINS + "/" + idAdmin + "/reactivation",
                BENEVOLES + "/" + idBenevole + "/desactivation", BENEVOLES + "/" + idBenevole + "/reactivation");
    }

    private HttpResponse<String> lire(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, session.enteteLecture(), null, null);
    }

    private HttpResponse<String> connexion(String pseudo, String motDePasse) throws Exception {
        String jeton = api.jetonValide();
        return api.requete("POST", "/api/connexion", Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton),
                "application/json", "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}");
    }

    private HttpResponse<String> affecterVia(Session session, UUID course, UUID... benevoles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        var tableau = corps.putArray("benevoleIds");
        for (UUID benevole : benevoles) {
            tableau.add(benevole.toString());
        }
        return api.requete("PUT", COURSES + "/" + course + "/benevoles", session.entetes(), "application/json",
                corps.toString());
    }

    private JsonNode trouver(JsonNode liste, String pseudo) {
        for (JsonNode compte : liste) {
            if (pseudo.equals(compte.get("pseudo").asString())) {
                return compte;
            }
        }
        throw new AssertionError("Aucun compte " + pseudo + " dans " + liste);
    }

    private List<String> textes(JsonNode tableau) {
        List<String> valeurs = new ArrayList<>();
        tableau.forEach(valeur -> valeurs.add(valeur.asString()));
        return valeurs;
    }

    /** Messages INFO+ de l'application commençant par le préfixe. */
    private List<String> lignes(String prefixe) {
        return journal.evenementsApplicatifs().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefixe)).toList();
    }

    private JsonNode sansInstance(HttpResponse<String> reponse) {
        ObjectNode corps = (ObjectNode) json.readTree(reponse.body());
        corps.remove("instance");
        return corps;
    }

    @SafeVarargs
    private final List<HttpResponse<String>> enParallele(CountDownLatch depart,
                                                         Callable<HttpResponse<String>>... actions) throws Exception {
        ExecutorService executeur = Executors.newFixedThreadPool(actions.length);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> action : actions) {
                futures.add(executeur.submit(action));
            }
            Thread.sleep(150);
            depart.countDown();
            List<HttpResponse<String>> reponses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                reponses.add(future.get());
            }
            return reponses;
        } finally {
            executeur.shutdownNow();
        }
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
