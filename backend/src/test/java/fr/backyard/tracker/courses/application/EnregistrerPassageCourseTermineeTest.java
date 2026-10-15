package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.COMPTE_BRUNO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.COMPTE_CHLOE;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.LEO;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.T0;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.X;
import static fr.backyard.tracker.courses.application.ScenarioPassageDeTest.Z;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.CourseTermineePourPassageException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnCourseException;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.9 (adaptés à 4.10 : {@code Resultat.vainqueurs()} entier) : effet sur le scan d'une Course terminée par le contrôle (CA4 ; RG9). Décor de
 * {@link ScenarioPassageDeTest} : Z est TERMINEE (boucles de 1 min, t0 = 14:03:27Z), Léo y est affecté ; X est EN_COURS
 * avec Bruno (1) et Chloé (2). Signatures supposées : celles de 4.4a ; le refus d'une Course terminée reste
 * {@link CourseTermineePourPassageException} (409 COURSE_TERMINEE), évalué avant l'état de l'Inscription.
 */
class EnregistrerPassageCourseTermineeTest {

    private static final UUID COMPTE_TOM = ScenarioPassageDeTest.id(0xb3);
    private static final UUID SCAN_DE_BRUNO = UUID.fromString("3b6f0d5e-0f6a-4d7b-9e1c-5a2d8c7e1f42");

    private final ScenarioPassageDeTest s = new ScenarioPassageDeTest();

