package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.12 : réintégration d'une Inscription (CA1 ; RG2, RG4).
 *
 * Signatures supposées : {@code Inscription reintegrer()} (style immuable : renvoie l'Inscription EN_COURSE, sans
 * motif ni Boucle d'abandon ; l'Inscription d'origine n'est pas modifiée) ; lève
 * {@code InscriptionNonEnAbandonException} (nouvelle exception du domaine, constructeur {@code (StatutInscription)},
 * accesseur {@code statut()}) pour une Inscription EN_COURSE ou VAINQUEUR.
 */
class InscriptionReintegrationTest {

    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPTE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static Inscription enAbandon(MotifAbandon motif, int boucle) {
        return Inscription.reconstituer(UUID.randomUUID(), COURSE, COMPTE, 3, JetonQr.generer(),
                StatutInscription.ABANDON, motif, boucle);
    }

    @ParameterizedTest(name = "CA1 - RG2/RG4 : abandon {0} réintégré -> EN_COURSE sans motif ni Boucle")
    @EnumSource(MotifAbandon.class)
    @DisplayName("CA1 - RG2/RG4 : quel que soit le motif, l'abandon repasse EN_COURSE et le motif et la Boucle sont effacés")
    void doit_remettre_en_course_quel_que_soit_le_motif(MotifAbandon motif) {
        Inscription abandon = enAbandon(motif, 2);

        Inscription reintegree = abandon.reintegrer();

        assertThat(reintegree.statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(reintegree.estEnCourse()).isTrue();
        assertThat(reintegree.motifAbandon()).isEmpty();
        assertThat(reintegree.boucleAbandon()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - RG4 : identité, dossard et jeton QR sont conservés")
    void doit_conserver_l_identite_de_l_inscription() {
        Inscription abandon = enAbandon(MotifAbandon.HORS_DELAI, 1);

        Inscription reintegree = abandon.reintegrer();

        assertThat(reintegree.id()).isEqualTo(abandon.id());
        assertThat(reintegree.courseId()).isEqualTo(COURSE);
        assertThat(reintegree.compteId()).isEqualTo(COMPTE);
        assertThat(reintegree.dossard()).isEqualTo(3);
        assertThat(reintegree.jetonQr()).isEqualTo(abandon.jetonQr());
    }

    @Test
    @DisplayName("CA1 - l'Inscription d'origine reste en abandon avec son motif et sa Boucle")
    void doit_laisser_l_inscription_d_origine_inchangee() {
        Inscription abandon = enAbandon(MotifAbandon.MANUEL, 2);

        abandon.reintegrer();

        assertThat(abandon.statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(abandon.motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(abandon.boucleAbandon()).contains(2);
    }

    @Test
    @DisplayName("CA1 - RG3 : un abandon enregistré sans motif ni Boucle (données antérieures) est réintégrable")
    void doit_reintegrer_un_abandon_sans_motif_ni_boucle() {
        Inscription abandon = Inscription.reconstituer(UUID.randomUUID(), COURSE, COMPTE, 3, JetonQr.generer(),
                StatutInscription.ABANDON);

        assertThat(abandon.reintegrer().statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @ParameterizedTest(name = "CA1 - RG2 : une Inscription {0} n''est pas réintégrable")
    @EnumSource(value = StatutInscription.class, names = {"EN_COURSE", "VAINQUEUR"})
    @DisplayName("CA1 - RG2 : seule une Inscription en abandon est réintégrable, le statut courant est porté")
    void doit_refuser_la_reintegration_hors_abandon(StatutInscription statut) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), COURSE, COMPTE, 3, JetonQr.generer(),
                statut);

        assertThatThrownBy(inscription::reintegrer)
                .isInstanceOfSatisfying(InscriptionNonEnAbandonException.class,
                        e -> assertThat(e.statut()).isEqualTo(statut));
        assertThat(inscription.statut()).isEqualTo(statut);
    }
}
