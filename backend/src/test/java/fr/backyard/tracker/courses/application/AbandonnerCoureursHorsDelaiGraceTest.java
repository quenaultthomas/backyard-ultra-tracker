package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.application.DecorGraceDeTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 7.1 : le contrôle de fin de Boucle n'agit qu'après le délai de grâce (CA4 à CA7 ; RG3, RG4,
 * RG5). Signatures supposées : celles de 4.8 à 4.10 inchangées ({@code AbandonnerCoureursHorsDelai.executer(UUID)}).
 * Boucles de 60 min (grâce 3 min), départ 10:00:00Z ; A et B EN_COURSE.
 */
class AbandonnerCoureursHorsDelaiGraceTest {

    @Test
    @DisplayName("CA4 - A {1}, B {} : à 11:02:59.999 aucune décision")
    void doit_ne_rien_decider_une_milliseconde_avant_la_fin_de_la_grace() {
        var decor = new DecorGraceDeTest(5);
        Inscription a = decor.coureur(1, 1);
        Inscription b = decor.coureur(2);

        var resultat = decor.controle(a("11:02:59.999")).executer(DecorGraceDeTest.COURSE);

        assertThat(resultat.abandons()).isZero();
        assertThat(resultat.vainqueurs()).isZero();
        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA4 - A {1}, B {} : à 11:03:00 B ABANDON (HORS_DELAI, boucle 1), A VAINQUEUR, Course TERMINEE")
    void doit_declarer_le_vainqueur_et_abandonner_l_autre_a_la_fin_de_la_grace() {
        var decor = new DecorGraceDeTest(5);
        Inscription a = decor.coureur(1, 1);
        Inscription b = decor.coureur(2);

        decor.controle(a("11:03:00")).executer(DecorGraceDeTest.COURSE);

        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(decor.relue(b).motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(decor.relue(b).boucleAbandon()).contains(1);
        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA5 - B reçoit en grâce un Passage sur 1 (réel 10:59:00, reçu 11:01:00) : à 11:03:00 A et B restent EN_COURSE")
    void doit_continuer_la_course_quand_le_scan_tardif_de_b_arrive_pendant_la_grace() {
        var decor = new DecorGraceDeTest(5);
        Inscription a = decor.coureur(1, 1);
        Inscription b = decor.coureur(2);

        var scanDeB = decor.scan(a("11:01:00")).executer(DecorGraceDeTest.COURSE, DecorGraceDeTest.LEO,
                b.jetonQr(), UUID.randomUUID(), 120_000);
        decor.controle(a("11:03:00")).executer(DecorGraceDeTest.COURSE);

        assertThat(scanDeB.passage().numeroBoucle()).isEqualTo(1);
        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA6 - A et B sans Passage sur 1 : aucune décision à 11:02:59.999")
    void doit_attendre_la_fin_de_la_grace_avant_les_vainqueurs_partages() {
        var decor = new DecorGraceDeTest(5);
        Inscription a = decor.coureur(1);
        Inscription b = decor.coureur(2);

        decor.controle(a("11:02:59.999")).executer(DecorGraceDeTest.COURSE);

        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA6 - A et B sans Passage sur 1 : à 11:03:00 vainqueurs partagés, Course TERMINEE")
    void doit_partager_la_victoire_a_la_fin_de_la_grace() {
        var decor = new DecorGraceDeTest(5);
        Inscription a = decor.coureur(1);
        Inscription b = decor.coureur(2);

        var resultat = decor.controle(a("11:03:00")).executer(DecorGraceDeTest.COURSE);

        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA7 - M = 2, A et B {1}, A {2} : aucune décision à 12:02:59.999")
    void doit_attendre_la_fin_de_la_grace_de_la_derniere_boucle() {
        var decor = new DecorGraceDeTest(2);
        Inscription a = decor.coureur(1, 1, 2);
        Inscription b = decor.coureur(2, 1);

        decor.controle(a("12:02:59.999")).executer(DecorGraceDeTest.COURSE);

        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA7 - M = 2, A et B {1}, A {2} : à 12:03:00 A VAINQUEUR, B ABANDON boucle 2, Course TERMINEE")
    void doit_trancher_la_derniere_boucle_a_la_fin_de_la_grace() {
        var decor = new DecorGraceDeTest(2);
        Inscription a = decor.coureur(1, 1, 2);
        Inscription b = decor.coureur(2, 1);

        decor.controle(a("12:03:00")).executer(DecorGraceDeTest.COURSE);

        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(decor.relue(b).boucleAbandon()).contains(2);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA7 - M = 2 : B reçoit un Passage sur 2 (réel 11:59:00, reçu 12:01:00) : à 12:03:00 A et B VAINQUEUR")
    void doit_partager_la_victoire_quand_le_scan_tardif_de_b_arrive_dans_la_grace_de_la_derniere_boucle() {
        var decor = new DecorGraceDeTest(2);
        Inscription a = decor.coureur(1, 1, 2);
        Inscription b = decor.coureur(2, 1);

        var scanDeB = decor.scan(a("12:01:00")).executer(DecorGraceDeTest.COURSE, DecorGraceDeTest.LEO,
                b.jetonQr(), UUID.randomUUID(), 120_000);
        decor.controle(a("12:03:00")).executer(DecorGraceDeTest.COURSE);

        assertThat(scanDeB.passage().numeroBoucle()).isEqualTo(2);
        assertThat(decor.relue(a).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(decor.relue(b).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(decor.statutCourse()).isEqualTo(StatutCourse.TERMINEE);
    }
}
