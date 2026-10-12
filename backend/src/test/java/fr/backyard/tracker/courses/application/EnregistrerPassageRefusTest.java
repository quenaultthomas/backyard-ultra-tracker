package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.COMPTE_BRUNO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.INCONNUE;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.LEO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.MARC;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.T0;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.U;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.W;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.X;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.Y;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.Z;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.BenevoleNonAffecteException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.CourseTermineePourPassageException;
import fr.backyard.tracker.courses.domaine.HeureDeDepartInconnueException;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnCourseException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonInconnuException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.PassageDejaEnregistreException;
import fr.backyard.tracker.courses.domaine.PassagePrecedentManquantException;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import fr.backyard.tracker.courses.domaine.TempsDeCourseEcouleException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.4a : cas d'usage EnregistrerPassage, refus et ordre d'évaluation (CA5 ; RG1, RG3, RG5 à RG8,
 * RG10). Chaque refus vérifie qu'aucune écriture n'a lieu. Voir {@link ScenarioPassageDeTest}.
 */
class EnregistrerPassageRefusTest {

    private static final Instant T0_PLUS_30S = T0.plusSeconds(30);
    private static final Instant T0_PLUS_90S = T0.plusSeconds(90);
    private static final Instant T0_PLUS_300S = T0.plusSeconds(300);

    private final ScenarioPassageDeTest s = new ScenarioPassageDeTest();

    // ---------- RG1 : affectation ----------

    @Test
    @DisplayName("CA5 - bénévole non affecté : BenevoleNonAffecteException")
    void doit_refuser_un_benevole_non_affecte_a_la_course() {
        s.assertRefusSansEcriture(() -> scannerPar(MARC, X, s.bruno.jetonQr(), T0_PLUS_30S),
                BenevoleNonAffecteException.class);
    }

    @Test
    @DisplayName("CA5 - Course inconnue : la même BenevoleNonAffecteException (aucune fuite sur l'existence)")
    void doit_refuser_une_course_inconnue_comme_un_benevole_non_affecte() {
        s.assertRefusSansEcriture(() -> scannerPar(LEO, INCONNUE, s.bruno.jetonQr(), T0_PLUS_30S),
                BenevoleNonAffecteException.class);
    }

    @Test
    @DisplayName("CA5 - non affecté et jeton inconnu : BenevoleNonAffecteException (étape 3 avant l'étape 4)")
    void doit_controler_l_affectation_avant_le_jeton() {
        s.assertRefusSansEcriture(() -> scannerPar(MARC, X, JetonQr.depuisSaisie("inconnu"), T0_PLUS_30S),
                BenevoleNonAffecteException.class);
    }

    // ---------- RG3 : jeton ----------

