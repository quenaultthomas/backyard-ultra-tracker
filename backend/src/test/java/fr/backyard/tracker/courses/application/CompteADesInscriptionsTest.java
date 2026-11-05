package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 9.6a, CA4 : cas d'usage de lecture « ce Compte a-t-il des Inscriptions ? » (RG9), appelé par l'adaptateur
 * d'assemblage du port {@code ConsultationInscriptions}.
 * Signatures attendues : {@code new CompteADesInscriptions(DepotInscriptions)} et
 * {@code boolean aDesInscriptions(UUID compteId)} (noms choisis par le testeur).
 */
class CompteADesInscriptionsTest {

    private static final UUID COURSE_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COURSE_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private final DepotInscriptionsEnMemoireDeTest depot = new DepotInscriptionsEnMemoireDeTest();
    private final CompteADesInscriptions consultation = new CompteADesInscriptions(depot);

    private void inscrire(UUID courseId, UUID compteId, int dossard, int jeton, StatutInscription statut) {
        depot.enregistrer(Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                new JetonQr(String.format("%043d", jeton)), statut));
    }

    @Test
    @DisplayName("CA4 - un Compte avec une Inscription EN_COURSE a des Inscriptions")
    void doit_repondre_vrai_pour_une_inscription_en_course() {
        inscrire(COURSE_1, ALICE, 1, 1, StatutInscription.EN_COURSE);

        assertThat(consultation.aDesInscriptions(ALICE)).isTrue();
    }

    @Test
    @DisplayName("CA4 - une Inscription ABANDON suffit")
    void doit_repondre_vrai_pour_une_inscription_en_abandon() {
        inscrire(COURSE_1, ALICE, 1, 1, StatutInscription.ABANDON);

        assertThat(consultation.aDesInscriptions(ALICE)).isTrue();
    }

    @Test
    @DisplayName("CA4 - une Inscription VAINQUEUR suffit")
    void doit_repondre_vrai_pour_une_inscription_vainqueur() {
        inscrire(COURSE_1, ALICE, 1, 1, StatutInscription.VAINQUEUR);

        assertThat(consultation.aDesInscriptions(ALICE)).isTrue();
    }

    @Test
    @DisplayName("CA4 - plusieurs Inscriptions dans plusieurs Courses : vrai")
    void doit_repondre_vrai_pour_plusieurs_inscriptions_dans_plusieurs_courses() {
        inscrire(COURSE_1, ALICE, 1, 1, StatutInscription.VAINQUEUR);
        inscrire(COURSE_2, ALICE, 1, 2, StatutInscription.EN_COURSE);

        assertThat(consultation.aDesInscriptions(ALICE)).isTrue();
    }

    @Test
    @DisplayName("CA4 - les Inscriptions d'un autre Compte ne comptent pas : faux pour un Compte sans Inscription")
    void doit_repondre_faux_pour_un_compte_sans_inscription_quand_d_autres_en_ont() {
        inscrire(COURSE_1, ALICE, 1, 1, StatutInscription.EN_COURSE);

        assertThat(consultation.aDesInscriptions(BRUNO)).isFalse();
    }

    @Test
    @DisplayName("CA4 - un Compte inconnu n'a pas d'Inscription : faux")
    void doit_repondre_faux_pour_un_compte_inconnu() {
        assertThat(consultation.aDesInscriptions(INCONNU)).isFalse();
    }

    @Test
    @DisplayName("CA4 - la consultation n'écrit rien : les Inscriptions sont inchangées")
    void doit_ne_rien_ecrire() {
        inscrire(COURSE_1, ALICE, 1, 1, StatutInscription.ABANDON);
        List<Inscription> avant = List.copyOf(depot.inscriptions);

        consultation.aDesInscriptions(ALICE);
        consultation.aDesInscriptions(INCONNU);

        assertThat(depot.inscriptions).containsExactlyElementsOf(avant);
        assertThat(depot.nombreDeMisesAJour).isZero();
    }
}
