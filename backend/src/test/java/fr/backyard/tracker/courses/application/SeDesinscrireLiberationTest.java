package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseCompleteException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 3.4 : libération de la place, dossard et jeton après désinscription, avec InscrireCoureur
 * (CA3, CA4 ; RG4, RG5, RG6).
 */
class SeDesinscrireLiberationTest {

    private static final UUID COURSE_P = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CHLOE = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
    private static final UUID LEO = UUID.fromString("00000000-0000-0000-0000-0000000000e9");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final InscrireCoureur inscrireCoureur = new InscrireCoureur(depotCourses, depotInscriptions,
            new GenerateurJetonQrDeterministeDeTest());
    private final SeDesinscrire seDesinscrire = new SeDesinscrire(depotCourses, depotInscriptions);

    @Test
    @DisplayName("CA3 - P pleine (2 places) : Chloé est refusée, puis acceptée avec le dossard 3 après le départ d'Alice")
    void doit_accepter_un_autre_coureur_apres_la_desinscription_sur_une_course_pleine() {
        depotCourses.enregistrer(course(COURSE_P, 2));
        Inscription alice = inscrireCoureur.executer(ALICE, COURSE_P);
        inscrireCoureur.executer(BRUNO, COURSE_P);
        assertThatThrownBy(() -> inscrireCoureur.executer(CHLOE, COURSE_P))
                .isInstanceOf(CourseCompleteException.class);

        seDesinscrire.executer(ALICE, alice.id());
        Inscription chloe = inscrireCoureur.executer(CHLOE, COURSE_P);

        assertThat(chloe.dossard()).isEqualTo(3);
        assertThat(depotInscriptions.nombreInscrits(COURSE_P)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - la place libérée n'est reprise qu'une fois : un second inscrit est refusé (complète)")
    void doit_refuser_un_second_coureur_une_fois_la_place_reprise() {
        depotCourses.enregistrer(course(COURSE_P, 2));
        Inscription alice = inscrireCoureur.executer(ALICE, COURSE_P);
        inscrireCoureur.executer(BRUNO, COURSE_P);
        seDesinscrire.executer(ALICE, alice.id());
        inscrireCoureur.executer(CHLOE, COURSE_P);

        assertThatThrownBy(() -> inscrireCoureur.executer(LEO, COURSE_P))
                .isInstanceOf(CourseCompleteException.class);
    }

    @Test
    @DisplayName("CA4 - dossards {1,2,3}, Bruno (2) part : le nouvel inscrit reçoit 4, les dossards 1 et 3 sont inchangés")
    void doit_laisser_un_trou_quand_un_dossard_intermediaire_est_libere() {
        Inscription alice = inscrireTroisCoureursDansX();
        Inscription bruno = depotInscriptions.parCompte(BRUNO).getFirst();

        seDesinscrire.executer(BRUNO, bruno.id());
        Inscription nouveau = inscrireCoureur.executer(LEO, COURSE_X);

        assertThat(nouveau.dossard()).isEqualTo(4);
        assertThat(depotInscriptions.parCompte(ALICE)).extracting(Inscription::dossard).containsExactly(1);
        assertThat(depotInscriptions.parCompte(CHLOE)).extracting(Inscription::dossard).containsExactly(3);
        assertThat(alice.dossard()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - dossards {1,2,3}, Chloé (3) part : le nouvel inscrit reçoit 3")
    void doit_reattribuer_le_plus_grand_dossard_quand_il_est_libere() {
        inscrireTroisCoureursDansX();
        Inscription chloe = depotInscriptions.parCompte(CHLOE).getFirst();

        seDesinscrire.executer(CHLOE, chloe.id());
        Inscription nouveau = inscrireCoureur.executer(LEO, COURSE_X);

        assertThat(nouveau.dossard()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA4 - toutes les Inscriptions de X supprimées : le prochain inscrit reçoit le dossard 1")
    void doit_repartir_du_dossard_1_quand_plus_aucune_inscription_n_existe() {
        inscrireTroisCoureursDansX();
        depotInscriptions.parCompte(ALICE).forEach(i -> seDesinscrire.executer(ALICE, i.id()));
        depotInscriptions.parCompte(BRUNO).forEach(i -> seDesinscrire.executer(BRUNO, i.id()));
        depotInscriptions.parCompte(CHLOE).forEach(i -> seDesinscrire.executer(CHLOE, i.id()));
        assertThat(depotInscriptions.nombreInscrits(COURSE_X)).isZero();

        Inscription nouveau = inscrireCoureur.executer(LEO, COURSE_X);

        assertThat(nouveau.dossard()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - la réinscription d'Alice crée une nouvelle Inscription : nouveau jeton, nouvel identifiant")
    void doit_attribuer_un_nouveau_jeton_et_un_nouvel_identifiant_a_la_reinscription() {
        depotCourses.enregistrer(course(COURSE_X, 50));
        Inscription ancienne = inscrireCoureur.executer(ALICE, COURSE_X);

        seDesinscrire.executer(ALICE, ancienne.id());
        Inscription nouvelle = inscrireCoureur.executer(ALICE, COURSE_X);

        assertThat(nouvelle.jetonQr()).isNotEqualTo(ancienne.jetonQr());
        assertThat(nouvelle.id()).isNotEqualTo(ancienne.id());
        assertThat(depotInscriptions.inscriptions).extracting(Inscription::jetonQr)
                .doesNotContain(ancienne.jetonQr());
    }

    private Inscription inscrireTroisCoureursDansX() {
        depotCourses.enregistrer(course(COURSE_X, 50));
        Inscription alice = inscrireCoureur.executer(ALICE, COURSE_X);
        inscrireCoureur.executer(BRUNO, COURSE_X);
        inscrireCoureur.executer(CHLOE, COURSE_X);
        return alice;
    }

    private static Course course(UUID id, int nombreMaxParticipants) {
        return Course.reconstituer(id, "Backyard des Crêtes", LocalDate.of(2026, 11, 14),
                StatutCourse.EN_PREPARATION, new ParametresBoucle(6706, 60, 120), nombreMaxParticipants, 24);
    }
}
