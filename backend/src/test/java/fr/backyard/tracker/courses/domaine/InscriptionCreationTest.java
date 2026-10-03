package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.1 : création d'une Inscription et attribution du dossard (CA1 ; RG2, RG3, RG7). */
class InscriptionCreationTest {

    private static final UUID ID_COURSE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ID_ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final JetonQr JETON = new JetonQr("0123456789abcdefghijklmnopqrstuvwxyzABCDEFG");

    @Test
    @DisplayName("CA1 - sans dossard existant, le premier dossard est 1")
    void doit_attribuer_le_dossard_1_quand_aucune_inscription_n_existe() {
        Inscription inscription = Inscription.creer(courseOuverte(), ID_ALICE, Optional.empty(), JETON);

        assertThat(inscription.dossard()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 - avec 7 pour plus grand dossard, le dossard est 8 (les trous éventuels sont ignorés)")
    void doit_attribuer_le_plus_grand_dossard_plus_un_sans_combler_les_trous() {
        Inscription inscription = Inscription.creer(courseOuverte(), ID_ALICE, Optional.of(7), JETON);

        assertThat(inscription.dossard()).isEqualTo(8);
    }

    @Test
    @DisplayName("CA1 - l'inscription créée est EN_COURSE et reprend la Course et le Compte")
    void doit_creer_une_inscription_en_course_liee_a_la_course_et_au_compte() {
        Inscription inscription = Inscription.creer(courseOuverte(), ID_ALICE, Optional.empty(), JETON);

        assertThat(inscription.statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(inscription.courseId()).isEqualTo(ID_COURSE);
        assertThat(inscription.compteId()).isEqualTo(ID_ALICE);
        assertThat(inscription.id()).isNotNull();
    }

    @Test
    @DisplayName("CA1 - le jeton fourni est conservé et son texte est distinct des dossards 1 et 8")
    void doit_conserver_le_jeton_fourni_distinct_du_dossard() {
        Inscription premiere = Inscription.creer(courseOuverte(), ID_ALICE, Optional.empty(), JETON);
        Inscription huitieme = Inscription.creer(courseOuverte(), ID_ALICE, Optional.of(7), JETON);

        assertThat(premiere.jetonQr()).isEqualTo(JETON);
        assertThat(huitieme.jetonQr()).isEqualTo(JETON);
        assertThat(premiere.jetonQr().valeur()).isNotEqualTo("1").isNotEqualTo("8");
        assertThat(huitieme.jetonQr().valeur()).isNotEqualTo("1").isNotEqualTo("8");
    }

    private static Course courseOuverte() {
        return Course.reconstituer(ID_COURSE, "Backyard des Crêtes", LocalDate.of(2026, 11, 14),
                StatutCourse.EN_PREPARATION, new ParametresBoucle(6706, 60, 120), 50, 24);
    }
}
