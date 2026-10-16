package fr.backyard.tracker.courses.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** DEP-05 (audit 2026-10-16) : le planificateur borne son journal pendant une panne durable. Test unitaire sans Spring. */
class PlanificateurAbandonAutomatiqueTest {

    private static final UUID PIEGEE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SAINE = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final AbandonnerCoureursHorsDelai casDUsage = mock(AbandonnerCoureursHorsDelai.class);
    private final PlanificateurAbandonAutomatique planificateur = new PlanificateurAbandonAutomatique(casDUsage);
    private final Logger journalPlanificateur = (Logger) LoggerFactory.getLogger(PlanificateurAbandonAutomatique.class);
    private final ListAppender<ILoggingEvent> journal = new ListAppender<>();

    @BeforeEach
    void brancherJournal() {
        journal.start();
        journalPlanificateur.addAppender(journal);
    }

    @AfterEach
    void debrancherJournal() {
        journalPlanificateur.detachAppender(journal);
    }

    @Test
    @DisplayName("DEP-05 : 100 échecs consécutifs d'une Course donnent 7 ERROR avec trace, la seconde Course reste contrôlée")
    void doit_borner_le_journal_d_une_course_en_echec() {
        when(casDUsage.coursesAControler()).thenReturn(List.of(PIEGEE, SAINE));
        doThrow(new IllegalStateException("panne")).when(casDUsage).executer(PIEGEE);

        IntStream.range(0, 100).forEach(tick -> planificateur.controlerLesCoursesEnCours());

        assertThat(erreurs()).hasSize(7).allSatisfy(erreur -> {
            assertThat(erreur.getFormattedMessage()).contains(PIEGEE.toString());
            assertThat(erreur.getThrowableProxy()).isNotNull();
        });
        assertThat(erreurs().get(6).getFormattedMessage()).contains("64 échec(s) consécutif(s)");
        verify(casDUsage, times(100)).executer(SAINE);
    }

    @Test
    @DisplayName("DEP-05 : le rétablissement d'une Course est journalisé une fois, avec le nombre d'échecs")
    void doit_journaliser_le_retablissement_une_fois() {
        when(casDUsage.coursesAControler()).thenReturn(List.of(PIEGEE));
        doThrow(new IllegalStateException("panne")).when(casDUsage).executer(PIEGEE);
        IntStream.range(0, 3).forEach(tick -> planificateur.controlerLesCoursesEnCours());
        reset(casDUsage);
        when(casDUsage.coursesAControler()).thenReturn(List.of(PIEGEE));

        planificateur.controlerLesCoursesEnCours();
        planificateur.controlerLesCoursesEnCours();

        assertThat(messages(Level.INFO)).containsExactly(
                "Contrôle d'abandon automatique rétabli pour la course " + PIEGEE + " après 3 échec(s) consécutif(s)");
    }

    @Test
    @DisplayName("DEP-05 : une Course sortie des Courses en cours pendant une panne est oubliée")
    void doit_oublier_une_course_qui_n_est_plus_en_cours() {
        when(casDUsage.coursesAControler()).thenReturn(List.of(PIEGEE));
        doThrow(new IllegalStateException("panne")).when(casDUsage).executer(PIEGEE);
        planificateur.controlerLesCoursesEnCours();
        planificateur.controlerLesCoursesEnCours();
        when(casDUsage.coursesAControler()).thenReturn(List.of());
        planificateur.controlerLesCoursesEnCours();
        when(casDUsage.coursesAControler()).thenReturn(List.of(PIEGEE));

        planificateur.controlerLesCoursesEnCours();

        assertThat(erreurs()).hasSize(3);
        assertThat(erreurs().get(2).getFormattedMessage()).contains("(1 échec(s) consécutif(s))");
    }

    @Test
    @DisplayName("DEP-05 : l'échec de lecture des Courses en cours est borné de même, puis son rétablissement journalisé")
    void doit_borner_le_journal_d_un_echec_de_lecture() {
        when(casDUsage.coursesAControler()).thenThrow(new IllegalStateException("base injoignable"));

        IntStream.range(0, 10).forEach(tick -> planificateur.controlerLesCoursesEnCours());
        reset(casDUsage);
        when(casDUsage.coursesAControler()).thenReturn(List.of());
        planificateur.controlerLesCoursesEnCours();

        assertThat(erreurs()).hasSize(4).allSatisfy(erreur -> {
            assertThat(erreur.getFormattedMessage()).contains("lecture des courses en cours");
            assertThat(erreur.getThrowableProxy()).isNotNull();
        });
        assertThat(messages(Level.INFO)).containsExactly("Contrôle d'abandon automatique rétabli pour la lecture des "
                + "courses en cours après 10 échec(s) consécutif(s)");
    }

    private List<ILoggingEvent> erreurs() {
        return journal.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    private List<String> messages(Level niveau) {
        return journal.list.stream().filter(e -> e.getLevel() == niveau).map(ILoggingEvent::getFormattedMessage).toList();
    }
}
