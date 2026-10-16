package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.11 : abandon manuel d'une Inscription (CA1 ; RG1, RG2, RG3, RG4).
 *
 * Signature supposée : {@code Inscription abandonnerManuellement(MotifAbandon motif, int nombrePassages)} : style
 * immuable de l'agrégat, renvoie l'Inscription ABANDON avec la Boucle d'abandon {@code nombrePassages + 1} ; lève
 * {@link IllegalArgumentException} pour HORS_DELAI (réservé à l'abandon automatique) et
 * {@link InscriptionNonEnCourseException} (statut courant) si l'Inscription n'est pas EN_COURSE.
 */
class InscriptionAbandonManuelTest {

    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPTE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static Inscription inscription(StatutInscription statut) {
        return Inscription.reconstituer(UUID.randomUUID(), COURSE, COMPTE, 7, JetonQr.generer(), statut);
    }

    @ParameterizedTest(name = "CA1 - RG3 : {0} passage(s) -> Boucle d''abandon {1}")
    @CsvSource({"0, 1", "1, 2", "2, 3", "5, 6"})
    @DisplayName("CA1 - RG3 : la Boucle d'abandon est le nombre de passages plus un")
    void doit_abandonner_sur_la_premiere_boucle_non_terminee(int nombrePassages, int boucleAttendue) {
        Inscription enCourse = inscription(StatutInscription.EN_COURSE);

        Inscription abandon = enCourse.abandonnerManuellement(MotifAbandon.MANUEL, nombrePassages);

        assertThat(abandon.statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(abandon.boucleAbandon()).contains(boucleAttendue);
    }

    @ParameterizedTest(name = "CA1 - RG2 : motif {0} accepté")
    @EnumSource(value = MotifAbandon.class, names = {"VOLONTAIRE", "MANUEL", "AUTRE"})
    @DisplayName("CA1 - RG2 : VOLONTAIRE, MANUEL et AUTRE sont conservés comme motif d'abandon")
    void doit_conserver_le_motif_accepte(MotifAbandon motif) {
        Inscription abandon = inscription(StatutInscription.EN_COURSE).abandonnerManuellement(motif, 0);

        assertThat(abandon.motifAbandon()).contains(motif);
    }

    @Test
    @DisplayName("CA1 - RG2 : HORS_DELAI est réservé à l'abandon automatique et refusé")
    void doit_refuser_le_motif_hors_delai() {
        Inscription enCourse = inscription(StatutInscription.EN_COURSE);

        assertThatThrownBy(() -> enCourse.abandonnerManuellement(MotifAbandon.HORS_DELAI, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(enCourse.statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA1 - RG4 : seuls statut, motif et Boucle changent ; identité, dossard et jeton sont conservés")
    void doit_conserver_l_identite_de_l_inscription() {
        Inscription enCourse = inscription(StatutInscription.EN_COURSE);

        Inscription abandon = enCourse.abandonnerManuellement(MotifAbandon.AUTRE, 2);

        assertThat(abandon.id()).isEqualTo(enCourse.id());
        assertThat(abandon.courseId()).isEqualTo(COURSE);
        assertThat(abandon.compteId()).isEqualTo(COMPTE);
        assertThat(abandon.dossard()).isEqualTo(7);
        assertThat(abandon.jetonQr()).isEqualTo(enCourse.jetonQr());
    }

    @Test
    @DisplayName("CA1 - l'Inscription d'origine reste EN_COURSE, sans motif ni Boucle d'abandon")
    void doit_laisser_l_inscription_d_origine_inchangee() {
        Inscription enCourse = inscription(StatutInscription.EN_COURSE);

        enCourse.abandonnerManuellement(MotifAbandon.VOLONTAIRE, 1);

        assertThat(enCourse.statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(enCourse.motifAbandon()).isEmpty();
        assertThat(enCourse.boucleAbandon()).isEmpty();
    }

    @ParameterizedTest(name = "CA1 - RG1 : Inscription {0} : InscriptionNonEnCourseException")
    @EnumSource(value = StatutInscription.class, names = {"ABANDON", "VAINQUEUR"})
    @DisplayName("CA1 - RG1 : jamais d'écrasement d'un abandon ni d'un vainqueur existant")
    void doit_refuser_l_abandon_d_une_inscription_qui_n_est_plus_en_course(StatutInscription statut) {
        Inscription sortie = inscription(statut);

        assertThatThrownBy(() -> sortie.abandonnerManuellement(MotifAbandon.MANUEL, 1))
                .isInstanceOfSatisfying(InscriptionNonEnCourseException.class,
                        e -> assertThat(e.statut()).isEqualTo(statut));
        assertThat(sortie.statut()).isEqualTo(statut);
    }

    @Test
    @DisplayName("CA1 - RG1 : un abandon automatique existant garde son motif HORS_DELAI et sa Boucle")
    void doit_conserver_le_motif_d_un_abandon_existant_apres_refus() {
        Inscription horsDelai = Inscription.reconstituer(UUID.randomUUID(), COURSE, COMPTE, 7, JetonQr.generer(),
                StatutInscription.ABANDON, MotifAbandon.HORS_DELAI, 1);

        assertThatThrownBy(() -> horsDelai.abandonnerManuellement(MotifAbandon.MANUEL, 3))
                .isInstanceOf(InscriptionNonEnCourseException.class);
        assertThat(horsDelai.motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(horsDelai.boucleAbandon()).contains(1);
    }
}
