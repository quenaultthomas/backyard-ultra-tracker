package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.9 : transition vers VAINQUEUR (CA2 ; RG1, RG5).
 *
 * Signature supposée (spec §4 « Port interne », par symétrie avec {@code abandonner}) :
 * {@code Inscription Inscription.devenirVainqueur()} renvoie l'Inscription en VAINQUEUR sans affecter l'instance
 * courante ; lève {@link InscriptionNonEnCourseException} (avec {@code statut()}) si elle n'est pas EN_COURSE.
 */
class InscriptionVainqueurTest {

    private static final UUID INSCRIPTION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPTE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static Inscription enCourse() {
        return Inscription.reconstituer(INSCRIPTION, COURSE, COMPTE, 7, JetonQr.generer(), StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA2 - RG5 : une inscription EN_COURSE devient VAINQUEUR, sans motif ni boucle d'abandon")
    void doit_devenir_vainqueur_sans_motif_ni_boucle_d_abandon() {
        Inscription vainqueur = enCourse().devenirVainqueur();

        assertThat(vainqueur.statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(vainqueur.motifAbandon()).isEmpty();
        assertThat(vainqueur.boucleAbandon()).isEmpty();
        assertThat(vainqueur.estEnCourse()).isFalse();
    }

    @Test
    @DisplayName("CA2 - RG5 : identifiants, dossard et jeton QR sont inchangés par la victoire")
    void doit_conserver_identifiants_dossard_et_jeton() {
        Inscription avant = enCourse();

        Inscription vainqueur = avant.devenirVainqueur();

        assertThat(vainqueur.id()).isEqualTo(INSCRIPTION);
        assertThat(vainqueur.courseId()).isEqualTo(COURSE);
        assertThat(vainqueur.compteId()).isEqualTo(COMPTE);
        assertThat(vainqueur.dossard()).isEqualTo(7);
        assertThat(vainqueur.jetonQr()).isEqualTo(avant.jetonQr());
    }

    @Test
    @DisplayName("CA2 : l'inscription d'origine n'est pas affectée (reste EN_COURSE)")
    void doit_laisser_l_inscription_d_origine_inchangee() {
        Inscription avant = enCourse();

        avant.devenirVainqueur();

        assertThat(avant.statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA2 : une inscription ABANDON ne peut pas devenir vainqueur, rien ne change")
    void doit_refuser_la_victoire_d_une_inscription_en_abandon() {
        Inscription abandon = enCourse().abandonner(MotifAbandon.HORS_DELAI, 2);

        assertThatThrownBy(abandon::devenirVainqueur)
                .isInstanceOfSatisfying(InscriptionNonEnCourseException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.ABANDON));
        assertThat(abandon.statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(abandon.motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(abandon.boucleAbandon()).contains(2);
    }

    @Test
    @DisplayName("CA2 : une inscription déjà VAINQUEUR ne peut pas le redevenir, rien ne change")
    void doit_refuser_la_victoire_d_un_vainqueur() {
        Inscription vainqueur = enCourse().devenirVainqueur();

        assertThatThrownBy(vainqueur::devenirVainqueur)
                .isInstanceOfSatisfying(InscriptionNonEnCourseException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.VAINQUEUR));
        assertThat(vainqueur.statut()).isEqualTo(StatutInscription.VAINQUEUR);
    }

    @Test
    @DisplayName("CA2 : toString d'un vainqueur ne montre ni le jeton QR ni l'identifiant du Compte")
    void doit_masquer_jeton_et_compte_dans_to_string() {
        Inscription vainqueur = enCourse().devenirVainqueur();

        assertThat(vainqueur.toString())
                .doesNotContain(vainqueur.jetonQr().valeur())
                .doesNotContain(COMPTE.toString());
    }
}
