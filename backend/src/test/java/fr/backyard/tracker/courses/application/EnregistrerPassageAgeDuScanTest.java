package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.COMPTE_BRUNO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.LEO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.MARC;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.T0;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.X;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.Y;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.Z;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.BenevoleNonAffecteException;
import fr.backyard.tracker.courses.domaine.IdentifiantScanDejaUtiliseException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonInconnuException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ScanTropAncienException;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.3, CA1 et CA2 (RG5) : borne de 24 h de l'âge d'un scan dans EnregistrerPassage, après le rejeu et avant
 * Course/Inscription/Boucle. Voir {@link ScenarioPassageDeTest}. Exception attendue :
 * {@code fr.backyard.tracker.courses.domaine.ScanTropAncienException}.
 */
class EnregistrerPassageAgeDuScanTest {

    private static final long TROP_ANCIEN = 86_400_001L;
    private static final long TRES_ANCIEN = 90_000_000L;
    private static final UUID S = UUID.fromString("5c0e1b7a-3d0f-4c52-8a55-0f1d2e3a4b5c");

    private final ScenarioPassageDeTest s = new ScenarioPassageDeTest();

    @Test
    @DisplayName("CA1 - âge de 86 400 001 ms : ScanTropAncienException et aucun Passage enregistré")
    void doit_refuser_un_scan_de_plus_de_vingt_quatre_heures_sans_rien_ecrire() {
        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(30)).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), TROP_ANCIEN),
                ScanTropAncienException.class);

        assertThat(s.passages.passages).isEmpty();
    }

    @Test
    @DisplayName("CA1 - âge de 86 400 000 ms (borne incluse) : jamais refusé pour son âge")
    void doit_ne_pas_refuser_pour_son_age_un_scan_d_exactement_vingt_quatre_heures() {
        assertThatThrownBy(() -> s.casA(T0.plusSeconds(30))
                .executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 86_400_000L))
                .isNotInstanceOf(ScanTropAncienException.class);
    }

    @Test
    @DisplayName("CA2 - rejeu du scan S pour la même Inscription avec un âge de 90 000 000 ms : rejeu, sans exception")
    void doit_rejouer_un_scan_deja_enregistre_quel_que_soit_l_age() {
        s.casA(T0.plusSeconds(30)).executer(X, LEO, s.bruno.jetonQr(), S, 0);

        var rejeu = s.casA(T0.plusSeconds(35)).executer(X, LEO, s.bruno.jetonQr(), S, TRES_ANCIEN);

        assertThat(rejeu.rejeu()).isTrue();
        assertThat(rejeu.passage().id()).isEqualTo(S);
        assertThat(s.passages.passages).hasSize(1);
    }

    @Test
    @DisplayName("CA2 - identifiant S d'une autre Inscription, âge 90 000 000 ms : IdentifiantScanDejaUtiliseException")
    void doit_refuser_un_identifiant_utilise_par_une_autre_inscription_meme_trop_ancien() {
        s.casA(T0.plusSeconds(30)).executer(X, LEO, s.bruno.jetonQr(), S, 0);

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(35)).executer(X, LEO, s.chloe.jetonQr(), S, TRES_ANCIEN),
                IdentifiantScanDejaUtiliseException.class);
    }

    @Test
    @DisplayName("CA2 - jeton inconnu, âge 90 000 000 ms : JetonInconnuException (contrôlé avant l'âge)")
    void doit_controler_le_jeton_avant_l_age() {
        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(30))
                        .executer(X, LEO, JetonQr.depuisSaisie("inconnu"), UUID.randomUUID(), TRES_ANCIEN),
                JetonInconnuException.class);
    }

    @Test
    @DisplayName("CA2 - Course TERMINEE, identifiant neuf, âge 90 000 000 ms : ScanTropAncienException, pas CourseTerminee")
    void doit_refuser_pour_son_age_avant_de_constater_une_course_terminee() {
        Inscription coureur = s.inscrire(Z, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(100)).executer(Z, LEO, coureur.jetonQr(), UUID.randomUUID(), TRES_ANCIEN),
                ScanTropAncienException.class);
    }

    @Test
    @DisplayName("CA2 - Course non démarrée, identifiant neuf, âge 90 000 000 ms : ScanTropAncienException")
    void doit_refuser_pour_son_age_avant_de_constater_une_course_non_demarree() {
        Inscription coureur = s.inscrire(Y, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(100)).executer(Y, LEO, coureur.jetonQr(), UUID.randomUUID(), TRES_ANCIEN),
                ScanTropAncienException.class);
    }

    @Test
    @DisplayName("CA2 - bénévole non affecté, âge 90 000 000 ms : BenevoleNonAffecteException (prioritaire sur l'âge)")
    void doit_controler_l_affectation_avant_l_age() {
        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(30))
                        .executer(X, MARC, s.bruno.jetonQr(), UUID.randomUUID(), TRES_ANCIEN),
                BenevoleNonAffecteException.class);
    }
}
