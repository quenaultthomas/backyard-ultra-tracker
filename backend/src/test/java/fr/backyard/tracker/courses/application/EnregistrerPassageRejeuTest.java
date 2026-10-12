package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.COMPTE_BRUNO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.LEO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.T0;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.W;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.X;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.IdentifiantScanDejaUtiliseException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonInconnuException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.PassageDejaEnregistreException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests de l'incrément 4.4a : cas d'usage EnregistrerPassage, idempotence par identifiant de scan et unicité en base
 * (CA6 ; RG8, RG9). Voir {@link ScenarioPassageDeTest}.
 */
class EnregistrerPassageRejeuTest {

    private static final UUID U1 = UUID.fromString("3b6f0d5e-0f6a-4d7b-9e1c-5a2d8c7e1f40");

    private final ScenarioPassageDeTest s = new ScenarioPassageDeTest();

    private EnregistrerPassage.Resultat premierScan() {
        return s.casA(T0.plusSeconds(30)).executer(X, LEO, s.bruno.jetonQr(), U1, 0);
    }

    private EnregistrerPassage.Resultat rejouer(Instant maintenant) {
        return s.casA(maintenant).executer(X, LEO, s.bruno.jetonQr(), U1, 0);
    }

    private void verifierRejeuIdentiqueSansEcriture(EnregistrerPassage.Resultat origine,
                                                    EnregistrerPassage.Resultat rejeu) {
        assertThat(rejeu.rejeu()).isTrue();
        assertThat(rejeu.passage().id()).isEqualTo(U1);
        assertThat(rejeu.passage().numeroBoucle()).isEqualTo(origine.passage().numeroBoucle());
        assertThat(rejeu.passage().scanneLe()).isEqualTo(origine.passage().scanneLe());
        assertThat(rejeu.dossard()).isEqualTo(1);
        assertThat(rejeu.pseudo()).isEqualTo("Bruno");
        assertThat(s.passages.nombreDEnregistrements).isEqualTo(1);
        assertThat(s.passages.passages).hasSize(1);
    }

    @Test
    @DisplayName("CA6 - même identifiantScan et même jeton : rejeu, Passage existant renvoyé sans écriture")
    void doit_renvoyer_le_passage_existant_pour_un_rejeu() {
        var origine = premierScan();

        var rejeu = rejouer(T0.plusSeconds(35));

        assertThat(origine.rejeu()).isFalse();
        verifierRejeuIdentiqueSansEcriture(origine, rejeu);
    }

    @Test
    @DisplayName("CA6 - rejeu avec le Clock en Boucle 2 : le Passage de la Boucle 1 est renvoyé tel quel")
    void doit_rejouer_apres_le_depart_de_la_boucle_suivante() {
        var origine = premierScan();

        var rejeu = rejouer(T0.plusSeconds(90));

        assertThat(rejeu.passage().numeroBoucle()).isEqualTo(1);
        verifierRejeuIdentiqueSansEcriture(origine, rejeu);
    }

    @Test
    @DisplayName("CA6 - rejeu après la fin du temps de course : Passage renvoyé, pas TempsDeCourseEcoule")
    void doit_rejouer_apres_la_fin_du_temps_de_course() {
        var origine = premierScan();

        var rejeu = rejouer(T0.plusSeconds(300));

        verifierRejeuIdentiqueSansEcriture(origine, rejeu);
    }

    @Test
    @DisplayName("CA6 - rejeu alors que la Course est devenue TERMINEE entre-temps")
    void doit_rejouer_quand_la_course_est_terminee() {
        var origine = premierScan();
        s.remplacerCourse(ScenarioPassageDeTest.course(X, StatutCourse.TERMINEE, 1, T0));

        var rejeu = rejouer(T0.plusSeconds(100));

        verifierRejeuIdentiqueSansEcriture(origine, rejeu);
    }

    @Test
    @DisplayName("CA6 - rejeu alors que l'Inscription est passée en ABANDON entre-temps")
    void doit_rejouer_quand_l_inscription_est_en_abandon() {
        var origine = premierScan();
        s.avecStatut(s.bruno, StatutInscription.ABANDON);

        var rejeu = rejouer(T0.plusSeconds(100));

        verifierRejeuIdentiqueSansEcriture(origine, rejeu);
    }

    @Test
    @DisplayName("CA6 - même identifiantScan avec le jeton jC : IdentifiantScanDejaUtiliseException, sans écriture")
    void doit_refuser_un_identifiant_de_scan_deja_utilise_par_un_autre_coureur() {
        premierScan();

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(35)).executer(X, LEO, s.chloe.jetonQr(), U1, 0),
                IdentifiantScanDejaUtiliseException.class);
    }

    @Test
    @DisplayName("CA6 - identifiantScan d'un Passage d'une autre Course : IdentifiantScanDejaUtiliseException")
    void doit_refuser_un_identifiant_de_scan_deja_utilise_dans_une_autre_course() {
        Inscription coureurW = s.inscrire(W, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        s.casA(T0.plusSeconds(40)).executer(W, LEO, coureurW.jetonQr(), U1, 0);
        int ecrituresAvant = s.passages.nombreDEnregistrements;

        assertThatThrownBy(() -> s.casA(T0.plusSeconds(45)).executer(X, LEO, s.bruno.jetonQr(), U1, 0))
                .isInstanceOf(IdentifiantScanDejaUtiliseException.class);

        assertThat(s.passages.nombreDEnregistrements).isEqualTo(ecrituresAvant);
        assertThat(s.passages.passages).hasSize(1);
    }

    @Test
    @DisplayName("CA6 - RG10 : jeton inconnu avec un identifiantScan existant : JetonInconnuException (étape 4 avant 5)")
    void doit_controler_le_jeton_avant_le_rejeu() {
        premierScan();

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(35)).executer(X, LEO, JetonQr.depuisSaisie("inconnu"), U1, 0),
                JetonInconnuException.class);
    }

    @Test
    @DisplayName("CA6 - identifiantScan nouveau pour jB en Boucle 1 déjà passée : PassageDejaEnregistreException, pas un rejeu")
    void doit_refuser_un_autre_scan_sur_la_meme_boucle() {
        premierScan();

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(35)).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 0),
                PassageDejaEnregistreException.class);
    }

    @Test
    @DisplayName("CA6 - le dépôt lève PassageDejaEnregistreException à l'écriture (deux scans simultanés) : l'exception métier remonte")
    void doit_laisser_remonter_l_exception_metier_du_depot_lors_d_une_course_entre_deux_scans() {
        PassageDejaEnregistreException duDepot = Mockito.mock(PassageDejaEnregistreException.class);
        s.passages.echecAEnregistrer = duDepot;

        assertThatThrownBy(() -> s.scannerSurX(T0.plusSeconds(30), s.bruno))
                .isInstanceOf(PassageDejaEnregistreException.class);

        assertThat(s.passages.passages).isEmpty();
    }
}
