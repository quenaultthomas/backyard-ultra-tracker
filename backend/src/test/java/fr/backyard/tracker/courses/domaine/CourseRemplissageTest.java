package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests de l'incrément 3.2 : complétude d'une Course et autorisation d'inscription (CA1 ; RG2, RG3). */
class CourseRemplissageTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @ParameterizedTest(name = "CA1 - max 2, {0} inscrit(s) : complète = {1}")
    @CsvSource({"0,false", "1,false", "2,true", "3,true"})
    void doit_etre_complete_quand_le_nombre_d_inscrits_atteint_le_maximum(int inscrits, boolean attendu) {
        assertThat(course(StatutCourse.EN_PREPARATION, 2).estComplete(inscrits)).isEqualTo(attendu);
    }

    @ParameterizedTest(name = "CA1 - max 1, {0} inscrit(s) : complète = {1}")
    @CsvSource({"0,false", "1,true"})
    void doit_etre_complete_des_la_premiere_inscription_quand_le_maximum_est_1(int inscrits, boolean attendu) {
        assertThat(course(StatutCourse.EN_PREPARATION, 1).estComplete(inscrits)).isEqualTo(attendu);
    }

    @Test
    @DisplayName("CA1 - verifierPlaceDisponible(1) ne lève rien pour un maximum de 2")
    void doit_accepter_une_inscription_quand_il_reste_une_place() {
        assertThatCode(() -> course(StatutCourse.EN_PREPARATION, 2).verifierPlaceDisponible(1))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA1 - verifierPlaceDisponible({0}) lève CourseCompleteException pour un maximum de 2")
    @ValueSource(ints = {2, 3})
    void doit_refuser_une_inscription_quand_la_course_est_complete(int inscrits) {
        assertThatThrownBy(() -> course(StatutCourse.EN_PREPARATION, 2).verifierPlaceDisponible(inscrits))
                .isInstanceOf(CourseCompleteException.class);
    }

    @Test
    @DisplayName("CA1 - autoriserInscription ne lève rien pour une Course EN_PREPARATION")
    void doit_autoriser_l_inscription_a_une_course_en_preparation() {
        assertThatCode(() -> course(StatutCourse.EN_PREPARATION, 2).autoriserInscription())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA1 - autoriserInscription lève CourseNonOuverteException pour {0}")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_refuser_l_inscription_a_une_course_demarree_ou_terminee(StatutCourse statut) {
        assertThatThrownBy(() -> course(statut, 2).autoriserInscription())
                .isInstanceOf(CourseNonOuverteException.class);
    }

    @Test
    @DisplayName("CA1 - les messages des deux exceptions ne contiennent ni pseudo ni jeton")
    void doit_ne_reveler_ni_pseudo_ni_jeton_dans_les_messages_de_refus() {
        String jeton = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
        Course complete = course(StatutCourse.EN_PREPARATION, 2);
        Course demarree = course(StatutCourse.EN_COURS, 2);

        Throwable pleine = org.assertj.core.api.Assertions.catchThrowable(() -> complete.verifierPlaceDisponible(2));
        Throwable close = org.assertj.core.api.Assertions.catchThrowable(demarree::autoriserInscription);

        assertThat(pleine).isNotNull();
        assertThat(close).isNotNull();
        assertThat(pleine.getMessage()).doesNotContain("Alice", "Bruno", jeton);
        assertThat(close.getMessage()).doesNotContain("Alice", "Bruno", jeton);
    }

    private static Course course(StatutCourse statut, int nombreMaxParticipants) {
        return Course.reconstituer(ID, "Backyard duo", LocalDate.of(2026, 11, 14), statut,
                new ParametresBoucle(6706, 60, 120), nombreMaxParticipants, 24);
    }
}
