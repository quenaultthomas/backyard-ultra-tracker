package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.infrastructure.PlanificateurAbandonAutomatique;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrat de l'incrément 4.8 : le planificateur serveur (CA8). Il est activé explicitement dans ce contexte (le socle
 * des tests le désactive) ; la décision se prend sur le Clock de test, le tick tourne en temps réel (1 s). Le contexte
 * est fermé en fin de classe (avant l'arrêt du conteneur, d'où @Testcontainers déclaré en premier) pour que le
 * planificateur ne continue pas de tourner contre une base arrêtée.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.abandon-automatique.actif=true"})
@DirtiesContext
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class AbandonAutomatiquePlanificateurIntegrationTest {

    static final Duration DELAI = Duration.ofSeconds(5);

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
    ApplicationContext contexte;

    DecorAbandon decor;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
    }

    @AfterEach
    void nettoyer() {
        decor.jdbc.execute("drop trigger if exists piege_abandon on inscription");
        decor.jdbc.execute("drop function if exists piege_abandon()");
        decor.arreterJournal();
    }

    private boolean abandonne(String course, UUID compte) {
        return decor.etat(course, compte).startsWith("ABANDON");
    }

    @Test
    @DisplayName("CA8 : le planificateur est présent ; après avance du Clock à t0 + 60 s, sans rien appeler, Chloé est ABANDON en moins de 5 s, Bruno non")
    void ca8_abandon_sans_intervention() throws Exception {
        assertThat(contexte.getBeansOfType(PlanificateurAbandonAutomatique.class)).hasSize(1);
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idTom, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusMillis(59_999));
        Thread.sleep(1_500);
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        decor.demarrerJournal();

        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));

        assertThat(DecorAbandon.attendre(() -> abandonne(x, decor.idChloe), DELAI)).isTrue();
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("EN_COURSE:-:-");
        Thread.sleep(2_500); // ticks suivants : rien de plus, une seule ligne
        assertThat(decor.lignesAbandonAutomatique()).hasSize(1);
    }

    @Test
    @DisplayName("CA8 : rattrapage : une Course en retard de 3 Boucles est traitée Boucle après Boucle au premier tick (Tom Boucle 1, Chloé Boucle 2, Bruno EN_COURSE)")
    void ca8_rattrapage_de_plusieurs_boucles() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.poserPassage(x, decor.idChloe, 1);

        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(180));

        assertThat(DecorAbandon.attendre(() -> abandonne(x, decor.idChloe) && abandonne(x, decor.idTom), DELAI))
                .isTrue();
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:2");
        // 4.9 RG2 : Bruno, seul passant de la Boucle 2, est VAINQUEUR (4.8 attendait EN_COURSE)
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
    }

    @Test
    @DisplayName("CA8 : Courses TERMINEE et EN_PREPARATION jamais modifiées par le planificateur, pendant qu'une Course EN_COURS est contrôlée")
    void ca8_courses_hors_perimetre_intactes() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        UUID terminee = decor.insererCourse("Terminée", "TERMINEE");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), terminee);
        UUID enPreparation = decor.insererCourse("En préparation", "EN_PREPARATION");
        for (UUID course : List.of(terminee, enPreparation)) {
            UUID avecPassage = decor.insererInscription(course, decor.idBruno, 1, "EN_COURSE");
            decor.insererInscription(course, decor.idChloe, 2, "EN_COURSE");
            decor.jdbc.update(DecorAbandon.INSERTION_PASSAGE, UUID.randomUUID(), avecPassage, 1,
                    Timestamp.from(DecorAbandon.T0.plusSeconds(5)), "SCAN");
        }
        List<Map<String, Object>> avant = decor.jdbc.queryForList(
                "select * from inscription where course_id in (?, ?) order by id", terminee, enPreparation);

        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(180));

        assertThat(DecorAbandon.attendre(() -> abandonne(x, decor.idChloe), DELAI)).isTrue();
        Thread.sleep(1_500);
        assertThat(decor.jdbc.queryForList("select * from inscription where course_id in (?, ?) order by id",
                terminee, enPreparation)).isEqualTo(avant);
        assertThat(decor.jdbc.queryForObject("select statut from course where id = ?", String.class, terminee))
                .isEqualTo("TERMINEE");
    }

    @Test
    @DisplayName("CA8 : une Course dont le traitement échoue est journalisée en ERROR (identifiant, sans pseudo), n'empêche pas une seconde Course d'être contrôlée au même tick, et est retentée aux ticks suivants")
    void ca8_echec_d_une_course_n_arrete_pas_les_autres() throws Exception {
        String piegee = decor.courseXDemarree();
        decor.poserPassage(piegee, decor.idBruno, 1);
        String saine = decor.creerCourse("Backyard saine", 1, 5);
        assertThat(decor.sinscrire(decor.alice, saine).statusCode()).isEqualTo(201);
        UUID retardataire = decor.insererCompte("Retardataire", "COUREUR");
        decor.insererInscription(UUID.fromString(saine), retardataire, 9, "EN_COURSE");
        decor.horloge.fixer(DecorAbandon.T0);
        assertThat(decor.api.requete("POST", DecorAbandon.CHEMIN + "/" + saine + "/demarrage",
                decor.patron.entetes(), null, null).statusCode()).isEqualTo(200);
        decor.poserPassage(saine, decor.idAlice, 1);
        decor.jdbc.execute("create function piege_abandon() returns trigger language plpgsql as $$ begin "
                + "if new.course_id = '" + piegee + "'::uuid then raise exception 'piege de test'; end if; "
                + "return new; end $$");
        decor.jdbc.execute("create trigger piege_abandon before update on inscription for each row "
                + "execute function piege_abandon()");
        decor.demarrerJournal();

        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));

        assertThat(DecorAbandon.attendre(() -> abandonne(saine, retardataire), DELAI)).isTrue();
        assertThat(DecorAbandon.attendre(() -> erreurs().size() >= 2, DELAI))
                .as("la Course piégée est retentée aux ticks suivants").isTrue();
        assertThat(decor.etat(piegee, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(piegee, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.jdbc.queryForObject("select statut from course where id = ?", String.class,
                UUID.fromString(piegee))).as("transaction annulée : la Course n'est pas terminée").isEqualTo("EN_COURS");
        for (ILoggingEvent erreur : erreurs()) {
            assertThat(erreur.getFormattedMessage()).contains(piegee)
                    .doesNotContain("Chloé", "Bruno", "Tom", "Retardataire");
            assertThat(erreur.getThrowableProxy()).isNotNull();
            assertThat(erreur.getThrowableProxy().getMessage()).doesNotContain("Chloé", "Bruno", "Tom");
        }

        decor.jdbc.execute("drop trigger piege_abandon on inscription");

        assertThat(DecorAbandon.attendre(() -> abandonne(piegee, decor.idChloe), DELAI))
                .as("la Course piégée est rattrapée une fois la panne levée").isTrue();
        assertThat(decor.etat(piegee, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        // 4.9 : Bruno, seul passant de la Boucle 1, est VAINQUEUR une fois la panne levée (4.8 attendait EN_COURSE)
        assertThat(decor.etat(piegee, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
    }

    private List<ILoggingEvent> erreurs() {
        return decor.evenements().stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }
}
