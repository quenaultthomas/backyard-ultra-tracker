package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.8 : transition d'abandon d'une Inscription (CA3 ; RG3, RG12, RG13).
 *
 * Signatures supposées :
 * - enum {@code MotifAbandon} (VOLONTAIRE, HORS_DELAI, MANUEL, AUTRE) ;
 * - {@code Inscription abandonner(MotifAbandon motif, int boucleAbandon)} : style immuable de l'agrégat, renvoie
 *   l'Inscription en ABANDON et laisse l'originale inchangée ; lève InscriptionNonEnCourseException (statut courant)
 *   si l'Inscription n'est pas EN_COURSE ;
 * - {@code Optional<MotifAbandon> motifAbandon()} et {@code Optional<Integer> boucleAbandon()} (vides hors ABANDON).
 */
class InscriptionAbandonTest {

    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPTE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static Inscription inscription(StatutInscription statut) {
        return Inscription.reconstituer(UUID.randomUUID(), COURSE, COMPTE, 7, JetonQr.generer(), statut);
    }

    @Test
    @DisplayName("CA3 - abandonner(HORS_DELAI, 2) : ABANDON, motif HORS_DELAI, boucle 2, identité inchangée")
    void doit_passer_en_abandon_avec_le_motif_et_la_boucle() {
        Inscription enCourse = inscription(StatutInscription.EN_COURSE);

        Inscription abandon = enCourse.abandonner(MotifAbandon.HORS_DELAI, 2);

        assertThat(abandon.statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(abandon.motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(abandon.boucleAbandon()).contains(2);
        assertThat(abandon.id()).isEqualTo(enCourse.id());
        assertThat(abandon.courseId()).isEqualTo(enCourse.courseId());
        assertThat(abandon.compteId()).isEqualTo(enCourse.compteId());
        assertThat(abandon.dossard()).isEqualTo(7);
        assertThat(abandon.jetonQr()).isEqualTo(enCourse.jetonQr());
    }

    @Test
    @DisplayName("CA3 - l'Inscription d'origine reste EN_COURSE, sans motif ni boucle d'abandon")
    void doit_laisser_l_inscription_d_origine_inchangee() {
        Inscription enCourse = inscription(StatutInscription.EN_COURSE);

        enCourse.abandonner(MotifAbandon.HORS_DELAI, 1);

        assertThat(enCourse.statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(enCourse.motifAbandon()).isEmpty();
        assertThat(enCourse.boucleAbandon()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - une Inscription EN_COURSE n'a ni motif ni boucle d'abandon")
    void doit_ne_pas_avoir_de_motif_ni_de_boucle_d_abandon_en_course() {
        Inscription enCourse = Inscription.creer(
                Course.reconstituer(COURSE, "Backyard", java.time.LocalDate.of(2026, 10, 12),
                        StatutCourse.EN_PREPARATION, new ParametresBoucle(400, 1, 5), 10, 5),
                COMPTE, java.util.Optional.empty(), JetonQr.generer());

        assertThat(enCourse.motifAbandon()).isEmpty();
        assertThat(enCourse.boucleAbandon()).isEmpty();
    }

    @ParameterizedTest(name = "CA3 - abandonner sur une Inscription {0} : InscriptionNonEnCourseException")
    @EnumSource(value = StatutInscription.class, names = {"ABANDON", "VAINQUEUR"})
    @DisplayName("CA3 - seule une Inscription EN_COURSE peut abandonner, rien ne change sinon")
    void doit_refuser_l_abandon_d_une_inscription_qui_n_est_plus_en_course(StatutInscription statut) {
        Inscription sortie = inscription(statut);

        assertThatThrownBy(() -> sortie.abandonner(MotifAbandon.HORS_DELAI, 2))
                .isInstanceOfSatisfying(InscriptionNonEnCourseException.class,
                        e -> assertThat(e.statut()).isEqualTo(statut));
        assertThat(sortie.statut()).isEqualTo(statut);
        assertThat(sortie.motifAbandon()).isEmpty();
        assertThat(sortie.boucleAbandon()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un abandon déjà enregistré ne peut pas être remplacé (motif et boucle conservés)")
    void doit_conserver_le_premier_abandon() {
        Inscription abandon = inscription(StatutInscription.EN_COURSE).abandonner(MotifAbandon.HORS_DELAI, 1);

        assertThatThrownBy(() -> abandon.abandonner(MotifAbandon.MANUEL, 3))
                .isInstanceOf(InscriptionNonEnCourseException.class);
        assertThat(abandon.motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(abandon.boucleAbandon()).contains(1);
    }

    @Test
    @DisplayName("CA3 - toString d'une Inscription en abandon ne contient ni jeton ni identifiant de Compte")
    void doit_masquer_jeton_et_compte_dans_le_toString() {
        Inscription abandon = inscription(StatutInscription.EN_COURSE).abandonner(MotifAbandon.HORS_DELAI, 1);

        assertThat(abandon.toString())
                .doesNotContain(abandon.jetonQr().valeur())
                .doesNotContain(COMPTE.toString());
    }

    @Test
    @DisplayName("CA3 - MotifAbandon contient exactement VOLONTAIRE, HORS_DELAI, MANUEL, AUTRE")
    void doit_exposer_les_quatre_motifs_d_abandon() {
        assertThat(Arrays.stream(MotifAbandon.values()).map(Enum::name))
                .containsExactlyInAnyOrder("VOLONTAIRE", "HORS_DELAI", "MANUEL", "AUTRE");
    }
}
