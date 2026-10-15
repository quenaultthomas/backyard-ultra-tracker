package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.infrastructure.PlanificateurAbandonAutomatique;
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
 * Contrat de l'incrément 4.9 : le planificateur serveur prolonge le contrôle de 4.8 par la décision « vainqueur unique » (CA7). Il est activé explicitement dans ce contexte (le socle
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
class VainqueurUniquePlanificateurIntegrationTest {

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

    private boolean termine(String course) {
        return "TERMINEE".equals(decor.statutCourse(course));
    }

    @Test
    @DisplayName("CA7 : sans toucher au service, après avance du Clock à 14:01:00.000Z, Bruno est VAINQUEUR et la Course TERMINEE en moins de 5 s ; ensuite plus aucune écriture ni ligne « Fin de course » supplémentaire")
    void ca7_vainqueur_sans_intervention() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.horloge.fixer(DecorAbandon.T0.plusMillis(59_999));
        Thread.sleep(1_500);
        assertThat(contexte.getBeansOfType(PlanificateurAbandonAutomatique.class)).hasSize(1);
        assertThat(decor.statutCourse(x)).isEqualTo("EN_COURS");
        decor.demarrerJournal();

        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(60));

        assertThat(DecorAbandon.attendre(() -> termine(x), DELAI)).isTrue();
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:1");
        List<Map<String, Object>> avant = decor.jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = decor.jdbc.queryForList("select * from course order by id");
        Thread.sleep(2_500); // ticks suivants : la Course TERMINEE sort de la liste, rien de plus
        assertThat(decor.jdbc.queryForList("select * from inscription order by id")).isEqualTo(avant);
        assertThat(decor.jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(decor.lignesFinDeCourse()).hasSize(1);
        assertThat(decor.lignesAbandonAutomatique()).hasSize(1);
    }

    @Test
    @DisplayName("CA7 : rattrapage : une Course en retard de 3 Boucles (Bruno {1, 2}, Chloé {1}) est terminée dès le premier tick (Bruno VAINQUEUR, Chloé Boucle 2, Tom Boucle 1)")
    void ca7_rattrapage_de_plusieurs_boucles() throws Exception {
        String x = decor.courseXDemarree();
        decor.poserPassage(x, decor.idBruno, 1);
        decor.poserPassage(x, decor.idBruno, 2);
        decor.poserPassage(x, decor.idChloe, 1);

        decor.horloge.fixer(DecorAbandon.T0.plusSeconds(180));

        assertThat(DecorAbandon.attendre(() -> termine(x), DELAI)).isTrue();
        assertThat(decor.etat(x, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.etat(x, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:2");
        assertThat(decor.etat(x, decor.idTom)).isEqualTo("ABANDON:HORS_DELAI:1");
    }

    @Test
    @DisplayName("CA7 : une Course piégée (écriture en échec) n'empêche pas une seconde Course d'être terminée au même tick, n'est ni terminée ni modifiée à moitié, est journalisée en ERROR sans pseudo puis retentée et terminée une fois la panne levée")
    void ca7_echec_d_une_course_n_arrete_pas_les_autres() throws Exception {
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

        assertThat(DecorAbandon.attendre(() -> termine(saine), DELAI)).isTrue();
        assertThat(decor.etat(saine, decor.idAlice)).isEqualTo("VAINQUEUR:-:-");
        assertThat(DecorAbandon.attendre(() -> erreurs().size() >= 2, DELAI))
                .as("la Course piégée est retentée aux ticks suivants").isTrue();
        assertThat(decor.etat(piegee, decor.idBruno)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.etat(piegee, decor.idChloe)).isEqualTo("EN_COURSE:-:-");
        assertThat(decor.statutCourse(piegee)).as("transaction annulée : Course non terminée").isEqualTo("EN_COURS");
        for (ILoggingEvent erreur : erreurs()) {
            assertThat(erreur.getFormattedMessage()).contains(piegee)
                    .doesNotContain("Chloé", "Bruno", "Tom", "Retardataire");
        }

        decor.jdbc.execute("drop trigger piege_abandon on inscription");

        assertThat(DecorAbandon.attendre(() -> termine(piegee), DELAI))
                .as("la Course piégée est terminée une fois la panne levée").isTrue();
        assertThat(decor.etat(piegee, decor.idBruno)).isEqualTo("VAINQUEUR:-:-");
        assertThat(decor.etat(piegee, decor.idChloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(decor.lignesFinDeCourse()).hasSize(2);
    }

    private List<ILoggingEvent> erreurs() {
        return decor.evenements().stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }
}
