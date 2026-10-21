package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.OctetsDeLogo;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * Contrat de l'incrément 6.4 (accueil public) : CA5 à CA10, serveur réel. GET /api/courses est public, renvoie
 * trois rubriques (enCours, aVenir, terminees) de 4 champs (id, nom, date, logoUrl), triées (RG1 à RG7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class CoursesPubliquesIntegrationTest {

    static final List<String> CHAMPS = List.of("id", "nom", "date", "logoUrl");
    static final String CHEMIN = "/api/courses";

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

    DecorAbandon decor;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : A à G en base, sans session : 200, no-store, aucun Set-Cookie, enCours [E, D], aVenir [B, C, A], terminees [G, F], 4 champs exactement par Course")
    void ca5_liste_publique_regroupee_et_triee() throws Exception {
        Map<String, UUID> c = semer();

        HttpResponse<String> reponse = lister(Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                v -> assertThat(v).contains("no-store"));
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                v -> assertThat(v).contains("application/json"));
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("enCours", "aVenir", "terminees");
        assertThat(ids(corps.get("enCours"))).containsExactly(c.get("E").toString(), c.get("D").toString());
        assertThat(ids(corps.get("aVenir"))).containsExactly(c.get("B").toString(), c.get("C").toString(), c.get("A").toString());
        assertThat(ids(corps.get("terminees"))).containsExactly(c.get("G").toString(), c.get("F").toString());
        List<String> tous = new ArrayList<>();
        for (String rubrique : List.of("enCours", "aVenir", "terminees")) {
            for (JsonNode element : corps.get(rubrique)) {
                assertThat(element.propertyNames()).hasSize(4).containsExactlyInAnyOrderElementsOf(CHAMPS);
                assertThat(element.get("date").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
                tous.add(element.get("id").asString());
            }
        }
        assertThat(tous).hasSize(7).doesNotHaveDuplicates();
        JsonNode e = corps.get("enCours").get(0);
        assertThat(e.get("nom").asString()).isEqualTo("Backyard Epsilon");
        assertThat(e.get("date").asString()).isEqualTo("2026-10-21");
        assertThat(corps.get("aVenir").get(0).get("nom").asString()).isEqualTo("backyard beta");
        assertThat(corps.get("aVenir").get(0).get("date").asString()).isEqualTo("2026-11-05");
    }

    @Test
    @DisplayName("CA5 : même date et même nom : départagées par identifiant, ordre identique d'un appel à l'autre")
    void ca5_ordre_deterministe_a_egalite_de_date_et_de_nom() throws Exception {
        UUID un = course("Backyard Twin", "2026-12-01", "EN_PREPARATION");
        UUID deux = course("Backyard Twin", "2026-12-01", "EN_PREPARATION");
        List<String> attendu = List.of(un.toString(), deux.toString()).stream().sorted().toList();

        List<String> premier = ids(corps().get("aVenir"));
        List<String> second = ids(corps().get("aVenir"));

        assertThat(premier).containsExactlyElementsOf(attendu);
        assertThat(second).isEqualTo(premier);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : logoUrl versionné pour la Course avec logo, null sans logo, aucune donnée sensible dans le corps")
    void ca6_logo_url_et_liste_fermee() throws Exception {
        UUID avec = course("Backyard avec logo", "2026-11-01", "EN_PREPARATION");
        UUID sans = course("Backyard sans logo", "2026-11-02", "EN_PREPARATION");
        poserLogo(avec);
        decor.insererInscription(avec, decor.idAlice, 1, "EN_COURSE");
        decor.jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", avec,
                decor.idLeo);

        HttpResponse<String> reponse = lister(Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode aVenir = decor.json.readTree(reponse.body()).get("aVenir");
        assertThat(aVenir.get(0).get("id").asString()).isEqualTo(avec.toString());
        assertThat(aVenir.get(0).get("logoUrl").asString()).startsWith("/api/courses/" + avec + "/logo?v=");
        assertThat(aVenir.get(1).get("id").asString()).isEqualTo(sans.toString());
        assertThat(aVenir.get(1).get("logoUrl").isNull()).isTrue();
        assertThat(reponse.body().toLowerCase()).doesNotContain("benevole", "inscri", "pseudo", "dossard", "jeton",
                "demarree", "distance", "nombremax", "alice", "statut");
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : base sans Course : 200 et trois tableaux vides")
    void ca7_base_sans_course() throws Exception {
        HttpResponse<String> reponse = lister(Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(decor.json.readTree(reponse.body())).isEqualTo(
                decor.json.readTree("{\"enCours\":[],\"aVenir\":[],\"terminees\":[]}"));
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : A à G, ADMIN, ADMIN_MASTER, BENEVOLE affecté, BENEVOLE non affecté, COUREUR et anonyme reçoivent le même corps 200, sans CSRF")
    void ca8_meme_corps_pour_tous_les_roles() throws Exception {
        semer();
        UUID affectee = course("Backyard affectée", "2026-11-30", "EN_PREPARATION");
        assertThat(decor.affecter(affectee.toString(), decor.idLeo).statusCode()).isEqualTo(200);
        decor.insererCompte("Marc", "BENEVOLE");
        Session marc = decor.api.ouvrir("Marc", DecorAbandon.MOT_DE_PASSE);
        HttpResponse<String> anonyme = lister(Map.of());
        assertThat(anonyme.statusCode()).isEqualTo(200);
        JsonNode attendu = decor.json.readTree(anonyme.body());

        for (Session session : List.of(decor.nadia, decor.patron, decor.leo, marc, decor.alice)) {
            HttpResponse<String> reponse = lister(session.enteteLecture());
            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(decor.json.readTree(reponse.body())).isEqualTo(attendu);
        }
        String jeton = decor.api.jetonValide();
        assertThat(lister(Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton)).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("CA8 : POST, PUT, DELETE sur /api/courses ne répondent jamais 2xx ; un POST sans CSRF donne 403 CSRF_INVALIDE")
    void ca8_autres_methodes_jamais_2xx() throws Exception {
        semer();
        long avant = decor.jdbc.queryForObject("select count(*) from course", Long.class);
        String jeton = decor.api.jetonValide();
        Map<String, String> anonymeAvecCsrf = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (String methode : List.of("POST", "PUT", "DELETE", "PATCH")) {
            List<Map<String, String>> identites = List.of(Map.of(), anonymeAvecCsrf, decor.alice.entetes(),
                    decor.alice.enteteLecture(), decor.nadia.entetes(), decor.patron.entetes());
            for (Map<String, String> entetes : identites) {
                int statut = decor.api.requete(methode, CHEMIN, entetes, "application/json", "{}").statusCode();
                assertThat(statut).as(methode + " " + entetes.keySet()).satisfies(s -> assertThat(s < 200 || s > 299).isTrue());
            }
        }
        HttpResponse<String> sansCsrf = decor.api.requete("POST", CHEMIN, Map.of(), "application/json", "{}");
        assertThat(sansCsrf.statusCode()).isEqualTo(403);
        assertThat(decor.json.readTree(sansCsrf.body()).get("code").asString()).isEqualTo("CSRF_INVALIDE");
        assertThat(decor.jdbc.queryForObject("select count(*) from course", Long.class)).isEqualTo(avant);
        assertThat(decor.api.requete("HEAD", CHEMIN, Map.of(), null, null).statusCode()).satisfies(s -> assertThat(s < 200 || s > 299).isTrue());
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : Course supprimée disparue de aVenir, Course démarrée passée de aVenir à enCours ; la lecture ne modifie rien en base")
    void ca9_suppression_et_demarrage_se_refletent_sans_ecriture() throws Exception {
        String supprimee = decor.creerCourse("Backyard supprimée", 1, 5);
        String demarree = decor.creerCourse("Backyard démarrée", 1, 5);
        assertThat(decor.sinscrire(decor.alice, demarree).statusCode()).isEqualTo(201);
        assertThat(ids(corps().get("aVenir"))).containsExactlyInAnyOrder(supprimee, demarree);

        HttpResponse<String> suppression = decor.api.requete("DELETE", DecorAbandon.CHEMIN + "/" + supprimee,
                decor.patron.entetes(), null, null);
        assertThat(suppression.statusCode()).isIn(200, 204);
        JsonNode apresSuppression = corps();
        assertThat(ids(apresSuppression.get("aVenir"))).containsExactly(demarree);
        assertThat(apresSuppression.get("enCours")).isEmpty();

        decor.horloge.fixer(DecorAbandon.T0);
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + demarree + "/demarrage",
                decor.patron.entetes(), null, null).statusCode()).isEqualTo(200);
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> inscriptionsAvant = decor.jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> passagesAvant = decor.jdbc.queryForList("select * from passage order by id");

        JsonNode apresDemarrage = corps();

        assertThat(ids(apresDemarrage.get("enCours"))).containsExactly(demarree);
        assertThat(apresDemarrage.get("aVenir")).isEmpty();
        assertThat(apresDemarrage.get("terminees")).isEmpty();
        assertThat(decor.jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(decor.jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : 5 puis 10 Courses, 40 puis 80 Inscriptions : même nombre d'instructions SQL, aucune Inscription ni Compte chargé")
    void ca10_nombre_d_instructions_independant_des_courses_et_des_inscriptions() throws Exception {
        List<UUID> courses = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            courses.add(course("Backyard " + i, "2026-11-0" + i, i % 2 == 0 ? "EN_COURS" : "EN_PREPARATION"));
        }
        poserLogo(courses.get(0));
        inscrire(courses, 40);
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(lister(Map.of()).statusCode()).isEqualTo(200);

        long pour5 = instructions(statistiques);
        for (int i = 6; i <= 10; i++) {
            courses.add(course("Backyard " + i, "2026-12-0" + (i - 5), i % 2 == 0 ? "TERMINEE" : "EN_PREPARATION"));
        }
        poserLogo(courses.get(7));
        inscrire(courses.subList(5, 10), 40);
        long pour10 = instructions(statistiques);

        assertThat(decor.jdbc.queryForObject("select count(*) from inscription", Integer.class)).isEqualTo(80);
        assertThat(pour10).as("même nombre d'instructions SQL pour 5 et 10 Courses").isEqualTo(pour5);
        assertThat(pour5).isLessThanOrEqualTo(3);
        statistiques.clear();
        assertThat(lister(Map.of()).statusCode()).isEqualTo(200);
        assertThat(java.util.Arrays.stream(statistiques.getEntityNames())
                .filter(n -> n.contains("Inscription") || n.contains("Compte") || n.contains("Passage"))
                .map(n -> statistiques.getEntityStatistics(n).getLoadCount()
                        + statistiques.getEntityStatistics(n).getFetchCount()).toList()).allMatch(n -> n == 0);
    }

    // ---------------------------------------------------------------- outils

    private Map<String, UUID> semer() {
        Map<String, UUID> c = new java.util.LinkedHashMap<>();
        c.put("A", course("Backyard Alpha", "2026-11-20", "EN_PREPARATION"));
        c.put("B", course("backyard beta", "2026-11-05", "EN_PREPARATION"));
        c.put("C", course("Backyard Gamma", "2026-11-05", "EN_PREPARATION"));
        c.put("D", course("Backyard Delta", "2026-10-20", "EN_COURS"));
        c.put("E", course("Backyard Epsilon", "2026-10-21", "EN_COURS"));
        c.put("F", course("Backyard Zeta", "2026-09-01", "TERMINEE"));
        c.put("G", course("Backyard Eta", "2026-10-10", "TERMINEE"));
        return c;
    }

    private UUID course(String nom, String date, String statut) {
        UUID id = UUID.randomUUID();
        decor.jdbc.update(DecorAbandon.INSERTION_COURSE, id, nom, Date.valueOf(LocalDate.parse(date)), statut);
        return id;
    }

    private void poserLogo(UUID course) {
        byte[] octets = OctetsDeLogo.png();
        decor.jdbc.update("insert into logo_course (course_id, contenu, type_mime, taille_octets, empreinte) "
                + "values (?, ?, 'image/png', ?, ?)", course, octets, octets.length, OctetsDeLogo.sha256(octets));
    }

    /** {@code total} Inscriptions de Comptes distincts, réparties sur les Courses données. */
    private void inscrire(List<UUID> courses, int total) {
        String empreinte = encodeur.encode(DecorAbandon.MOT_DE_PASSE);
        int base = decor.jdbc.queryForObject("select count(*) from compte", Integer.class);
        for (int i = 0; i < total; i++) {
            UUID compte = UUID.randomUUID();
            decor.jdbc.update(DecorAbandon.INSERTION_COMPTE, compte, "P" + base + "x" + i, "p" + base + "x" + i,
                    empreinte, "COUREUR", java.sql.Timestamp.from(DecorAbandon.T0));
            decor.insererInscription(courses.get(i % courses.size()), compte, i / courses.size() + 1 + base * 100,
                    "EN_COURSE");
        }
    }

    private long instructions(Statistics statistiques) throws Exception {
        long avant = statistiques.getPrepareStatementCount();
        assertThat(lister(Map.of()).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    private HttpResponse<String> lister(Map<String, String> entetes) throws Exception {
        return decor.api.requete("GET", CHEMIN, entetes, null, null);
    }

    private JsonNode corps() throws Exception {
        HttpResponse<String> reponse = lister(Map.of());
        assertThat(reponse.statusCode()).isEqualTo(200);
        return decor.json.readTree(reponse.body());
    }

    private static List<String> ids(JsonNode rubrique) {
        List<String> ids = new ArrayList<>();
        rubrique.forEach(element -> ids.add(element.get("id").asString()));
        return ids;
    }
}
