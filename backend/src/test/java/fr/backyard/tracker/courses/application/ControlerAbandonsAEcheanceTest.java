package fr.backyard.tracker.courses.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.backyard.tracker.courses.HorlogeReglableDeTest;
import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai.Resultat;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.2 (RG8, RG9) : le contrôle d'échéance n'appelle {@code AbandonnerCoureursHorsDelai.executer} que lorsque
 * {@code bouclesTranchees(maintenant)} dépasse la dernière Boucle décidée avec succès.
 * Signatures supposées : {@code ControlerAbandonsAEcheance(DepotCourses, Clock, AbandonnerCoureursHorsDelai)},
 * {@code void executer()}, {@code DepotCourses.enCours()} ; {@code AbandonnerCoureursHorsDelai.executer(UUID)} renvoie
 * {@code Resultat}. Boucle de 60 min, départ 10:00:00Z, grâce de 3 min : la Boucle 1 est tranchée à 11:03:00,
 * la Boucle 2 à 12:03:00. Les exceptions éventuellement propagées par {@code executer()} sont tolérées (la spec ne
 * fixe pas si elles remontent) : seuls les appels au cas d'usage sont vérifiés.
 */
class ControlerAbandonsAEcheanceTest {

    private static final UUID COURSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final UUID AUTRE_COURSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000073");
    private static final Instant DEPART = Instant.parse("2026-10-21T10:00:00Z");
    private static final Resultat SANS_DECISION = new Resultat(0, 0);

    private final AbandonnerCoureursHorsDelai abandonner = mock(AbandonnerCoureursHorsDelai.class);
    private final DepotCourses depotCourses = mock(DepotCourses.class);
    private final HorlogeReglableDeTest horloge = new HorlogeReglableDeTest(DEPART);
    private ControlerAbandonsAEcheance controle = nouveauControle();

    private ControlerAbandonsAEcheance nouveauControle() {
        return new ControlerAbandonsAEcheance(depotCourses, horloge, abandonner);
    }

    private static Course courseEnCours(UUID id) {
        return Course.reconstituer(id, "Backyard " + id, LocalDate.of(2026, 10, 21), StatutCourse.EN_COURS,
                new ParametresBoucle(400, 60, 5), 10, 5, Set.of(), DEPART);
    }

    private void courseEnCoursSeule() {
        when(depotCourses.enCours()).thenReturn(List.of(courseEnCours(COURSE_ID)));
        when(abandonner.executer(any())).thenReturn(SANS_DECISION);
    }

    private void controlerA(String heure) {
        horloge.placerA(Instant.parse("2026-10-21T" + heure + "Z"));
        try {
            controle.executer();
        } catch (RuntimeException tolere) {
            // la propagation d'un échec n'est pas fixée par la spec
        }
    }

    @Test
    @DisplayName("CA15 - aucun appel à 10:30:00, 10:59:59 et 11:02:59 ; un appel à 11:03:00 ; plus aucun ensuite")
    void doit_appeler_le_cas_d_usage_une_seule_fois_a_l_echeance_de_la_boucle() {
        courseEnCoursSeule();

        controlerA("10:30:00");
        controlerA("10:59:59");
        controlerA("11:02:59");
        verify(abandonner, never()).executer(any());

        controlerA("11:03:00");
        verify(abandonner, times(1)).executer(COURSE_ID);

        controlerA("11:03:01");
        controlerA("11:30:00");
        verify(abandonner, times(1)).executer(COURSE_ID);
    }

    @Test
    @DisplayName("CA15 - le contrôle lit les Courses par enCours(), jamais toutes()")
    void doit_lire_uniquement_les_courses_en_cours() {
        courseEnCoursSeule();

        controlerA("10:30:00");

        verify(depotCourses).enCours();
        verify(depotCourses, never()).toutes();
    }

