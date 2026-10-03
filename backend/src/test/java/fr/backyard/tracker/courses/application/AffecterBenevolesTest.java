package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.application.AffecterBenevoles.Commande;
import fr.backyard.tracker.courses.domaine.BenevoleInconnuException;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseTermineeException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.4 : cas d'usage AffecterBenevoles (CA2 ; RG2, RG3, RG4, RG8). */
class AffecterBenevolesTest {

    private static final UUID L1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID L2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID L3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID C1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID ID_E = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesAvecAffectationsDeTest depot = new DepotCoursesAvecAffectationsDeTest();
    private final AffecterBenevoles affecterBenevoles =
            new AffecterBenevoles(depot, new AnnuaireBenevolesDeTest(L1, L2, L3));

    @Test
    @DisplayName("CA2 - A reçoit {l2, l3} : A vaut {l2, l3} et B est intacte")
    void doit_remplacer_les_benevoles_de_la_course_sans_toucher_aux_autres() {
        garnirLesDepots();

        Course resultat = affecterBenevoles.executer(new Commande(ID_A, List.of(L2, L3)));

        assertThat(resultat.id()).isEqualTo(ID_A);
        assertThat(depot.parId(ID_A).orElseThrow().benevolesAffectes()).containsExactlyInAnyOrder(L2, L3);
        assertThat(depot.parId(ID_B).orElseThrow().benevolesAffectes()).containsExactlyInAnyOrder(L1, L2);
    }

    @Test
    @DisplayName("CA2 - E EN_COURS reçoit {l2} : E vaut {l2}")
    void doit_affecter_des_benevoles_a_une_course_en_cours() {
        garnirLesDepots();

        affecterBenevoles.executer(new Commande(ID_E, List.of(L2)));

        assertThat(depot.parId(ID_E).orElseThrow().benevolesAffectes()).containsExactly(L2);
    }

    @Test
    @DisplayName("CA2 - ensemble vide sur A : A n'a plus de bénévole")
    void doit_vider_les_benevoles_de_la_course() {
        garnirLesDepots();

        affecterBenevoles.executer(new Commande(ID_A, List.of()));

        assertThat(depot.parId(ID_A).orElseThrow().benevolesAffectes()).isEmpty();
    }

    @Test
    @DisplayName("CA2 - doublons dans la commande : un seul exemplaire enregistré")
    void doit_dedoublonner_les_benevoles_de_la_commande() {
        garnirLesDepots();

        affecterBenevoles.executer(new Commande(ID_A, List.of(L3, L3)));

        assertThat(depot.parId(ID_A).orElseThrow().benevolesAffectes()).containsExactly(L3);
    }

    @Test
    @DisplayName("CA2 - un coureur dans la commande : BenevoleInconnuException, A garde {l1}, rien d'enregistré")
    void doit_refuser_toute_la_commande_si_un_identifiant_n_est_pas_un_benevole() {
        garnirLesDepots();
        int enregistrementsAvant = depot.nombreDEnregistrements;

        assertThatThrownBy(() -> affecterBenevoles.executer(new Commande(ID_A, List.of(L2, C1))))
                .isInstanceOf(BenevoleInconnuException.class);

        assertThat(depot.parId(ID_A).orElseThrow().benevolesAffectes()).containsExactly(L1);
        assertThat(depot.nombreDEnregistrements).isEqualTo(enregistrementsAvant);
    }

    @Test
    @DisplayName("CA2 - un identifiant inconnu de l'annuaire : BenevoleInconnuException, A garde {l1}")
    void doit_refuser_un_identifiant_inconnu_de_l_annuaire() {
        garnirLesDepots();

        assertThatThrownBy(() -> affecterBenevoles.executer(new Commande(ID_A, List.of(L2, ID_INCONNU))))
                .isInstanceOf(BenevoleInconnuException.class);

        assertThat(depot.parId(ID_A).orElseThrow().benevolesAffectes()).containsExactly(L1);
    }

    @Test
    @DisplayName("CA2 - Course inconnue : CourseIntrouvableException, même avec un coureur ; dépôts inchangés")
    void doit_refuser_une_course_inconnue_avant_de_verifier_les_benevoles() {
        garnirLesDepots();
        int enregistrementsAvant = depot.nombreDEnregistrements;

        assertThatThrownBy(() -> affecterBenevoles.executer(new Commande(ID_INCONNU, List.of(L1))))
                .isInstanceOf(CourseIntrouvableException.class);
        assertThatThrownBy(() -> affecterBenevoles.executer(new Commande(ID_INCONNU, List.of(C1))))
                .isInstanceOf(CourseIntrouvableException.class);

        assertThat(depot.toutes()).hasSize(4);
        assertThat(depot.nombreDEnregistrements).isEqualTo(enregistrementsAvant);
        assertThat(depot.parId(ID_A).orElseThrow().benevolesAffectes()).containsExactly(L1);
    }

    @Test
    @DisplayName("CA2 - C TERMINEE reçoit {c1} : CourseTermineeException (le statut précède l'annuaire)")
    void doit_refuser_une_course_terminee_avant_de_verifier_les_benevoles() {
        garnirLesDepots();

        assertThatThrownBy(() -> affecterBenevoles.executer(new Commande(ID_C, List.of(C1))))
                .isInstanceOf(CourseTermineeException.class);
    }

    @Test
    @DisplayName("CA2 - C TERMINEE reçoit des bénévoles valides : refus, ensemble inchangé, rien d'enregistré")
    void doit_refuser_l_affectation_d_une_course_terminee_sans_rien_enregistrer() {
        garnirLesDepots();
        int enregistrementsAvant = depot.nombreDEnregistrements;

        assertThatThrownBy(() -> affecterBenevoles.executer(new Commande(ID_C, List.of(L2))))
                .isInstanceOf(CourseTermineeException.class);

        assertThat(depot.parId(ID_C).orElseThrow().benevolesAffectes()).isEmpty();
        assertThat(depot.nombreDEnregistrements).isEqualTo(enregistrementsAvant);
    }

    private void garnirLesDepots() {
        LocalDate date = LocalDate.of(2026, 11, 14);
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(ID_A, "A", date, StatutCourse.EN_PREPARATION,
                Set.of(L1)));
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(ID_B, "B", date, StatutCourse.EN_PREPARATION,
                Set.of(L1, L2)));
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(ID_C, "C", date, StatutCourse.TERMINEE,
                Set.of()));
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(ID_E, "E", date, StatutCourse.EN_COURS,
                Set.of(L1)));
    }
}
