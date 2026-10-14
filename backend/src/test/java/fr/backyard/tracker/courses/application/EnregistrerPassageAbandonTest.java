package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.LEO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.T0;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.X;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnCourseException;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.8 : EnregistrerPassage face à l'abandon automatique (CA5 ; RG10, RG11).
 * Signatures supposées : celles de 4.4a (voir {@link ScenarioPassageDeTest}) ; la Course est chargée par
 * {@code DepotCourses.parIdPourModification} (le double DepotCoursesDeTest compte et journalise les chargements) ;
 * {@code Inscription.abandonner(MotifAbandon, int)} renvoie l'Inscription en ABANDON.
 * Course X : boucles de 1 min, t0 = 14:03:27Z ; Bruno (dossard 1), Chloé (2).
 */
class EnregistrerPassageAbandonTest {

    private static final Instant T0_PLUS_60_300 = T0.plusMillis(60_300);
    private static final UUID SCAN_DE_BRUNO = UUID.fromString("3b6f0d5e-0f6a-4d7b-9e1c-5a2d8c7e1f41");

    private final ScenarioPassageDeTest s = new ScenarioPassageDeTest();

    private void abandonnerHorsDelai(Inscription inscription, int boucle) {
        Inscription abandon = inscription.abandonner(MotifAbandon.HORS_DELAI, boucle);
        s.inscriptions.inscriptions.replaceAll(i -> i.id().equals(inscription.id()) ? abandon : i);
    }

    @Test
    @DisplayName("CA5 - RG10 : la Course est chargée sous verrou (parIdPourModification), jamais par parId, avant l'Inscription")
    void doit_charger_la_course_sous_verrou_avant_de_lire_l_inscription() {
        List<String> journal = new ArrayList<>();
        s.courses.journalDesLectures = journal;
        s.inscriptions.journalDesLectures = journal;

        s.scannerSurX(T0.plusSeconds(30), s.bruno);

        assertThat(s.courses.nombreDeChargementsPourModification).isEqualTo(1);
        assertThat(s.courses.nombreDeChargementsParId).isZero();
        assertThat(journal).containsExactly("course-verrouillee", "inscription");
    }

    @Test
    @DisplayName("CA5 - RG11 : Inscription ABANDON (HORS_DELAI, 1) : InscriptionNonEnCourseException (ABANDON), aucun Passage écrit")
    void doit_refuser_le_scan_d_un_coureur_mis_en_abandon_automatiquement() {
        abandonnerHorsDelai(s.chloe, 1);

        assertThatThrownBy(() -> s.scannerSurX(T0.plusSeconds(90), s.chloe))
                .isInstanceOfSatisfying(InscriptionNonEnCourseException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.ABANDON));
        assertThat(s.passages.passages).isEmpty();
        assertThat(s.passages.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA5 - RG11 : rejeu de l'identifiantScan d'un Passage existant d'un coureur depuis passé en ABANDON : rejeu renvoyé sans exception")
    void doit_rejouer_un_passage_existant_meme_si_le_coureur_est_depuis_en_abandon() {
        s.casA(T0.plusSeconds(30)).executer(X, LEO, s.bruno.jetonQr(), SCAN_DE_BRUNO, 0);
        abandonnerHorsDelai(s.bruno, 2);

        var rejeu = s.casA(T0.plusSeconds(200)).executer(X, LEO, s.bruno.jetonQr(), SCAN_DE_BRUNO, 0);

        assertThat(rejeu.rejeu()).isTrue();
        assertThat(rejeu.passage().id()).isEqualTo(SCAN_DE_BRUNO);
        assertThat(rejeu.passage().numeroBoucle()).isEqualTo(1);
        assertThat(s.passages.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - RG10 a : coureur sans Passage 1 encore EN_COURSE, scan de t0 + 59,8 s validé à t0 + 60,3 s : Passage boucle 1 créé")
    void doit_accepter_un_scan_de_la_boucle_precedente_valide_avant_l_abandon() {
        var resultat = s.casA(T0_PLUS_60_300).executer(X, LEO, s.chloe.jetonQr(), UUID.randomUUID(), 500);

        assertThat(resultat.rejeu()).isFalse();
        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
        assertThat(resultat.passage().scanneLe()).contains(T0.plusMillis(59_800));
        assertThat(s.passages.passages).hasSize(1);
    }

    @Test
    @DisplayName("CA5 - RG10 b : le même scan validé après l'abandon est refusé (pas de délai de grâce), aucun Passage")
    void doit_refuser_le_meme_scan_une_fois_le_coureur_abandonne() {
        abandonnerHorsDelai(s.chloe, 1);

        s.assertRefusSansEcriture(
                () -> s.casA(T0_PLUS_60_300).executer(X, LEO, s.chloe.jetonQr(), UUID.randomUUID(), 500),
                InscriptionNonEnCourseException.class);
    }
}
