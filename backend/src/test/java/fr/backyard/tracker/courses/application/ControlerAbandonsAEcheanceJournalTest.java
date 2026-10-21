package fr.backyard.tracker.courses.application;

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
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * DEP-05 (audit 2026-10-16) : le contrôle d'abandon automatique borne son journal pendant une panne durable.
 * Test unitaire sans Spring.
 * Adapté en 7.2 : les Courses en cours viennent de {@code DepotCourses.enCours()} et le cas d'usage n'est appelé que
 * pour une Boucle tranchée non encore décidée avec succès ; l'horloge est placée après l'échéance de la Boucle 1.
 * Déplacé en 7.2 de PlanificateurAbandonAutomatiqueTest : la logique vit désormais dans ControlerAbandonsAEcheance.
 */
class ControlerAbandonsAEcheanceJournalTest {

    private static final UUID PIEGEE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SAINE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant DEPART = Instant.parse("2026-10-21T10:00:00Z");
    /** Boucle de 60 min et grâce de 3 min : la Boucle 1 est tranchée, la Boucle 2 pas encore. */
    private static final Clock APRES_ECHEANCE_BOUCLE_1 = Clock.fixed(Instant.parse("2026-10-21T11:30:00Z"),
            ZoneOffset.UTC);

    private final AbandonnerCoureursHorsDelai casDUsage = mock(AbandonnerCoureursHorsDelai.class);
    private final DepotCourses depotCourses = mock(DepotCourses.class);
    private final ControlerAbandonsAEcheance controle =
            new ControlerAbandonsAEcheance(depotCourses, APRES_ECHEANCE_BOUCLE_1, casDUsage);
    private final Logger journalControle = (Logger) LoggerFactory.getLogger(ControlerAbandonsAEcheance.class);
    private final ListAppender<ILoggingEvent> journal = new ListAppender<>();

    @BeforeEach
    void brancherJournal() {
        journal.start();
        journalControle.addAppender(journal);
    }

    @AfterEach
    void debrancherJournal() {
        journalControle.detachAppender(journal);
    }

    @Test
    @DisplayName("DEP-05 : 100 échecs consécutifs d'une Course donnent 7 ERROR avec trace, la seconde Course reste contrôlée")
    void doit_borner_le_journal_d_une_course_en_echec() {
        when(depotCourses.enCours()).thenReturn(courses(PIEGEE, SAINE));
        doThrow(new IllegalStateException("panne")).when(casDUsage).executer(PIEGEE);

        controle.executer();
        verify(casDUsage, times(1)).executer(SAINE);
        IntStream.range(1, 100).forEach(tick -> controle.executer());

        assertThat(erreurs()).hasSize(7).allSatisfy(erreur -> {
            assertThat(erreur.getFormattedMessage()).contains(PIEGEE.toString());
            assertThat(erreur.getThrowableProxy()).isNotNull();
        });
        assertThat(erreurs().get(6).getFormattedMessage()).contains("64 échec(s) consécutif(s)");
        verify(casDUsage, times(100)).executer(PIEGEE);
        // 7.2 RG9 : la Course saine, décidée avec succès au premier tick, n'est plus rappelée pour la même Boucle.
        verify(casDUsage, times(1)).executer(SAINE);
    }

    @Test
    @DisplayName("DEP-05 : le rétablissement d'une Course est journalisé une fois, avec le nombre d'échecs")
    void doit_journaliser_le_retablissement_une_fois() {
        when(depotCourses.enCours()).thenReturn(courses(PIEGEE));
        doThrow(new IllegalStateException("panne")).when(casDUsage).executer(PIEGEE);
        IntStream.range(0, 3).forEach(tick -> controle.executer());
        reset(casDUsage);

        controle.executer();
        controle.executer();

        assertThat(messages(Level.INFO)).containsExactly(
                "Contrôle d'abandon automatique rétabli pour la course " + PIEGEE + " après 3 échec(s) consécutif(s)");
    }

    @Test
    @DisplayName("DEP-05 : une Course sortie des Courses en cours pendant une panne est oubliée")
    void doit_oublier_une_course_qui_n_est_plus_en_cours() {
        when(depotCourses.enCours()).thenReturn(courses(PIEGEE));
        doThrow(new IllegalStateException("panne")).when(casDUsage).executer(PIEGEE);
        controle.executer();
        controle.executer();
        when(depotCourses.enCours()).thenReturn(List.of());
        controle.executer();
        when(depotCourses.enCours()).thenReturn(courses(PIEGEE));

        controle.executer();

        assertThat(erreurs()).hasSize(3);
        assertThat(erreurs().get(2).getFormattedMessage()).contains("(1 échec(s) consécutif(s))");
    }

    @Test
    @DisplayName("DEP-05 : l'échec de lecture des Courses en cours est borné de même, puis son rétablissement journalisé")
    void doit_borner_le_journal_d_un_echec_de_lecture() {
        when(depotCourses.enCours()).thenThrow(new IllegalStateException("base injoignable"));

        IntStream.range(0, 10).forEach(tick -> controle.executer());
        reset(depotCourses);
        when(depotCourses.enCours()).thenReturn(List.of());
        controle.executer();

        assertThat(erreurs()).hasSize(4).allSatisfy(erreur -> {
            assertThat(erreur.getFormattedMessage()).contains("lecture des courses en cours");
            assertThat(erreur.getThrowableProxy()).isNotNull();
        });
        assertThat(messages(Level.INFO)).containsExactly("Contrôle d'abandon automatique rétabli pour la lecture des "
                + "courses en cours après 10 échec(s) consécutif(s)");
    }

    private static List<Course> courses(UUID... ids) {
        return java.util.Arrays.stream(ids).map(id -> Course.reconstituer(id, "Backyard " + id,
                LocalDate.of(2026, 10, 21), StatutCourse.EN_COURS, new ParametresBoucle(400, 60, 5), 10, 5, Set.of(),
                DEPART)).toList();
    }

    private List<ILoggingEvent> erreurs() {
        return journal.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    private List<String> messages(Level niveau) {
        return journal.list.stream().filter(e -> e.getLevel() == niveau).map(ILoggingEvent::getFormattedMessage).toList();
    }
}
