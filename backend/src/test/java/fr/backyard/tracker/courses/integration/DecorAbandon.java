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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Décor partagé des tests d'intégration de l'incrément 4.8 (abandon automatique). La Course X (Boucles de 1 min,
 * 5 Boucles max) est démarrée par l'API à 14:00:00.000Z, soit t0 = 14:00:00Z ; Bruno (dossard 1), Chloé (2) et
 * Tom (3) y sont inscrits, Léo est affecté. Les Passages sont posés par SQL ou par le scan, selon le test.
 */
final class DecorAbandon {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String JOUR = "2026-10-12";
    static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    /** Délai de grâce 7.1 (RG1) d'une Boucle de 1 min : min(3 min, 1 min / 4) = 15 s. */
    static final long GRACE_SECONDES = 15;
    static final String NOM_X = "Backyard des Crêtes";
    static final String NOM_Y = "Backyard du Lac";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, ?, ?, ?, 400, 1, 5, 100, 5)";
    static final String INSERTION_PASSAGE = "insert into passage (id, inscription_id, numero_boucle, scanne_le, "
            + "origine) values (?, ?, ?, ?, ?)";

    final JsonMapper json = JsonMapper.builder().build();
    final JdbcTemplate jdbc;
    final PasswordEncoder encodeur;
    final CoursesIntegrationTest.HorlogeMutable horloge;
    final int port;
    ClientHttp api;
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
    private JournalApplicatif journal;

    DecorAbandon(DataSource dataSource, PasswordEncoder encodeur, CoursesIntegrationTest.HorlogeMutable horloge,
                 int port) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.encodeur = encodeur;
        this.horloge = horloge;
        this.port = port;
    }

    /** Base vidée, comptes de référence créés, sessions ouvertes, horloge à t0. */
    void preparer(InitialiserAdminMaster initialiserAdminMaster, RegistreTentativesConnexionEnMemoire registre)
            throws Exception {
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(T0);
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

    /** X (1 min, 5 Boucles) créée, Léo affecté, Bruno 1, Chloé 2 et Tom 3 inscrits, démarrée à t0 par l'API. */
    String courseXDemarree() throws Exception {
        String x = creerCourse(NOM_X, 1, 5);
        assertThat(affecter(x, idLeo).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(tom, x).statusCode()).isEqualTo(201);
        demarrer(x);
        return x;
    }

    /** Course d'une Boucle de 1 min et d'au plus {@code maxBoucles} Boucles, mêmes inscrits que X, démarrée à t0. */
    String courseDemarreeAvecMaxBoucles(String nom, int maxBoucles) throws Exception {
        String course = creerCourse(nom, 1, maxBoucles);
        assertThat(affecter(course, idLeo).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, course).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, course).statusCode()).isEqualTo(201);
        assertThat(sinscrire(tom, course).statusCode()).isEqualTo(201);
        demarrer(course);
        return course;
    }

    String statutCourse(String idCourse) {
        return jdbc.queryForObject("select statut from course where id = ?", String.class, UUID.fromString(idCourse));
    }

    /** Y (Boucles de 90 min) : Alice inscrite, Léo affecté, démarrée à t0 par l'API. */
    String courseYDemarree() throws Exception {
        String y = creerCourse(NOM_Y, 90, 5);
        assertThat(affecter(y, idLeo).statusCode()).isEqualTo(200);
        assertThat(sinscrire(alice, y).statusCode()).isEqualTo(201);
        demarrer(y);
        return y;
    }

    private void demarrer(String idCourse) throws Exception {
        horloge.fixer(T0);
        assertThat(api.requete("POST", CHEMIN + "/" + idCourse + "/demarrage", patron.entetes(), null, null)
                .statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- API

    HttpResponse<String> pilotage(Session session, String idCourse) throws Exception {
        return api.requete("GET", CHEMIN + "/" + idCourse + "/pilotage", session.enteteLecture(), null, null);
    }

    HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", "/api/coureur/courses/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    HttpResponse<String> affecter(String idCourse, UUID benevole) throws Exception {
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + benevole + "\"]}");
    }

    HttpResponse<String> scanner(Session session, String idCourse, String jeton, UUID identifiantScan, long age)
            throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("jetonQr", jeton);
        corps.put("identifiantScan", identifiantScan.toString());
        corps.put("ageDuScanMillisecondes", age);
        return api.requete("POST", "/api/benevole/courses/" + idCourse + "/passages", session.entetes(),
                "application/json", corps.toString());
    }

    String creerCourse(String nom, int dureeMinutes, int maxBoucles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", JOUR);
        corps.put("distanceBoucleMetres", 400);
        corps.put("dureeBoucleMinutes", dureeMinutes);
        corps.put("denivelePositifBoucleMetres", 5);
        corps.put("nombreMaxParticipants", 100);
        corps.put("nombreMaxBoucles", maxBoucles);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    // ---------------------------------------------------------------- SQL

    UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(MOT_DE_PASSE),
                role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    UUID insererCourse(String nom, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(JOUR), statut);
        return id;
    }

    UUID insererInscription(UUID idCourse, UUID idCompte, int dossard, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, ?)", id, idCourse, idCompte, dossard,
                UUID.randomUUID().toString(), statut);
        return id;
    }

    String idInscription(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(idCourse), idCompte).toString();
    }

    String jeton(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select jeton_qr from inscription where course_id = ? and compte_id = ?",
                String.class, UUID.fromString(idCourse), idCompte);
    }

    /** Passage SCAN de cette Boucle, daté 5 s après le début de la Boucle. */
    void poserPassage(String idCourse, UUID idCompte, int boucle) {
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), UUID.fromString(idInscription(idCourse, idCompte)), boucle,
                Timestamp.from(T0.plusSeconds(60L * (boucle - 1) + 5)), "SCAN");
    }

    void poserPassageCorrection(String idCourse, UUID idCompte, int boucle) {
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), UUID.fromString(idInscription(idCourse, idCompte)), boucle,
                null, "CORRECTION");
    }

    /** « statut:motif:boucle » de l'Inscription (motif et boucle « - » si nuls). */
    String etat(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select statut || ':' || coalesce(motif_abandon, '-') || ':' "
                + "|| coalesce(boucle_abandon::text, '-') from inscription where course_id = ? and compte_id = ?",
                String.class, UUID.fromString(idCourse), idCompte);
    }

    int compterPassages(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select count(*) from passage where inscription_id = ?", Integer.class,
                UUID.fromString(idInscription(idCourse, idCompte)));
    }

    // ---------------------------------------------------------------- journal

    void demarrerJournal() {
        arreterJournal();
        journal = JournalApplicatif.demarrerEnDebug();
    }

    void arreterJournal() {
        if (journal == null) {
            return;
        }
        journal.fermer();
        journal = null;
    }

    /** Événements de l'application (paquet fr.backyard.tracker) depuis le démarrage du journal, copie figée. */
    List<ILoggingEvent> evenements() {
        return journal.evenementsApplicatifs();
    }

    /** Lignes INFO « Abandon automatique : … » du journal. */
    List<String> lignesAbandonAutomatique() {
        return evenements().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("Abandon automatique")).toList();
    }

    /** Lignes INFO « Fin de course : … » du journal (4.9). */
    List<String> lignesFinDeCourse() {
        return evenements().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("Fin de course")).toList();
    }

    // ---------------------------------------------------------------- attente

    static boolean attendre(BooleanSupplier condition, Duration delai) throws InterruptedException {
        Instant limite = Instant.now().plus(delai);
        while (Instant.now().isBefore(limite)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
