package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.COMPTE_BRUNO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.LEO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.T0;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.W;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.X;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.OriginePassage;
import fr.backyard.tracker.courses.domaine.ScanAnterieurAuDepartException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.4a : cas d'usage EnregistrerPassage, scans acceptés (CA4 ; RG4, RG5, RG12, RG19).
 * Voir {@link ScenarioPassageDeTest} pour les signatures attendues.
 */
class EnregistrerPassageTest {

    private final ScenarioPassageDeTest s = new ScenarioPassageDeTest();

    @Test
    @DisplayName("CA4 - scan de jB à t0 + 30 s : Passage SCAN de la Boucle 1, scanneLe = t0 + 30 s, identifiant de scan conservé, dossard 1 et pseudo de l'annuaire")
    void doit_creer_un_passage_de_la_boucle_1_pour_un_scan_en_direct() {
        UUID identifiantScan = UUID.fromString("3b6f0d5e-0f6a-4d7b-9e1c-5a2d8c7e1f40");

        var resultat = s.casA(T0.plusSeconds(30)).executer(X, LEO, s.bruno.jetonQr(), identifiantScan, 0);

        assertThat(resultat.rejeu()).isFalse();
        assertThat(resultat.dossard()).isEqualTo(1);
        assertThat(resultat.pseudo()).isEqualTo("Bruno");
        assertThat(resultat.passage().id()).isEqualTo(identifiantScan);
        assertThat(resultat.passage().inscriptionId()).isEqualTo(s.bruno.id());
        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
        assertThat(resultat.passage().scanneLe()).contains(T0.plusSeconds(30));
        assertThat(resultat.passage().origine()).isEqualTo(OriginePassage.SCAN);
    }

    @Test
    @DisplayName("CA4 - le Passage renvoyé est celui enregistré dans le dépôt (une seule écriture)")
    void doit_enregistrer_le_passage_une_seule_fois() {
        var resultat = s.scannerSurX(T0.plusSeconds(30), s.bruno);

        assertThat(s.passages.nombreDEnregistrements).isEqualTo(1);
        assertThat(s.passages.parId(resultat.passage().id())).containsSame(resultat.passage());
    }

    @Test
    @DisplayName("CA4 - jB à t0 + 90 s avec un Passage de Boucle 1 présent : Passage de la Boucle 2")
    void doit_creer_la_boucle_2_quand_la_boucle_1_est_passee() {
        s.passeSur(s.bruno, 1);

        var resultat = s.scannerSurX(T0.plusSeconds(90), s.bruno);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(2);
        assertThat(resultat.rejeu()).isFalse();
    }

    @Test
    @DisplayName("CA4 - ageDuScan 40 000 ms avec Clock à t0 + 70 s : Boucle 1 et scanneLe = t0 + 30 s (la Boucle vient de scanneLe)")
    void doit_placer_le_passage_sur_la_boucle_du_scan_et_non_celle_de_la_reception() {
        var resultat = s.casA(T0.plusSeconds(70)).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 40_000);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
        assertThat(resultat.passage().scanneLe()).contains(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("CA4 - ageDuScan plaçant le scan avant t0 : ScanAnterieurAuDepartException, rien n'est écrit")
    void doit_refuser_un_scan_dont_l_age_le_place_avant_le_depart() {
        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(10)).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 20_000),
                ScanAnterieurAuDepartException.class);
    }

    @Test
    @DisplayName("CA4 - scanneLe est tronqué à la milliseconde")
    void doit_tronquer_scanne_le_a_la_milliseconde() {
        Instant maintenant = Instant.parse("2026-10-12T14:03:57.123456789Z");

        var resultat = s.scannerSurX(maintenant, s.bruno);

        assertThat(resultat.passage().scanneLe()).contains(Instant.parse("2026-10-12T14:03:57.123Z"));
    }

    @Test
    @DisplayName("CA4 - le pseudo « Coureur anonyme » de l'annuaire est restitué tel quel")
    void doit_restituer_le_pseudo_coureur_anonyme_tel_quel() {
        s.annuaire.pseudos.put(ScenarioPassageDeTest.COMPTE_CHLOE, "Coureur anonyme");

        var resultat = s.scannerSurX(T0.plusSeconds(30), s.chloe);

        assertThat(resultat.dossard()).isEqualTo(2);
        assertThat(resultat.pseudo()).isEqualTo("Coureur anonyme");
    }

    @Test
    @DisplayName("CA4 - un Compte non résolu par l'annuaire donne le pseudo « Compte inconnu » (RG12)")
    void doit_utiliser_compte_inconnu_quand_l_annuaire_ne_resout_pas_le_pseudo() {
        s.annuaire.pseudos.remove(COMPTE_BRUNO);

        var resultat = s.scannerSurX(T0.plusSeconds(30), s.bruno);

        assertThat(resultat.pseudo()).isEqualTo("Compte inconnu");
    }

    @Test
    @DisplayName("CA4 - le Clock n'est lu qu'une fois : scanneLe vient de cette unique lecture")
    void doit_lire_le_clock_une_seule_fois() {
        var clock = new ScenarioPassageDeTest.ClockAvanceALaLecture(T0.plusSeconds(30), Duration.ofSeconds(7));

        var resultat = s.casAvec(clock).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 0);

        assertThat(clock.lectures.get()).isEqualTo(1);
        assertThat(resultat.passage().scanneLe()).contains(T0.plusSeconds(30));
        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - le scan ne change ni le statut de l'Inscription ni la Course (RG6) et n'écrit que le Passage")
    void doit_ne_modifier_ni_l_inscription_ni_la_course() {
        Inscription avant = s.bruno;

        s.scannerSurX(T0.plusSeconds(30), s.bruno);

        assertThat(s.inscriptions.inscriptions).containsExactly(avant, s.chloe);
        assertThat(s.inscriptions.inscriptions).extracting(Inscription::statut)
                .containsOnly(StatutInscription.EN_COURSE);
        assertThat(s.courses.nombreDEnregistrements).isZero();
        assertThat(s.courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA4 - RG19 : au même Clock, X (boucles de 1 min) et W (2 min, autre départ) donnent des Boucles indépendantes")
    void doit_calculer_la_boucle_de_chaque_course_independamment() {
        Inscription coureurW = s.inscrire(W, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        s.passeSur(s.bruno, 1);
        Instant maintenant = T0.plusSeconds(90);

        var surX = s.casA(maintenant).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 0);
        var surW = s.casA(maintenant).executer(W, LEO, coureurW.jetonQr(), UUID.randomUUID(), 0);

        assertThat(surX.passage().numeroBoucle()).isEqualTo(2);
        assertThat(surW.passage().numeroBoucle()).isEqualTo(1);
    }
}
