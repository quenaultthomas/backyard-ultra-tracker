package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.DecorGraceDeTest.a;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.PassagePrecedentManquantException;
import fr.backyard.tracker.courses.domaine.TempsDeCourseEcouleException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 7.1 : attribution des scans reçus pendant la grâce (CA5, CA8, CA10 ; RG4, RG5). Signature
 * supposée inchangée : {@code EnregistrerPassage.executer(courseId, benevoleId, jeton, identifiantScan, age)}.
 * Boucles de 60 min, départ 10:00:00Z, bénévole Léo affecté.
 */
class EnregistrerPassageGraceTest {

    @Test
    @DisplayName("CA5 - RG4 : scan d'instant réel 10:59:00 reçu à 11:01:00 (âge 2 min) : Passage SCAN enregistré sur la Boucle 1")
    void doit_enregistrer_sur_la_boucle_de_l_instant_reel_un_scan_recu_pendant_la_grace() {
        var decor = new DecorGraceDeTest(5);
        Inscription b = decor.coureur(2);

        var resultat = decor.scan(a("11:01:00")).executer(DecorGraceDeTest.COURSE, DecorGraceDeTest.LEO,
                b.jetonQr(), UUID.randomUUID(), 120_000);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
        assertThat(resultat.passage().scanneLe()).contains(a("10:59:00"));
        assertThat(decor.passages.passages).hasSize(1);
    }

    @Test
    @DisplayName("CA10 - RG4 : sans Passage sur 1, scan d'instant réel 11:00:30 (Boucle 2) reçu à 11:01:00 : PassagePrecedentManquantException")
    void doit_refuser_un_scan_de_la_boucle_suivante_sans_passage_sur_la_boucle_precedente() {
        var decor = new DecorGraceDeTest(5);
        Inscription b = decor.coureur(2);

        assertThatThrownBy(() -> decor.scan(a("11:01:00")).executer(DecorGraceDeTest.COURSE,
                DecorGraceDeTest.LEO, b.jetonQr(), UUID.randomUUID(), 30_000))
                .isInstanceOf(PassagePrecedentManquantException.class);
        assertThat(decor.passages.passages).isEmpty();
    }

    @Test
    @DisplayName("CA8 - M = 2 : scan d'instant réel 11:59:59.999 reçu à 12:01:00 (âge 60001 ms) : enregistré sur la Boucle 2")
    void doit_accepter_pendant_la_grace_un_scan_fait_juste_avant_la_fin_de_la_derniere_boucle() {
        var decor = new DecorGraceDeTest(2);
        Inscription b = decor.coureur(2, 1);

        var resultat = decor.scan(a("12:01:00")).executer(DecorGraceDeTest.COURSE, DecorGraceDeTest.LEO,
                b.jetonQr(), UUID.randomUUID(), 60_001);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA8 - M = 2 : scan d'instant réel 12:00:00 reçu à 12:01:00 (âge 60 s) : TempsDeCourseEcouleException, aucun Passage")
    void doit_refuser_un_scan_fait_apres_la_fin_de_la_derniere_boucle_meme_pendant_la_grace() {
        var decor = new DecorGraceDeTest(2);
        Inscription b = decor.coureur(2, 1);

        assertThatThrownBy(() -> decor.scan(a("12:01:00")).executer(DecorGraceDeTest.COURSE,
                DecorGraceDeTest.LEO, b.jetonQr(), UUID.randomUUID(), 60_000))
                .isInstanceOf(TempsDeCourseEcouleException.class);
        assertThat(decor.passages.passages).hasSize(1);
    }
}
