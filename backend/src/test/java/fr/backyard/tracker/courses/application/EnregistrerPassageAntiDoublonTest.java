package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.COMPTE_BRUNO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.LEO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.T0;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.X;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.course;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.CoureurScanne;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnCourseException;
import fr.backyard.tracker.courses.domaine.PassageDejaEnregistreException;
import fr.backyard.tracker.courses.domaine.PassagePrecedentManquantException;
import fr.backyard.tracker.courses.domaine.PassageTropRapprocheException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import fr.backyard.tracker.courses.domaine.TempsDeCourseEcouleException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.6 : cas d'usage EnregistrerPassage et anti-doublon (CA3 ; RG1, RG4, RG15). La Course X du
 * scénario de 4.4a est remplacée par une Course de 60 min (d = 2 min), M = 5, départ à t0 ; Bruno (dossard 1) et
 * Chloé (dossard 2) sont EN_COURSE. Voir {@link ScenarioPassageDeTest}.
 *
 * Signatures attendues : celles de 4.4a, inchangées ; refus {@code PassageTropRapprocheException} (extends
 * {@code RefusPassageCoureurException}) avec {@code delaiMinimal()}, {@code secondesRestantes()},
 * {@code numeroBoucle()} et {@code coureur()} renseigné par le cas d'usage ; nouvelle lecture du port
 * {@code DepotPassages.parInscriptionEtBoucle(UUID inscriptionId, int numeroBoucle): Optional<Passage>}
 * (le double {@code DepotPassagesEnMemoireDeTest} doit l'implémenter : ajout hors périmètre de ce fichier).
 */
class EnregistrerPassageAntiDoublonTest {

    private static final Duration SOIXANTE_MINUTES = Duration.ofMinutes(60);
    private static final Instant PASSAGE_BOUCLE_1 = T0.plus(Duration.ofMinutes(59).plusSeconds(50));
    private static final Instant BOUCLE_2_PLUS_5S = T0.plus(SOIXANTE_MINUTES).plusSeconds(5);
    private static final UUID V = id(6);
    private static final UUID IDENTIFIANT_PASSAGE_BOUCLE_1 = UUID.fromString("3b6f0d5e-0f6a-4d7b-9e1c-5a2d8c7e1f41");

    private final ScenarioPassageDeTest s = new ScenarioPassageDeTest();

    @BeforeEach
    void courseXDeSoixanteMinutes() {
        s.remplacerCourse(course(X, StatutCourse.EN_COURS, 60, T0));
    }

    // ---------- CA3 : refus ----------

    @Test
    @DisplayName("CA3 - Passage de Boucle 1 à t0 + 59 min 50 s, nouvel identifiant à t0 + 60 min 5 s : PassageTropRapprocheException, aucune écriture")
    void doit_refuser_un_nouveau_scan_trop_rapproche_sans_rien_ecrire() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);

        s.assertRefusSansEcriture(() -> scannerSurX(BOUCLE_2_PLUS_5S, s.bruno), PassageTropRapprocheException.class);
    }

    @Test
    @DisplayName("CA3 - le refus porte le délai (120 s), les secondes restantes (105), la Boucle visée (2) et le coureur (dossard 1, Bruno)")
    void doit_rapporter_le_refus_au_coureur_scanne_avec_le_delai_restant() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);

        assertThatThrownBy(() -> scannerSurX(BOUCLE_2_PLUS_5S, s.bruno))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class, e -> {
                    assertThat(e.delaiMinimal()).isEqualTo(Duration.ofSeconds(120));
                    assertThat(e.secondesRestantes()).isEqualTo(105);
                    assertThat(e.numeroBoucle()).isEqualTo(2);
                    assertThat(e.coureur()).contains(new CoureurScanne(1, "Bruno"));
                });
    }

    @Test
    @DisplayName("CA3 - un refus n'écrit rien : le scan suivant, avec un nouvel identifiant, est évalué à neuf et accepté une fois le délai écoulé")
    void doit_evaluer_a_neuf_le_scan_suivant_apres_un_refus() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);
        assertThatThrownBy(() -> scannerSurX(BOUCLE_2_PLUS_5S, s.bruno))
                .isInstanceOf(PassageTropRapprocheException.class);

        var resultat = scannerSurX(PASSAGE_BOUCLE_1.plusSeconds(120), s.bruno);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(2);
        assertThat(resultat.rejeu()).isFalse();
        assertThat(s.passages.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3 - exactement 120 s après le Passage de Boucle 1 : accepté en Boucle 2")
    void doit_accepter_un_scan_exactement_au_delai_minimal() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);

        var resultat = scannerSurX(PASSAGE_BOUCLE_1.plusSeconds(120), s.bruno);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - une milliseconde avant le délai : PassageTropRapprocheException, 1 seconde restante")
    void doit_refuser_un_scan_a_une_milliseconde_du_delai_minimal() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);

        assertThatThrownBy(() -> scannerSurX(PASSAGE_BOUCLE_1.plusMillis(119_999), s.bruno))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class,
                        e -> assertThat(e.secondesRestantes()).isEqualTo(1));
    }

    @Test
    @DisplayName("CA3 - un Passage de Boucle 1 de CORRECTION : scan en Boucle 2 accepté sans contrôle d'anti-doublon")
    void doit_accepter_la_boucle_2_quand_le_passage_precedent_est_une_correction() {
        s.corrigeSur(s.chloe, 1);

        var resultat = scannerSurX(BOUCLE_2_PLUS_5S, s.chloe);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - Boucle 1 : jamais d'anti-doublon, le premier scan juste après le départ est accepté")
    void doit_accepter_le_premier_scan_en_boucle_1() {
        var resultat = scannerSurX(T0.plusSeconds(1), s.bruno);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
    }

    // ---------- CA3 : rejeu (RG4) ----------

    @Test
    @DisplayName("CA3 - même identifiantScan que le Passage de Boucle 1, dans la fenêtre du délai : rejeu renvoyé, sans exception ni écriture")
    void doit_renvoyer_le_rejeu_meme_dans_la_fenetre_du_delai() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1, IDENTIFIANT_PASSAGE_BOUCLE_1);

        var resultat = s.casA(BOUCLE_2_PLUS_5S).executer(X, LEO, s.bruno.jetonQr(), IDENTIFIANT_PASSAGE_BOUCLE_1, 0);

        assertThat(resultat.rejeu()).isTrue();
        assertThat(resultat.passage().id()).isEqualTo(IDENTIFIANT_PASSAGE_BOUCLE_1);
        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
        assertThat(s.passages.nombreDEnregistrements).isZero();
        assertThat(s.passages.passages).hasSize(1);
    }

    // ---------- CA3 : âge du scan (RG2) ----------

    @Test
    @DisplayName("CA3 - ageDuScanMillisecondes 10 000 avec Clock à t0 + 62 min : scanneLe = t0 + 61 min 50 s, écart de 2 min, accepté")
    void doit_evaluer_l_ecart_sur_l_instant_du_scan_deduit_de_l_age() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);
        Instant maintenant = T0.plus(Duration.ofMinutes(62));

        var resultat = s.casA(maintenant).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 10_000);

        assertThat(resultat.passage().scanneLe()).contains(T0.plus(Duration.ofMinutes(61)).plusSeconds(50));
        assertThat(resultat.passage().numeroBoucle()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - l'âge du scan est déduit avant le contrôle : Clock à t0 + 61 min 50 s et âge de 10 s (écart 110 s) refusé, alors que le même scan sans âge (écart 120 s) est accepté")
    void doit_refuser_un_scan_dont_l_age_le_rapproche_du_passage_precedent() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);
        Instant maintenant = PASSAGE_BOUCLE_1.plusSeconds(120);

        assertThatThrownBy(() -> s.casA(maintenant).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 10_000))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class,
                        e -> assertThat(e.secondesRestantes()).isEqualTo(10));
        assertThat(s.passages.nombreDEnregistrements).isZero();

        var sansAge = s.casA(maintenant).executer(X, LEO, s.bruno.jetonQr(), UUID.randomUUID(), 0);

        assertThat(sansAge.passage().numeroBoucle()).isEqualTo(2);
    }

    // ---------- CA3 : ordre d'évaluation (RG4) ----------

    @Test
    @DisplayName("CA3 - Passage de Boucle 1 manquant : PassagePrecedentManquantException, avant l'anti-doublon")
    void doit_controler_la_continuite_avant_l_anti_doublon() {
        s.assertRefusSansEcriture(() -> scannerSurX(BOUCLE_2_PLUS_5S, s.chloe), PassagePrecedentManquantException.class);
    }

    @Test
    @DisplayName("CA3 - scan de la même Boucle dans la fenêtre du délai : PassageDejaEnregistreException, pas PassageTropRapproche")
    void doit_controler_le_deja_passe_avant_l_anti_doublon() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);
        passeSurBoucle(s.bruno, 2, T0.plus(SOIXANTE_MINUTES).plusSeconds(1));

        s.assertRefusSansEcriture(() -> scannerSurX(BOUCLE_2_PLUS_5S, s.bruno), PassageDejaEnregistreException.class);
    }

    @Test
    @DisplayName("CA3 - Clock à t0 + 5 x 60 min, 10 s après le Passage de Boucle 4 : TempsDeCourseEcouleException, avant l'anti-doublon")
    void doit_controler_le_temps_ecoule_avant_l_anti_doublon() {
        passeSurBoucle(s.bruno, 4, T0.plus(SOIXANTE_MINUTES.multipliedBy(5)).minusSeconds(10));

        s.assertRefusSansEcriture(() -> scannerSurX(T0.plus(SOIXANTE_MINUTES.multipliedBy(5)), s.bruno),
                TempsDeCourseEcouleException.class);
    }

    @Test
    @DisplayName("CA3 - Inscription ABANDON dans la fenêtre du délai : InscriptionNonEnCourseException, avant l'anti-doublon")
    void doit_controler_le_statut_avant_l_anti_doublon() {
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);
        Inscription sorti = s.avecStatut(s.bruno, StatutInscription.ABANDON);

        s.assertRefusSansEcriture(() -> scannerSurX(BOUCLE_2_PLUS_5S, sorti), InscriptionNonEnCourseException.class);
    }

    // ---------- CA3 : isolation entre Courses (RG15) ----------

    @Test
    @DisplayName("CA3 - au même Clock et même écart de 15 s, la Course de 60 min refuse (d = 2 min) et la Course de 1 min accepte (d = 15 s)")
    void doit_decider_independamment_pour_deux_courses_au_meme_clock() {
        // V : Boucles de 1 min démarrées à t0 + 59 min, donc BOUCLE_2_PLUS_5S est son scan de Boucle 2.
        s.remplacerCourse(course(V, StatutCourse.EN_COURS, 1, T0.plus(Duration.ofMinutes(59))));
        Inscription coureurV = s.inscrire(V, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        passeSurBoucle1(s.bruno, PASSAGE_BOUCLE_1);
        passeSurBoucle1(coureurV, PASSAGE_BOUCLE_1);

        assertThatThrownBy(() -> scannerSurX(BOUCLE_2_PLUS_5S, s.bruno))
                .isInstanceOf(PassageTropRapprocheException.class);
        var surV = s.casA(BOUCLE_2_PLUS_5S).executer(V, LEO, coureurV.jetonQr(), UUID.randomUUID(), 0);

        assertThat(surV.passage().numeroBoucle()).isEqualTo(2);
    }

    // ---------- outils ----------

    private void passeSurBoucle1(Inscription inscription, Instant scanneLe) {
        passeSurBoucle(inscription, 1, scanneLe);
    }

    private void passeSurBoucle1(Inscription inscription, Instant scanneLe, UUID identifiant) {
        s.passages.poser(fr.backyard.tracker.courses.domaine.Passage.scanner(identifiant, inscription, 1, scanneLe));
    }

    private void passeSurBoucle(Inscription inscription, int boucle, Instant scanneLe) {
        s.passages.poser(fr.backyard.tracker.courses.domaine.Passage.scanner(UUID.randomUUID(), inscription, boucle,
                scanneLe));
    }

    private EnregistrerPassage.Resultat scannerSurX(Instant maintenant, Inscription coureur) {
        return s.scannerSurX(maintenant, coureur);
    }
}
