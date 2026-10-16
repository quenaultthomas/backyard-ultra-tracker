package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.CourseTermineePourAbandonException;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnCourseException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.11 : cas d'usage d'abandon manuel (CA2, CA3 ; RG1 à RG5, RG7, RG15).
 *
 * Signatures supposées :
 * - {@code new AbandonnerCoureur(DepotCourses, DepotInscriptions, DepotPassages, Clock)} (le Clock est injecté comme
 *   pour les autres cas d'usage mais n'est jamais lu) ;
 * - {@code Resultat executer(UUID courseId, UUID inscriptionId, MotifAbandon motif)} avec
 *   {@code record Resultat(Inscription inscription, StatutCourse statutCourse)} ;
 * - Course chargée par {@code parIdPourModification}, Inscription par {@code parIdEtCourse}, Passages comptés par
 *   {@code DepotPassages.parInscriptions} ; l'Inscription est écrite par {@code DepotInscriptions.mettreAJour}
 *   (une fois), la Course par {@code DepotCourses.enregistrer} uniquement quand elle se termine ;
 * - exceptions : CourseIntrouvableException, InscriptionIntrouvableException, CourseNonDemarreeException,
 *   CourseTermineePourAbandonException, InscriptionNonEnCourseException, IllegalArgumentException (HORS_DELAI).
 * Course X : Boucles de 1 min, M = 5, t0 = 14:00:00Z ; Bruno (1), Chloé (2), Tom (3).
 */
class AbandonnerCoureurTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final UUID X = id(1);
    private static final UUID Y = id(2);

    private final DepotCoursesDeTest courses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();
    private final Clock horlogeInterdite = mock(Clock.class);
    private final AbandonnerCoureur cas = new AbandonnerCoureur(courses, inscriptions, passages, horlogeInterdite);

    private Inscription bruno;
    private Inscription chloe;
    private Inscription tom;

    private void courseXAvec(Integer[] pourBruno, Integer[] pourChloe, Integer[] pourTom) {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        bruno = inscrire(X, 1, StatutInscription.EN_COURSE, pourBruno);
        chloe = inscrire(X, 2, StatutInscription.EN_COURSE, pourChloe);
        tom = inscrire(X, 3, StatutInscription.EN_COURSE, pourTom);
        courses.nombreDEnregistrements = 0;
    }

    private static Integer[] boucles(Integer... boucles) {
        return boucles;
    }

    // ---------- CA2 : abandon d'un coureur, Course qui continue ----------

    @Test
    @DisplayName("CA2 - RG3 : Bruno {1, 2} abandonné MANUEL : ABANDON, MANUEL, Boucle 3")
    void doit_abandonner_un_coureur_sur_la_premiere_boucle_non_terminee() {
        courseXAvec(boucles(1, 2), boucles(1), boucles());

        var resultat = cas.executer(X, bruno.id(), MotifAbandon.MANUEL);

        Inscription brunoApres = relue(bruno);
        assertThat(brunoApres.statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(brunoApres.motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(brunoApres.boucleAbandon()).contains(3);
        assertThat(resultat.inscription().id()).isEqualTo(bruno.id());
        assertThat(resultat.inscription().statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(resultat.inscription().boucleAbandon()).contains(3);
    }

    @Test
    @DisplayName("CA2 - RG3 : Tom {} abandonné VOLONTAIRE : Boucle d'abandon 1")
    void doit_abandonner_sur_la_boucle_1_un_coureur_sans_passage() {
        courseXAvec(boucles(1, 2), boucles(1), boucles());

        cas.executer(X, tom.id(), MotifAbandon.VOLONTAIRE);

        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.VOLONTAIRE);
        assertThat(relue(tom).boucleAbandon()).contains(1);
    }

    @Test
    @DisplayName("CA2 - RG3 : un Passage CORRECTION compte comme un SCAN dans la Boucle d'abandon")
    void doit_compter_un_passage_de_correction_comme_un_scan() {
        courseXAvec(boucles(), boucles(1), boucles());
        passages.poser(Passage.corriger(UUID.randomUUID(), bruno, 1));
        passages.poser(Passage.scanner(UUID.randomUUID(), bruno, 2, T0.plus(Duration.ofMinutes(1)).plusSeconds(10)));

        cas.executer(X, bruno.id(), MotifAbandon.AUTRE);

        assertThat(relue(bruno).boucleAbandon()).contains(3);
    }

    @Test
    @DisplayName("CA2 - RG3 : seuls les Passages de l'Inscription abandonnée comptent (pas ceux des autres)")
    void doit_ignorer_les_passages_des_autres_inscriptions() {
        courseXAvec(boucles(1, 2, 3), boucles(1, 2, 3, 4), boucles(1));

        cas.executer(X, tom.id(), MotifAbandon.MANUEL);

        assertThat(relue(tom).boucleAbandon()).contains(2);
    }

    @Test
    @DisplayName("CA2 - RG3 : Bruno {1..5} (M = 5) abandonné : Boucle d'abandon 6, soit nombreMaxBoucles + 1")
    void doit_accepter_une_boucle_d_abandon_au_dela_du_nombre_max_de_boucles() {
        courseXAvec(boucles(1, 2, 3, 4, 5), boucles(1), boucles());

        cas.executer(X, bruno.id(), MotifAbandon.MANUEL);

        assertThat(relue(bruno).boucleAbandon()).contains(6);
    }

    @Test
    @DisplayName("CA2 - RG4/RG5 : la Course reste EN_COURS et n'est pas sauvegardée")
    void doit_laisser_la_course_en_cours_sans_la_sauvegarder() {
        courseXAvec(boucles(1, 2), boucles(1), boucles());

        var resultat = cas.executer(X, tom.id(), MotifAbandon.VOLONTAIRE);

        assertThat(resultat.statutCourse()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - RG4 : une seule sauvegarde d'Inscription et aucun Passage écrit")
    void doit_ecrire_une_seule_inscription_et_aucun_passage() {
        courseXAvec(boucles(1, 2), boucles(1), boucles());
        List<Passage> passagesAvant = List.copyOf(passages.passages);

        cas.executer(X, bruno.id(), MotifAbandon.MANUEL);

        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(1);
        assertThat(passages.nombreDEnregistrements).isZero();
        assertThat(passages.passages).containsExactlyElementsOf(passagesAvant);
    }

    @Test
    @DisplayName("CA2 - RG4 : les autres Inscriptions ne sont pas touchées")
    void doit_laisser_les_autres_inscriptions_inchangees() {
        courseXAvec(boucles(1, 2), boucles(1), boucles());

        cas.executer(X, bruno.id(), MotifAbandon.MANUEL);

        assertThat(relue(chloe)).isEqualTo(chloe);
        assertThat(relue(tom)).isEqualTo(tom);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA2 - RG7 : Course chargée sous verrou par parIdPourModification, jamais par parId ; Clock jamais lue")
    void doit_charger_sous_verrou_sans_lire_l_horloge() {
        courseXAvec(boucles(1, 2), boucles(1), boucles());

        cas.executer(X, bruno.id(), MotifAbandon.MANUEL);

        assertThat(courses.nombreDeChargementsPourModification).isEqualTo(1);
        assertThat(courses.nombreDeChargementsParId).isZero();
        verifyNoInteractions(horlogeInterdite);
    }

    @Test
    @DisplayName("CA2 - RG1 : une Course EN_COURS sans demarreeLe reste éligible")
    void doit_abandonner_dans_une_course_en_cours_sans_heure_de_depart() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, null));
        tom = inscrire(X, 3, StatutInscription.EN_COURSE, boucles());
        bruno = inscrire(X, 1, StatutInscription.EN_COURSE, boucles());

        cas.executer(X, tom.id(), MotifAbandon.MANUEL);

        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).boucleAbandon()).contains(1);
    }

    // ---------- CA2 : refus ----------

    @Test
    @DisplayName("CA2 - RG15 : l'Inscription d'une autre Course est introuvable, aucune écriture")
    void doit_refuser_l_inscription_d_une_autre_course() {
        courseXAvec(boucles(1), boucles(1), boucles());
        courses.enregistrer(course(Y, StatutCourse.EN_COURS, 5, T0));
        Inscription deY = inscrire(Y, 1, StatutInscription.EN_COURSE, boucles());
        courses.nombreDEnregistrements = 0;

        assertThatThrownBy(() -> cas.executer(X, deY.id(), MotifAbandon.MANUEL))
                .isInstanceOf(InscriptionIntrouvableException.class);

        assertThat(relue(deY).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - RG9 : une Inscription inconnue est introuvable, aucune écriture")
    void doit_refuser_une_inscription_inconnue() {
        courseXAvec(boucles(1), boucles(1), boucles());

        assertThatThrownBy(() -> cas.executer(X, id(999), MotifAbandon.MANUEL))
                .isInstanceOf(InscriptionIntrouvableException.class);

        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - RG9 : une Course inconnue est introuvable, sans lire d'Inscription")
    void doit_refuser_une_course_inconnue() {
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        var casSansCourse = new AbandonnerCoureur(courses, depotInscriptions, passages, horlogeInterdite);

        assertThatThrownBy(() -> casSansCourse.executer(X, id(5), MotifAbandon.MANUEL))
                .isInstanceOf(CourseIntrouvableException.class);

        verifyNoInteractions(depotInscriptions);
    }

    @Test
    @DisplayName("CA2 - RG1 : Course TERMINEE : exception de Course terminée avant toute lecture de l'Inscription")
    void doit_refuser_l_abandon_dans_une_course_terminee_avant_de_lire_l_inscription() {
        courses.enregistrer(course(X, StatutCourse.TERMINEE, 5, T0));
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        DepotPassages depotPassages = mock(DepotPassages.class);
        var casTermine = new AbandonnerCoureur(courses, depotInscriptions, depotPassages, horlogeInterdite);

        assertThatThrownBy(() -> casTermine.executer(X, id(5), MotifAbandon.MANUEL))
                .isInstanceOf(CourseTermineePourAbandonException.class);

        verifyNoInteractions(depotInscriptions, depotPassages);
    }

    @Test
    @DisplayName("CA2 - RG1 : Course EN_PREPARATION : CourseNonDemarreeException avant toute lecture de l'Inscription")
    void doit_refuser_l_abandon_dans_une_course_en_preparation_avant_de_lire_l_inscription() {
        courses.enregistrer(course(X, StatutCourse.EN_PREPARATION, 5, null));
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        DepotPassages depotPassages = mock(DepotPassages.class);
        var casEnPreparation = new AbandonnerCoureur(courses, depotInscriptions, depotPassages, horlogeInterdite);

        assertThatThrownBy(() -> casEnPreparation.executer(X, id(5), MotifAbandon.MANUEL))
                .isInstanceOf(CourseNonDemarreeException.class);

        verifyNoInteractions(depotInscriptions, depotPassages);
    }

    @Test
    @DisplayName("CA2 - RG1 : Inscription déjà en abandon (HORS_DELAI) : exception, aucune écriture, abandon d'origine conservé")
    void doit_refuser_l_abandon_d_une_inscription_deja_en_abandon() {
        courseXAvec(boucles(1), boucles(1), boucles());
        Inscription deja = Inscription.reconstituer(UUID.randomUUID(), X, UUID.randomUUID(), 4, JetonQr.generer(),
                StatutInscription.ABANDON, MotifAbandon.HORS_DELAI, 1);
        inscriptions.enregistrer(deja);

        assertThatThrownBy(() -> cas.executer(X, deja.id(), MotifAbandon.MANUEL))
                .isInstanceOfSatisfying(InscriptionNonEnCourseException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.ABANDON));

        assertThat(relue(deja).motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(relue(deja).boucleAbandon()).contains(1);
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - RG1 : un VAINQUEUR n'abandonne pas : exception avec statut VAINQUEUR, aucune écriture")
    void doit_refuser_l_abandon_d_un_vainqueur() {
        courseXAvec(boucles(1), boucles(1), boucles());
        Inscription vainqueur = inscrire(X, 4, StatutInscription.VAINQUEUR, boucles());

        assertThatThrownBy(() -> cas.executer(X, vainqueur.id(), MotifAbandon.MANUEL))
                .isInstanceOfSatisfying(InscriptionNonEnCourseException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.VAINQUEUR));

        assertThat(relue(vainqueur).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA2 - RG2 : le motif HORS_DELAI est refusé par le cas d'usage, aucune écriture")
    void doit_refuser_le_motif_hors_delai() {
        courseXAvec(boucles(1), boucles(1), boucles());

        assertThatThrownBy(() -> cas.executer(X, tom.id(), MotifAbandon.HORS_DELAI))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    // ---------- CA3 : fin de Course quand plus personne n'est en course ----------

    @Test
    @DisplayName("CA3 - RG5 : Bruno et Chloé ABANDON, Tom abandonné AUTRE : Tom Boucle 1, Course TERMINEE, aucun VAINQUEUR")
    void doit_terminer_la_course_sans_vainqueur_quand_le_dernier_coureur_abandonne() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        inscrire(X, 1, StatutInscription.ABANDON, boucles());
        inscrire(X, 2, StatutInscription.ABANDON, boucles());
        tom = inscrire(X, 3, StatutInscription.EN_COURSE, boucles());
        courses.nombreDEnregistrements = 0;

        var resultat = cas.executer(X, tom.id(), MotifAbandon.AUTRE);

        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.AUTRE);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(resultat.statutCourse()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(inscriptions.inscriptions).noneMatch(i -> i.statut() == StatutInscription.VAINQUEUR);
    }

    @Test
    @DisplayName("CA3 - RG5/RG7 : la Course terminée est sauvegardée une fois, avec l'Inscription, demarreeLe conservé")
    void doit_sauvegarder_la_course_terminee_en_meme_temps_que_l_inscription() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        tom = inscrire(X, 3, StatutInscription.EN_COURSE, boucles());
        courses.nombreDEnregistrements = 0;

        cas.executer(X, tom.id(), MotifAbandon.MANUEL);

        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(1);
        assertThat(courses.nombreDEnregistrements).isEqualTo(1);
        assertThat(courses.parId(X).orElseThrow().demarreeLe()).contains(T0);
        assertThat(passages.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA3 - RG5 : Bruno et Chloé EN_COURSE, Tom abandonné : la Course reste EN_COURS")
    void doit_laisser_la_course_en_cours_si_deux_coureurs_restent() {
        courseXAvec(boucles(1), boucles(1), boucles());

        var resultat = cas.executer(X, tom.id(), MotifAbandon.MANUEL);

        assertThat(resultat.statutCourse()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA3 - RG5 : Course à un seul inscrit : son abandon termine la Course")
    void doit_terminer_une_course_a_un_seul_inscrit() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        bruno = inscrire(X, 1, StatutInscription.EN_COURSE, boucles(1));

        var resultat = cas.executer(X, bruno.id(), MotifAbandon.VOLONTAIRE);

        assertThat(resultat.statutCourse()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(relue(bruno).boucleAbandon()).contains(2);
    }

    @Test
    @DisplayName("CA3 - RG4 : Bruno et Tom abandonnés, Chloé seule restante : EN_COURSE, pas VAINQUEUR, Course EN_COURS")
    void doit_ne_pas_designer_de_vainqueur_pour_le_coureur_reste_seul() {
        courseXAvec(boucles(1), boucles(1), boucles());

        cas.executer(X, bruno.id(), MotifAbandon.MANUEL);
        var resultat = cas.executer(X, tom.id(), MotifAbandon.MANUEL);

        assertThat(resultat.statutCourse()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA3 - RG1/RG5 : après la fin de Course, un second abandon est refusé (Course terminée), rien n'est écrit")
    void doit_refuser_un_second_abandon_sur_la_course_terminee() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        tom = inscrire(X, 3, StatutInscription.EN_COURSE, boucles());
        cas.executer(X, tom.id(), MotifAbandon.MANUEL);
        int misesAJour = inscriptions.nombreDeMisesAJour;
        int sauvegardes = courses.nombreDEnregistrements;

        assertThatThrownBy(() -> cas.executer(X, tom.id(), MotifAbandon.AUTRE))
                .isInstanceOf(CourseTermineePourAbandonException.class);

        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(misesAJour);
        assertThat(courses.nombreDEnregistrements).isEqualTo(sauvegardes);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.MANUEL);
    }

    @Test
    @DisplayName("CA3 - RG15 : la fin de la Course X ne touche pas la Course Y ni ses Inscriptions")
    void doit_terminer_uniquement_la_course_concernee() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        tom = inscrire(X, 3, StatutInscription.EN_COURSE, boucles());
        courses.enregistrer(course(Y, StatutCourse.EN_COURS, 5, T0));
        Inscription deY = inscrire(Y, 1, StatutInscription.EN_COURSE, boucles());

        cas.executer(X, tom.id(), MotifAbandon.MANUEL);

        assertThat(courses.parId(Y).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(relue(deY).statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA3 - RG15 : un coureur EN_COURSE d'une autre Course ne retarde pas la fin de X")
    void doit_ignorer_les_inscriptions_des_autres_courses_pour_la_fin() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        courses.enregistrer(course(Y, StatutCourse.EN_COURS, 5, T0));
        inscrire(Y, 1, StatutInscription.EN_COURSE, boucles());
        tom = inscrire(X, 3, StatutInscription.EN_COURSE, boucles());

        var resultat = cas.executer(X, tom.id(), MotifAbandon.MANUEL);

        assertThat(resultat.statutCourse()).isEqualTo(StatutCourse.TERMINEE);
    }

    // ---------- fabriques ----------

    private static Course course(UUID id, StatutCourse statut, int nombreMaxBoucles, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard " + id, JOUR, statut, new ParametresBoucle(400, 1, 5), 10,
                nombreMaxBoucles, Set.of(), demarreeLe);
    }

    private Inscription inscrire(UUID courseId, int dossard, StatutInscription statut, Integer[] boucles) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, UUID.randomUUID(), dossard,
                JetonQr.generer(), statut);
        inscriptions.enregistrer(inscription);
        for (Integer boucle : boucles) {
            passages.poser(Passage.scanner(UUID.randomUUID(), inscription, boucle,
                    T0.plus(Duration.ofMinutes(boucle - 1L)).plusSeconds(10)));
        }
        return inscription;
    }

    private Inscription relue(Inscription inscription) {
        List<Inscription> trouvees = inscriptions.inscriptions.stream()
                .filter(i -> i.id().equals(inscription.id())).toList();
        assertThat(trouvees).hasSize(1);
        return trouvees.get(0);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