    @Test
    @DisplayName("CA16 - un échec à 11:03:00 est retenté à 11:03:01 ; après succès, plus d'appel pour la Boucle 1")
    void doit_reessayer_apres_un_echec_puis_ne_plus_appeler_apres_succes() {
        when(depotCourses.enCours()).thenReturn(List.of(courseEnCours(COURSE_ID)));
        when(abandonner.executer(COURSE_ID)).thenThrow(new IllegalStateException("panne")).thenReturn(SANS_DECISION);

        controlerA("11:03:00");
        verify(abandonner, times(1)).executer(COURSE_ID);
        controlerA("11:03:01");
        verify(abandonner, times(2)).executer(COURSE_ID);
        controlerA("11:03:02");
        controlerA("11:30:00");

        verify(abandonner, times(2)).executer(COURSE_ID);
    }

    @Test
    @DisplayName("CA16 - à 12:03:00, un nouvel appel pour la Boucle 2")
    void doit_appeler_a_nouveau_a_l_echeance_de_la_boucle_suivante() {
        courseEnCoursSeule();
        controlerA("11:03:00");

        controlerA("12:02:59");
        verify(abandonner, times(1)).executer(COURSE_ID);
        controlerA("12:03:00");

        verify(abandonner, times(2)).executer(COURSE_ID);
    }

    @Test
    @DisplayName("CA16 - redémarrage simulé à 11:30:00 : un appel (Boucle 1 jamais pointée en mémoire)")
    void doit_decider_au_premier_appel_apres_un_redemarrage() {
        courseEnCoursSeule();
        controlerA("11:03:00");
        verify(abandonner, times(1)).executer(COURSE_ID);
        controle = nouveauControle();

        controlerA("11:30:00");
        controlerA("11:30:01");

        verify(abandonner, times(2)).executer(COURSE_ID);
    }

    @Test
    @DisplayName("CA16 - une Course qui n'est plus EN_COURS est oubliée : si elle revient, la Boucle est ré-appelée")
    void doit_oublier_l_etat_d_une_course_qui_n_est_plus_en_cours() {
        courseEnCoursSeule();
        controlerA("11:03:00");
        verify(abandonner, times(1)).executer(COURSE_ID);
        when(depotCourses.enCours()).thenReturn(List.of());
        controlerA("11:03:01");
        when(depotCourses.enCours()).thenReturn(List.of(courseEnCours(COURSE_ID)));

        controlerA("11:03:02");

        verify(abandonner, times(2)).executer(COURSE_ID);
    }

    @Test
    @DisplayName("CA16 - l'échec sur une Course n'empêche pas le traitement d'une autre Course EN_COURS au même appel")
    void doit_controler_les_autres_courses_malgre_l_echec_d_une_course() {
        when(depotCourses.enCours()).thenReturn(List.of(courseEnCours(COURSE_ID), courseEnCours(AUTRE_COURSE_ID)));
        when(abandonner.executer(COURSE_ID)).thenThrow(new IllegalStateException("panne"));
        when(abandonner.executer(AUTRE_COURSE_ID)).thenReturn(SANS_DECISION);

        controlerA("11:03:00");

        verify(abandonner, times(1)).executer(COURSE_ID);
        verify(abandonner, times(1)).executer(AUTRE_COURSE_ID);
    }

    @Test
    @DisplayName("CA16 - l'état est par Course : la réussite de l'une ne masque pas l'échéance de l'autre")
    void doit_suivre_la_derniere_boucle_decidee_par_course() {
        when(depotCourses.enCours()).thenReturn(List.of(courseEnCours(COURSE_ID)));
        when(abandonner.executer(any())).thenReturn(SANS_DECISION);
        controlerA("11:03:00");
        when(depotCourses.enCours()).thenReturn(List.of(courseEnCours(COURSE_ID), courseEnCours(AUTRE_COURSE_ID)));

        controlerA("11:03:01");

        verify(abandonner, times(1)).executer(COURSE_ID);
        verify(abandonner, times(1)).executer(AUTRE_COURSE_ID);
    }
}
