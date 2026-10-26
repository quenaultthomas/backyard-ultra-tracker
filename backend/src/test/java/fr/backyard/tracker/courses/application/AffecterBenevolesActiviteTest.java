package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.application.AffecterBenevoles.Commande;
import fr.backyard.tracker.courses.domaine.AnnuaireBenevoles;
import fr.backyard.tracker.courses.domaine.BenevoleDesactiveException;
import fr.backyard.tracker.courses.domaine.BenevoleInconnuException;
import fr.backyard.tracker.courses.domaine.CourseTermineeException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.1, CA7 : un bénévole désactivé ne peut pas être ajouté à une Course, mais reste libre d'y rester ou d'en
 * être retiré (RG13). L'annuaire expose {@code estBenevole(UUID)} et {@code estBenevoleActif(UUID)}.
 */
class AffecterBenevolesActiviteTest {

    private static final UUID LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID MARC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ALICE_COUREUSE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 14);

    private final DepotCoursesAvecAffectationsDeTest depot = new DepotCoursesAvecAffectationsDeTest();
    // Léo est un bénévole désactivé, Marc un bénévole actif.
    private final AffecterBenevoles affecter = new AffecterBenevoles(depot, new AnnuaireDeTest(Set.of(LEO, MARC), Set.of(MARC)));

    private void course(UUID id, StatutCourse statut, Set<UUID> benevoles) {
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(id, "Course " + id, DATE, statut, benevoles));
        depot.nombreDEnregistrements = 0;
    }

    private Set<UUID> affectes(UUID idCourse) {
        return depot.parId(idCourse).orElseThrow().benevolesAffectes();
    }

    @Test
    @DisplayName("CA7 - un nouvel affecté inactif est refusé et la Course est inchangée")
    void doit_refuser_un_nouvel_affecte_desactive() {
        course(P, StatutCourse.EN_PREPARATION, Set.of(MARC));

        assertThatThrownBy(() -> affecter.executer(new Commande(P, List.of(LEO, MARC))))
                .isInstanceOf(BenevoleDesactiveException.class);

        assertThat(affectes(P)).containsExactly(MARC);
        assertThat(depot.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA7 - un nouvel affecté inactif est refusé même s'il est seul dans la commande")
    void doit_refuser_un_benevole_desactive_seul() {
        course(P, StatutCourse.EN_PREPARATION, Set.of());

        assertThatThrownBy(() -> affecter.executer(new Commande(P, List.of(LEO))))
                .isInstanceOf(BenevoleDesactiveException.class);

        assertThat(affectes(P)).isEmpty();
    }

    @Test
    @DisplayName("CA7 - un nouvel affecté actif est accepté")
    void doit_accepter_un_nouvel_affecte_actif() {
        course(P, StatutCourse.EN_PREPARATION, Set.of());

        affecter.executer(new Commande(P, List.of(MARC)));

        assertThat(affectes(P)).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA7 - un bénévole inactif déjà affecté peut rester dans l'ensemble envoyé")
    void doit_laisser_un_benevole_desactive_deja_affecte_dans_l_ensemble() {
        course(C, StatutCourse.EN_COURS, Set.of(LEO));

        affecter.executer(new Commande(C, List.of(LEO, MARC)));

        assertThat(affectes(C)).containsExactlyInAnyOrder(LEO, MARC);
    }

    @Test
    @DisplayName("CA7 - un bénévole inactif déjà affecté peut être retiré")
    void doit_permettre_de_retirer_un_benevole_desactive_deja_affecte() {
        course(C, StatutCourse.EN_COURS, Set.of(LEO));

        affecter.executer(new Commande(C, List.of(MARC)));

        assertThat(affectes(C)).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA7 - un ensemble vide retire un bénévole inactif déjà affecté")
    void doit_vider_une_course_dont_le_seul_benevole_est_desactive() {
        course(C, StatutCourse.EN_COURS, Set.of(LEO));

        affecter.executer(new Commande(C, List.of()));

        assertThat(affectes(C)).isEmpty();
    }

    @Test
    @DisplayName("CA7 - identifiant inconnu : BenevoleInconnuException, avant le contrôle d'activité")
    void doit_refuser_un_identifiant_inconnu_avant_de_controler_l_activite() {
        course(P, StatutCourse.EN_PREPARATION, Set.of(MARC));

        assertThatThrownBy(() -> affecter.executer(new Commande(P, List.of(LEO, INCONNU))))
                .isInstanceOf(BenevoleInconnuException.class);

        assertThat(affectes(P)).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA7 - un coureur dans la commande : BenevoleInconnuException, avant le contrôle d'activité")
    void doit_refuser_un_coureur_avant_de_controler_l_activite() {
        course(P, StatutCourse.EN_PREPARATION, Set.of());

        assertThatThrownBy(() -> affecter.executer(new Commande(P, List.of(ALICE_COUREUSE, LEO))))
                .isInstanceOf(BenevoleInconnuException.class);
    }

    @Test
    @DisplayName("CA7 - Course TERMINEE : le refus existant l'emporte, même avec un bénévole inactif")
    void doit_refuser_une_course_terminee_avant_de_controler_l_activite() {
        course(T, StatutCourse.TERMINEE, Set.of());

        assertThatThrownBy(() -> affecter.executer(new Commande(T, List.of(LEO))))
                .isInstanceOf(CourseTermineeException.class);

        assertThat(affectes(T)).isEmpty();
    }

    /** Annuaire de test : bénévoles connus et, parmi eux, ceux qui sont actifs. */
    private static final class AnnuaireDeTest implements AnnuaireBenevoles {
        private final Set<UUID> benevoles;
        private final Set<UUID> actifs;

        AnnuaireDeTest(Set<UUID> benevoles, Set<UUID> actifs) {
            this.benevoles = benevoles;
            this.actifs = actifs;
        }

        @Override
        public boolean estBenevole(UUID idCompte) {
            return benevoles.contains(idCompte);
        }

        @Override
        public boolean estBenevoleActif(UUID idCompte) {
            return actifs.contains(idCompte);
        }
    }
}