    @Test
    @DisplayName("CA5 - jeton inconnu : JetonInconnuException")
    void doit_refuser_un_jeton_inconnu() {
        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, JetonQr.depuisSaisie("jeton-inexistant"), T0_PLUS_30S),
                JetonInconnuException.class);
    }

    @Test
    @DisplayName("CA5 - jeton d'une Inscription d'une autre Course affectée au même bénévole : JetonInconnuException")
    void doit_refuser_le_jeton_d_une_autre_course() {
        Inscription coureurW = s.inscrire(W, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, coureurW.jetonQr(), T0_PLUS_30S),
                JetonInconnuException.class);
    }

    // ---------- RG6 : états ----------

    @Test
    @DisplayName("CA5 - Course EN_PREPARATION : CourseNonDemarreeException")
    void doit_refuser_un_scan_sur_une_course_en_preparation() {
        Inscription coureur = s.inscrire(Y, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, Y, coureur.jetonQr(), T0_PLUS_30S),
                CourseNonDemarreeException.class);
    }

    @Test
    @DisplayName("CA5 - Course TERMINEE : exception de Course terminée")
    void doit_refuser_un_scan_sur_une_course_terminee() {
        Inscription coureur = s.inscrire(Z, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, Z, coureur.jetonQr(), T0_PLUS_30S),
                CourseTermineePourPassageException.class);
    }

    @Test
    @DisplayName("CA5 - Course EN_COURS sans heure de départ : HeureDeDepartInconnueException")
    void doit_refuser_un_scan_sur_une_course_sans_heure_de_depart() {
        Inscription coureur = s.inscrire(U, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, U, coureur.jetonQr(), T0_PLUS_30S),
                HeureDeDepartInconnueException.class);
    }

    @ParameterizedTest(name = "CA5 - Inscription {0} : InscriptionNonEnCourseException")
    @EnumSource(value = StatutInscription.class, names = {"ABANDON", "VAINQUEUR"})
    void doit_refuser_un_coureur_qui_n_est_plus_en_course(StatutInscription statut) {
        Inscription sorti = s.avecStatut(s.bruno, statut);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, sorti.jetonQr(), T0_PLUS_30S),
                InscriptionNonEnCourseException.class);
    }

    // ---------- RG5, RG7, RG8 : Boucle ----------

    @Test
    @DisplayName("CA5 - jB déjà passé en Boucle 1 : PassageDejaEnregistreException")
    void doit_refuser_un_second_passage_sur_la_meme_boucle() {
        s.passeSur(s.bruno, 1);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, s.bruno.jetonQr(), T0_PLUS_30S),
                PassageDejaEnregistreException.class);
    }

    @Test
    @DisplayName("CA5 - un Passage CORRECTION sur la même Boucle compte comme « déjà passé »")
    void doit_refuser_un_scan_sur_une_boucle_deja_corrigee() {
        s.corrigeSur(s.bruno, 1);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, s.bruno.jetonQr(), T0_PLUS_30S),
                PassageDejaEnregistreException.class);
    }

    @Test
    @DisplayName("CA5 - Clock à t0 + 300 s : TempsDeCourseEcouleException")
    void doit_refuser_un_scan_apres_la_fin_de_la_derniere_boucle() {
        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, s.bruno.jetonQr(), T0_PLUS_300S),
                TempsDeCourseEcouleException.class);
    }

    @Test
    @DisplayName("CA5 - jC sans Passage de Boucle 1 à t0 + 90 s : PassagePrecedentManquantException")
    void doit_refuser_la_boucle_2_sans_passage_sur_la_boucle_1() {
        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, s.chloe.jetonQr(), T0_PLUS_90S),
                PassagePrecedentManquantException.class);
    }

    @Test
    @DisplayName("CA5 - jC avec un Passage CORRECTION en Boucle 1 à t0 + 90 s : accepté en Boucle 2")
    void doit_accepter_la_boucle_2_quand_la_boucle_1_a_un_passage_correction() {
        s.corrigeSur(s.chloe, 1);

        var resultat = scannerPar(LEO, X, s.chloe.jetonQr(), T0_PLUS_90S);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(2);
        assertThat(s.passages.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - Passage de Boucle 1 d'un autre coureur : ne vaut pas pour jC (continuité propre à l'Inscription)")
    void doit_exiger_le_passage_precedent_de_la_meme_inscription() {
        s.passeSur(s.bruno, 1);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, s.chloe.jetonQr(), T0_PLUS_90S),
                PassagePrecedentManquantException.class);
    }

    // ---------- RG10 : ordre d'évaluation ----------

    @Test
    @DisplayName("CA5 - RG10 : jeton inconnu et Course EN_PREPARATION : JetonInconnuException")
    void doit_controler_le_jeton_avant_l_etat_de_la_course() {
        s.inscrire(Y, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, Y, JetonQr.depuisSaisie("inconnu"), T0_PLUS_30S),
                JetonInconnuException.class);
    }

    @Test
    @DisplayName("CA5 - RG10 : Course EN_PREPARATION et Inscription ABANDON : CourseNonDemarreeException")
    void doit_controler_la_course_avant_le_statut_de_l_inscription() {
        Inscription sorti = s.inscrire(Y, COMPTE_BRUNO, 1, StatutInscription.ABANDON);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, Y, sorti.jetonQr(), T0_PLUS_30S),
                CourseNonDemarreeException.class);
    }

    @Test
    @DisplayName("CA5 - RG10 : Inscription ABANDON et déjà passé : InscriptionNonEnCourseException")
    void doit_controler_le_statut_avant_le_deja_passe() {
        s.passeSur(s.bruno, 1);
        Inscription sorti = s.avecStatut(s.bruno, StatutInscription.ABANDON);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, sorti.jetonQr(), T0_PLUS_30S),
                InscriptionNonEnCourseException.class);
    }

    @Test
    @DisplayName("CA5 - RG10 : Inscription ABANDON et temps écoulé : InscriptionNonEnCourseException")
    void doit_controler_le_statut_avant_le_temps_ecoule() {
        Inscription sorti = s.avecStatut(s.bruno, StatutInscription.ABANDON);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, sorti.jetonQr(), T0_PLUS_300S),
                InscriptionNonEnCourseException.class);
    }

    @Test
    @DisplayName("CA5 - RG10 : déjà passé en Boucle 5 et temps écoulé (t0 + 300 s) : TempsDeCourseEcouleException")
    void doit_controler_le_temps_ecoule_avant_le_deja_passe() {
        for (int boucle = 1; boucle <= 5; boucle++) {
            s.passeSur(s.bruno, boucle);
        }

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, s.bruno.jetonQr(), T0_PLUS_300S),
                TempsDeCourseEcouleException.class);
    }

    @Test
    @DisplayName("CA5 - RG10 : déjà passé et Passage précédent manquant (CORRECTION seul en Boucle 2) : PassageDejaEnregistreException")
    void doit_controler_le_deja_passe_avant_la_continuite() {
        s.corrigeSur(s.bruno, 2);

        s.assertRefusSansEcriture(() -> scannerPar(LEO, X, s.bruno.jetonQr(), T0_PLUS_90S),
                PassageDejaEnregistreException.class);
    }

    private EnregistrerPassage.Resultat scannerPar(UUID benevole, UUID course, JetonQr jeton, Instant maintenant) {
        return s.casA(maintenant).executer(course, benevole, jeton, UUID.randomUUID(), 0);
    }
}
