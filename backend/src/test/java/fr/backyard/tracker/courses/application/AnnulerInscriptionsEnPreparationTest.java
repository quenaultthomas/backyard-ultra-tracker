package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 3.6, CA3 : annulation des Inscriptions d'un Compte aux Courses EN_PREPARATION, conservation des autres
 * (RG4, RG5, RG11).
 */
class AnnulerInscriptionsEnPreparationTest {

    // Identifiants volontairement déclarés dans le désordre : l'ordre de verrouillage doit être l'ordre croissant.
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID COURSE_SUPPRIMEE = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final AnnulerInscriptionsEnPreparation annuler =
            new AnnulerInscriptionsEnPreparation(depotCourses, depotInscriptions);

    private Inscription aliceP1;
    private Inscription aliceP2;
    private Inscription aliceC;
    private Inscription aliceT;
    private Inscription brunoP1;

    private void garnir() {
        depotCourses.enregistrer(course(P2, "Backyard express", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(P1, "Backyard des Crêtes", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(T, "Backyard terminée", StatutCourse.TERMINEE));
        depotCourses.enregistrer(course(C, "Backyard en cours", StatutCourse.EN_COURS));
        aliceT = inscription(T, ALICE, 1, 4, StatutInscription.VAINQUEUR);
        aliceC = inscription(C, ALICE, 1, 3, StatutInscription.ABANDON);
        aliceP2 = inscription(P2, ALICE, 1, 2, StatutInscription.EN_COURSE);
        aliceP1 = inscription(P1, ALICE, 1, 1, StatutInscription.EN_COURSE);
        brunoP1 = inscription(P1, BRUNO, 2, 5, StatutInscription.EN_COURSE);
        for (Inscription inscription : List.of(aliceT, aliceC, aliceP2, aliceP1, brunoP1)) {
            depotInscriptions.enregistrer(inscription);
        }
    }

    @Test
    @DisplayName("CA3 - annulerPour(alice) retourne 2 : les Inscriptions à P1 et P2")
    void doit_retourner_le_nombre_d_inscriptions_annulees() {
        garnir();

        int annulees = annuler.annulerPour(ALICE);

        assertThat(annulees).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - P1 et P2 (en préparation) n'ont plus l'Inscription d'Alice")
    void doit_supprimer_les_inscriptions_aux_courses_en_preparation() {
        garnir();

        annuler.annulerPour(ALICE);

        assertThat(depotInscriptions.inscriptions).doesNotContain(aliceP1, aliceP2);
        assertThat(depotInscriptions.existePour(P1, ALICE)).isFalse();
        assertThat(depotInscriptions.existePour(P2, ALICE)).isFalse();
    }

    @Test
    @DisplayName("CA3 - les Inscriptions à C (EN_COURS) et T (TERMINEE) sont conservées : dossard, jeton, statut identiques")
    void doit_conserver_les_inscriptions_aux_courses_demarrees_ou_terminees() {
        garnir();

        annuler.annulerPour(ALICE);

        assertThat(depotInscriptions.parIdEtCompte(aliceC.id(), ALICE)).get().satisfies(i -> {
            assertThat(i.courseId()).isEqualTo(C);
            assertThat(i.dossard()).isEqualTo(1);
            assertThat(i.jetonQr()).isEqualTo(aliceC.jetonQr());
            assertThat(i.statut()).isEqualTo(StatutInscription.ABANDON);
        });
        assertThat(depotInscriptions.parIdEtCompte(aliceT.id(), ALICE)).get().satisfies(i -> {
            assertThat(i.courseId()).isEqualTo(T);
            assertThat(i.dossard()).isEqualTo(1);
            assertThat(i.jetonQr()).isEqualTo(aliceT.jetonQr());
            assertThat(i.statut()).isEqualTo(StatutInscription.VAINQUEUR);
        });
    }

    @Test
    @DisplayName("CA3 - l'Inscription de Bruno à P1 est intacte et sa place reste attribuée (dossard non réattribué)")
    void doit_laisser_intacte_l_inscription_d_un_autre_coureur() {
        garnir();

        annuler.annulerPour(ALICE);

        assertThat(depotInscriptions.parIdEtCompte(brunoP1.id(), BRUNO)).get().satisfies(i -> {
            assertThat(i.dossard()).isEqualTo(2);
            assertThat(i.jetonQr()).isEqualTo(brunoP1.jetonQr());
            assertThat(i.statut()).isEqualTo(StatutInscription.EN_COURSE);
        });
        assertThat(depotInscriptions.nombreInscrits(P1)).isEqualTo(1);
        assertThat(depotInscriptions.nombreInscrits(P2)).isZero();
    }

    @Test
    @DisplayName("CA3 - les Courses ne sont ni modifiées ni supprimées")
    void doit_laisser_les_courses_intactes() {
        garnir();

        annuler.annulerPour(ALICE);

        assertThat(depotCourses.toutes()).hasSize(4);
        assertThat(depotCourses.suppressions).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un Compte sans Inscription : 0, rien n'est supprimé, aucune exception")
    void doit_retourner_zero_pour_un_compte_sans_inscription() {
        garnir();
        UUID chloe = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

        int annulees = annuler.annulerPour(chloe);

        assertThat(annulees).isZero();
        assertThat(depotInscriptions.inscriptions).hasSize(5);
        assertThat(depotCourses.chargementsPourModification).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un Compte inscrit seulement à des Courses démarrées : 0, tout est conservé")
    void doit_retourner_zero_quand_toutes_les_courses_sont_demarrees_ou_terminees() {
        garnir();
        depotInscriptions.supprimer(aliceP1.id());
        depotInscriptions.supprimer(aliceP2.id());

        int annulees = annuler.annulerPour(ALICE);

        assertThat(annulees).isZero();
        assertThat(depotInscriptions.inscriptions).containsExactlyInAnyOrder(aliceC, aliceT, brunoP1);
    }

    @Test
    @DisplayName("CA3 - une Course supprimée entre-temps est ignorée : pas d'exception, elle n'est pas comptée")
    void doit_ignorer_une_course_supprimee_entre_la_lecture_et_le_verrou() {
        garnir();
        depotInscriptions.enregistrer(inscription(COURSE_SUPPRIMEE, ALICE, 1, 6, StatutInscription.EN_COURSE));

        int annulees = annuler.annulerPour(ALICE);

        assertThat(annulees).isEqualTo(2);
        assertThat(depotInscriptions.existePour(P1, ALICE)).isFalse();
        assertThat(depotInscriptions.existePour(P2, ALICE)).isFalse();
    }

    @Test
    @DisplayName("CA3 - les Courses sont chargées sous verrou, dans l'ordre croissant de leur identifiant")
    void doit_verrouiller_les_courses_dans_l_ordre_croissant_des_identifiants() {
        garnir();

        annuler.annulerPour(ALICE);

        assertThat(depotCourses.chargementsPourModification).contains(P1, P2).isSorted();
        assertThat(depotCourses.chargementsPourModification).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("CA3 - même verrouillage croissant pour des Inscriptions enregistrées dans l'ordre inverse")
    void doit_verrouiller_en_ordre_croissant_quelle_que_soit_l_ordre_des_inscriptions() {
        garnir();
        depotInscriptions.inscriptions.clear();
        depotInscriptions.enregistrer(aliceP2);
        depotInscriptions.enregistrer(aliceT);
        depotInscriptions.enregistrer(aliceP1);

        annuler.annulerPour(ALICE);

        assertThat(depotCourses.chargementsPourModification).isSorted();
        assertThat(depotCourses.chargementsPourModification).contains(P1, P2);
    }

    @Test
    @DisplayName("CA3 - une Course passée EN_COURS entre la lecture et le verrou conserve l'Inscription")
    void doit_conserver_l_inscription_d_une_course_demarree_entre_la_lecture_et_le_verrou() {
        DepotCoursesQuiDemarreAuVerrou depotCoursesConcurrent = new DepotCoursesQuiDemarreAuVerrou(
                course(P1, "Backyard des Crêtes", StatutCourse.EN_PREPARATION));
        DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
        Inscription aliceDemarree = inscription(P1, ALICE, 1, 1, StatutInscription.EN_COURSE);
        inscriptions.enregistrer(aliceDemarree);
        AnnulerInscriptionsEnPreparation cas = new AnnulerInscriptionsEnPreparation(depotCoursesConcurrent, inscriptions);

        int annulees = cas.annulerPour(ALICE);

        assertThat(annulees).isZero();
        assertThat(inscriptions.inscriptions).containsExactly(aliceDemarree);
    }

    // --- fabriques et doubles -------------------------------------------------------------------------------

    private static Course course(UUID id, String nom, StatutCourse statut) {
        return Course.reconstituer(id, nom, LocalDate.of(2026, 11, 14), statut, new ParametresBoucle(6706, 60, 120), 3,
                24);
    }

    private static Inscription inscription(UUID courseId, UUID compteId, int dossard, int jeton, StatutInscription statut) {
        return Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                new JetonQr(String.format("%043d", jeton)), statut);
    }

    /**
     * Course vue EN_PREPARATION par une lecture simple, mais déjà EN_COURS quand elle est lue sous verrou : le démarrage
     * a eu lieu entre les deux.
     */
    private static final class DepotCoursesQuiDemarreAuVerrou implements DepotCourses {
        private final Course enPreparation;

        DepotCoursesQuiDemarreAuVerrou(Course enPreparation) {
            this.enPreparation = enPreparation;
        }

        @Override
        public void enregistrer(Course course) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Course> toutes() {
            return new ArrayList<>(List.of(enPreparation));
        }

        @Override
        public Optional<Course> parId(UUID id) {
            return id.equals(enPreparation.id()) ? Optional.of(enPreparation) : Optional.empty();
        }

        @Override
        public Optional<Course> parIdPourModification(UUID id) {
            return id.equals(enPreparation.id())
                    ? Optional.of(course(id, "Backyard des Crêtes", StatutCourse.EN_COURS))
                    : Optional.empty();
        }
    }
}
