package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ListerMesInscriptions.MonInscription;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 3.3 : cas d'usage ListerMesInscriptions (CA1 ; RG1, RG2, RG4, RG7).
 *
 * <p>Signatures supposées : {@code new ListerMesInscriptions(DepotInscriptions, DepotCourses)},
 * {@code List<MonInscription> executer(UUID compteId)} avec {@code record MonInscription(Course course,
 * Inscription inscription)} imbriqué dans le cas d'usage.
 */
class ListerMesInscriptionsTest {

    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COURSE_Y = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID COURSE_Z = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID COURSE_W = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final UUID COURSE_V = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CHLOE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static final String JETON_X = "X".repeat(43);
    private static final String JETON_Y = "Y".repeat(43);
    private static final String JETON_Z = "Z".repeat(43);
    private static final String JETON_BRUNO = "B".repeat(43);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final ListerMesInscriptions lister = new ListerMesInscriptions(depotInscriptions, depotCourses);

    @Test
    @DisplayName("CA1 - Alice : 3 inscriptions dans l'ordre ORDRE_DE_LISTE (Y 2026-11-15, X 2026-10-10, Z 2025-12-01)")
    void doit_lister_les_inscriptions_du_compte_dans_l_ordre_de_liste_des_courses() {
        garnirLesDepots();

        List<MonInscription> resultat = lister.executer(ALICE);

        assertThat(resultat).extracting(m -> m.course().id()).containsExactly(COURSE_Y, COURSE_X, COURSE_Z);
    }

    @Test
    @DisplayName("CA1 - chaque élément porte la Course et l'Inscription d'Alice, dossards 1, 1, 3 et jetons inchangés")
    void doit_associer_chaque_inscription_a_sa_course_avec_dossard_et_jeton_inchanges() {
        garnirLesDepots();

        List<MonInscription> resultat = lister.executer(ALICE);

        assertThat(resultat).extracting(m -> m.inscription().courseId()).containsExactly(COURSE_Y, COURSE_X, COURSE_Z);
        assertThat(resultat).extracting(m -> m.inscription().compteId()).containsOnly(ALICE);
        assertThat(resultat).extracting(m -> m.inscription().dossard()).containsExactly(1, 1, 3);
        assertThat(resultat).extracting(m -> m.inscription().jetonQr().valeur())
                .containsExactly(JETON_Y, JETON_X, JETON_Z);
    }

    @Test
    @DisplayName("CA1 - aucune inscription de Bruno dans la liste d'Alice")
    void doit_exclure_les_inscriptions_des_autres_comptes() {
        garnirLesDepots();

        assertThat(lister.executer(ALICE)).extracting(m -> m.inscription().jetonQr().valeur())
                .doesNotContain(JETON_BRUNO);
    }

    @Test
    @DisplayName("CA1 - Bruno : une seule inscription, X, dossard 2")
    void doit_ne_lister_que_l_inscription_de_bruno() {
        garnirLesDepots();

        List<MonInscription> resultat = lister.executer(BRUNO);

        assertThat(resultat).singleElement().satisfies(m -> {
            assertThat(m.course().id()).isEqualTo(COURSE_X);
            assertThat(m.inscription().dossard()).isEqualTo(2);
            assertThat(m.inscription().jetonQr().valeur()).isEqualTo(JETON_BRUNO);
        });
    }

    @Test
    @DisplayName("CA1 - un compte sans inscription obtient une liste vide")
    void doit_renvoyer_une_liste_vide_pour_un_compte_sans_inscription() {
        garnirLesDepots();

        assertThat(lister.executer(CHLOE)).isEmpty();
    }

    @Test
    @DisplayName("CA1 - toutes Courses et tous statuts : Course EN_COURS, inscriptions ABANDON et VAINQUEUR renvoyées")
    void doit_renvoyer_les_inscriptions_quels_que_soient_le_statut_de_course_et_le_statut_d_inscription() {
        garnirLesDepots();
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_W, "Backyard W", LocalDate.of(2024, 5, 1),
                StatutCourse.TERMINEE));
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_V, "Backyard V", LocalDate.of(2024, 4, 1),
                StatutCourse.TERMINEE));
        depotInscriptions.enregistrer(inscription(COURSE_W, ALICE, 4, "W".repeat(43), StatutInscription.ABANDON));
        depotInscriptions.enregistrer(inscription(COURSE_V, ALICE, 5, "V".repeat(43), StatutInscription.VAINQUEUR));

        List<MonInscription> resultat = lister.executer(ALICE);

        assertThat(resultat).extracting(m -> m.course().id())
                .containsExactly(COURSE_Y, COURSE_X, COURSE_Z, COURSE_W, COURSE_V);
        assertThat(resultat).extracting(m -> m.course().statut()).contains(StatutCourse.EN_COURS,
                StatutCourse.TERMINEE);
        assertThat(resultat).extracting(m -> m.inscription().statut())
                .contains(StatutInscription.ABANDON, StatutInscription.VAINQUEUR);
    }

    @Test
    @DisplayName("CA1 - deux appels successifs renvoient les mêmes jetons")
    void doit_renvoyer_les_memes_jetons_a_deux_appels_successifs() {
        garnirLesDepots();

        List<String> premierAppel = jetons(lister.executer(ALICE));
        List<String> secondAppel = jetons(lister.executer(ALICE));

        assertThat(secondAppel).isEqualTo(premierAppel).containsExactly(JETON_Y, JETON_X, JETON_Z);
    }

    @Test
    @DisplayName("CA1 - la lecture n'enregistre rien")
    void doit_lire_sans_rien_enregistrer() {
        garnirLesDepots();
        int avant = depotInscriptions.inscriptions.size();
        int courses = depotCourses.nombreDEnregistrements;

        lister.executer(ALICE);

        assertThat(depotInscriptions.inscriptions).hasSize(avant);
        assertThat(depotCourses.nombreDEnregistrements).isEqualTo(courses);
    }

    private static List<String> jetons(List<MonInscription> inscriptions) {
        return inscriptions.stream().map(m -> m.inscription().jetonQr().valeur()).toList();
    }

    private static Inscription inscription(UUID courseId, UUID compteId, int dossard, String jeton,
                                           StatutInscription statut) {
        return Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard, new JetonQr(jeton), statut);
    }

    private void garnirLesDepots() {
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_X, "Backyard des Crêtes", LocalDate.of(2026, 10, 10),
                StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_Y, "Backyard express", LocalDate.of(2026, 11, 15),
                StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_Z, "Backyard hiver", LocalDate.of(2025, 12, 1),
                StatutCourse.EN_COURS));
        depotInscriptions.enregistrer(inscription(COURSE_X, ALICE, 1, JETON_X, StatutInscription.EN_COURSE));
        depotInscriptions.enregistrer(inscription(COURSE_Y, ALICE, 1, JETON_Y, StatutInscription.EN_COURSE));
        depotInscriptions.enregistrer(inscription(COURSE_Z, ALICE, 3, JETON_Z, StatutInscription.EN_COURSE));
        depotInscriptions.enregistrer(inscription(COURSE_X, BRUNO, 2, JETON_BRUNO, StatutInscription.EN_COURSE));
    }
}