    @ParameterizedTest(name = "CA4 - RG9 : scan neuf d''une Inscription {0} sur une Course TERMINEE : Course terminée, aucun Passage")
    @EnumSource(StatutInscription.class)
    void doit_refuser_tout_scan_neuf_sur_une_course_terminee_quel_que_soit_le_coureur(StatutInscription statut) {
        Inscription coureur = s.inscrire(Z, COMPTE_TOM, 3, StatutInscription.EN_COURSE);
        coureur = s.avecStatut(coureur, statut);
        Inscription scanne = coureur;

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(300)).executer(Z, LEO, scanne.jetonQr(), UUID.randomUUID(), 0),
                CourseTermineePourPassageException.class);
        assertThat(s.passages.passages).isEmpty();
    }

    @Test
    @DisplayName("CA4 - RG9 : sur Z, le vainqueur, l'abandonné et l'EN_COURSE obtiennent tous la même exception de Course terminée")
    void doit_refuser_vainqueur_abandonne_et_en_course_avec_la_meme_exception() {
        Inscription vainqueur = s.inscrire(Z, COMPTE_BRUNO, 1, StatutInscription.VAINQUEUR);
        Inscription abandonne = s.inscrire(Z, COMPTE_CHLOE, 2, StatutInscription.ABANDON);
        Inscription enCourse = s.inscrire(Z, COMPTE_TOM, 3, StatutInscription.EN_COURSE);

        for (Inscription coureur : new Inscription[] {vainqueur, abandonne, enCourse}) {
            s.assertRefusSansEcriture(
                    () -> s.casA(T0.plusSeconds(300)).executer(Z, LEO, coureur.jetonQr(), UUID.randomUUID(), 0),
                    CourseTermineePourPassageException.class);
        }
    }

    @Test
    @DisplayName("CA4 - RG9 : rejeu d'un identifiantScan existant sur une Course TERMINEE : rejeu renvoyé sans exception")
    void doit_rejouer_un_scan_existant_meme_sur_une_course_terminee() {
        Inscription vainqueur = s.inscrire(Z, COMPTE_BRUNO, 1, StatutInscription.VAINQUEUR);
        s.passages.poser(Passage.scanner(SCAN_DE_BRUNO, vainqueur, 1, T0.plusSeconds(10)));

        var rejeu = s.casA(T0.plusSeconds(300)).executer(Z, LEO, vainqueur.jetonQr(), SCAN_DE_BRUNO, 0);

        assertThat(rejeu.rejeu()).isTrue();
        assertThat(rejeu.passage().id()).isEqualTo(SCAN_DE_BRUNO);
        assertThat(s.passages.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA4 - RG9 : Course EN_COURS et coureur ABANDON : InscriptionNonEnCourseException (inchangé), aucun Passage")
    void doit_garder_le_refus_non_en_course_pour_un_abandon_dans_une_course_en_cours() {
        Inscription abandon = s.chloe.abandonner(MotifAbandon.HORS_DELAI, 1);
        s.inscriptions.inscriptions.replaceAll(i -> i.id().equals(abandon.id()) ? abandon : i);

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusSeconds(90)).executer(X, LEO, abandon.jetonQr(), UUID.randomUUID(), 0),
                InscriptionNonEnCourseException.class);
    }

    @Test
    @DisplayName("CA4 - RG9 : scan de Chloé de scanneLe 59,8 s validé à 60,3 s, encore EN_COURSE : Passage boucle 1 créé, puis le contrôle avec Bruno {1} ne désigne aucun vainqueur")
    void doit_compter_le_scan_valide_avant_la_decision_dans_les_passants() {
        s.passeSur(s.bruno, 1);
        Instant validation = T0.plusMillis(60_300);

        var resultat = s.casA(validation).executer(X, LEO, s.chloe.jetonQr(), UUID.randomUUID(), 500);
        var controle = new AbandonnerCoureursHorsDelai(s.courses, s.inscriptions, s.passages,
                java.time.Clock.fixed(validation, java.time.ZoneOffset.UTC)).executer(X);

        assertThat(resultat.rejeu()).isFalse();
        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
        assertThat(controle.vainqueurs()).isZero();
        assertThat(controle.abandons()).isZero();
        assertThat(s.inscriptions.inscriptions).allMatch(Inscription::estEnCourse);
        assertThat(s.courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA4 - RG9 : le même scan de Chloé validé après la décision (Bruno vainqueur, Chloé abandonnée, Course terminée) est refusé : Course terminée, aucun Passage de Chloé")
    void doit_refuser_le_scan_valide_apres_la_decision_sans_delai_de_grace() {
        s.passeSur(s.bruno, 1);
        Instant validation = T0.plusMillis(60_300);
        new AbandonnerCoureursHorsDelai(s.courses, s.inscriptions, s.passages,
                java.time.Clock.fixed(T0.plusSeconds(60), java.time.ZoneOffset.UTC)).executer(X);
        // Le contrôle de fin de course écrit la Course une fois : seul le scan refusé ne doit rien écrire.
        s.courses.nombreDEnregistrements = 0;

        s.assertRefusSansEcriture(
                () -> s.casA(validation).executer(X, LEO, s.chloe.jetonQr(), UUID.randomUUID(), 500),
                CourseTermineePourPassageException.class);
        assertThat(s.passages.passages).noneMatch(p -> p.inscriptionId().equals(s.chloe.id()));
    }

    @Test
    @DisplayName("CA3 - RG8 : scan de Chloé de scanneLe 59,8 s validé à 60,3 s, Bruno {} : Passage boucle 1 créé, puis le contrôle désigne Chloé vainqueur unique et abandonne Bruno (boucle 1)")
    void doit_designer_la_scanneuse_vainqueur_unique_quand_son_scan_est_le_seul_de_la_boucle() {
        Instant validation = T0.plusMillis(60_300);

        var resultat = s.casA(validation).executer(X, LEO, s.chloe.jetonQr(), UUID.randomUUID(), 500);
        var controle = new AbandonnerCoureursHorsDelai(s.courses, s.inscriptions, s.passages,
                java.time.Clock.fixed(validation, java.time.ZoneOffset.UTC)).executer(X);

        assertThat(resultat.passage().numeroBoucle()).isEqualTo(1);
        assertThat(controle.vainqueurs()).isEqualTo(1);
        assertThat(controle.abandons()).isEqualTo(1);
        assertThat(s.inscriptions.inscriptions).filteredOn(i -> i.id().equals(s.chloe.id()))
                .allMatch(i -> i.statut() == StatutInscription.VAINQUEUR);
        assertThat(s.inscriptions.inscriptions).filteredOn(i -> i.id().equals(s.bruno.id()))
                .allMatch(i -> i.statut() == StatutInscription.ABANDON && i.boucleAbandon().equals(java.util.Optional.of(1)));
        assertThat(s.courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG8 : le même scan validé après un contrôle ayant déclaré Bruno et Chloé vainqueurs partagés (personne n'a bouclé) est refusé : Course terminée, aucun Passage")
    void doit_refuser_le_scan_valide_apres_des_vainqueurs_partages() {
        new AbandonnerCoureursHorsDelai(s.courses, s.inscriptions, s.passages,
                java.time.Clock.fixed(T0.plusSeconds(60), java.time.ZoneOffset.UTC)).executer(X);
        assertThat(s.inscriptions.inscriptions).allMatch(i -> i.statut() == StatutInscription.VAINQUEUR);
        s.courses.nombreDEnregistrements = 0;

        s.assertRefusSansEcriture(
                () -> s.casA(T0.plusMillis(60_300)).executer(X, LEO, s.chloe.jetonQr(), UUID.randomUUID(), 500),
                CourseTermineePourPassageException.class);
        assertThat(s.passages.passages).isEmpty();
    }
}
