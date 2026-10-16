package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.CourseTermineePourReintegrationException;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.HeureDeDepartInconnueException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnAbandonException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.OriginePassage;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import fr.backyard.tracker.courses.domaine.TempsDeCourseEcouleException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.12 : cas d'usage de réintégration (CA3 ; RG1 à RG4, RG7, RG8, RG16).
 *
 * Signatures supposées :
 * - {@code new ReintegrerCoureur(DepotCourses, DepotInscriptions, DepotPassages, Clock)} ;
 * - {@code Resultat executer(UUID courseId, UUID inscriptionId)} avec
 *   {@code record Resultat(Inscription inscription, List<Integer> bouclesCorrigees)} (numéros croissants) ;
 * - ordre des opérations : (1) {@code Clock.instant()} lu une seule fois ; (2) Course chargée par
 *   {@code DepotCourses.parIdPourModification} (jamais {@code parId}), CourseIntrouvableException si absente ;
 *   (3) {@code course.autoriserReintegration(instant)} AVANT toute lecture de l'Inscription ; (4) Inscription par
 *   {@code DepotInscriptions.parIdEtCourse}, InscriptionIntrouvableException si absente ; (5) Passages de
 *   l'Inscription par {@code DepotPassages.parInscriptions} ; (6) {@code inscription.reintegrer()} (refus
 *   InscriptionNonEnAbandonException) et {@code Reintegration.bouclesACorriger(...)} avec
 *   {@code course.bouclesTerminees(instant)}, avant toute écriture ; (7) écriture de chaque Passage CORRECTION par
 *   {@code DepotPassages.enregistrer} (identifiant aléatoire, {@code Passage.corriger}) puis de l'Inscription par
 *   {@code DepotInscriptions.mettreAJour} (une fois, même quand aucune Boucle n'est corrigée) ; la Course n'est
 *   jamais sauvegardée.
 * Course X : Boucles de 1 min, M = 5, t0 = 14:00:00Z ; Bruno (1), Chloé (2), Tom (3).
 */
class ReintegrerCoureurTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final UUID X = id(1);
    private static final UUID Y = id(2);

    private final DepotCoursesDeTest courses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();

    private Inscription bruno;
    private Inscription chloe;
    private Inscription tom;

    /** Horloge fixe qui compte ses lectures. */
    private static final class HorlogeComptee extends Clock {
        private final Instant instant;
        final AtomicInteger lectures = new AtomicInteger();

        HorlogeComptee(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            lectures.incrementAndGet();
            return instant;
        }
    }

    private ReintegrerCoureur cas(Instant maintenant) {
        return new ReintegrerCoureur(courses, inscriptions, passages, Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private static Instant a(String heureMinuteSeconde) {
        return Instant.parse("2026-10-12T" + heureMinuteSeconde + "Z");
    }

    /** Course X EN_COURS ; Bruno et Chloé EN_COURSE avec les Boucles données ; Tom en abandon sans motif posé ici. */
    private void courseXAvecBrunoEtChloe(Integer[] pourBruno, Integer[] pourChloe) {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        bruno = inscrire(X, 1, StatutInscription.EN_COURSE, null, null, pourBruno);
        chloe = inscrire(X, 2, StatutInscription.EN_COURSE, null, null, pourChloe);
        courses.nombreDEnregistrements = 0;
    }

    private void tomEnAbandon(MotifAbandon motif, int boucleAbandon, Integer... boucles) {
        tom = inscrire(X, 3, StatutInscription.ABANDON, motif, boucleAbandon, boucles);
    }

    private static Integer[] boucles(Integer... boucles) {
        return boucles;
    }

    // ---------- CA3 : réintégration nominale ----------

    @Test
    @DisplayName("CA3 - RG3/RG4 : Tom HORS_DELAI Boucle 1 réintégré à 14:01:30 : EN_COURSE, sans motif ni Boucle, résultat [1]")
    void doit_remettre_en_course_un_coureur_en_abandon_hors_delai() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);

        var resultat = cas(a("14:01:30")).executer(X, tom.id());

        Inscription tomApres = relue(tom);
        assertThat(tomApres.statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(tomApres.motifAbandon()).isEmpty();
        assertThat(tomApres.boucleAbandon()).isEmpty();
        assertThat(resultat.inscription().id()).isEqualTo(tom.id());
        assertThat(resultat.inscription().statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(resultat.bouclesCorrigees()).containsExactly(1);
    }

    @Test
    @DisplayName("CA3 - RG3/RG4 : un seul Passage CORRECTION est sauvegardé, Boucle 1, sans instant de scan")
    void doit_sauvegarder_un_passage_de_correction_sans_instant_de_scan() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);

        cas(a("14:01:30")).executer(X, tom.id());

        assertThat(passages.nombreDEnregistrements).isEqualTo(1);
        List<Passage> deTom = passagesDe(tom);
        assertThat(deTom).hasSize(1);
        assertThat(deTom.get(0).numeroBoucle()).isEqualTo(1);
        assertThat(deTom.get(0).origine()).isEqualTo(OriginePassage.CORRECTION);
        assertThat(deTom.get(0).scanneLe()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - RG4 : la Course n'est pas sauvegardée et reste EN_COURS ; les autres Inscriptions sont inchangées")
    void doit_laisser_la_course_et_les_autres_inscriptions_inchangees() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);
        List<Passage> passagesDeBrunoEtChloe = passagesDe(bruno, chloe);

        cas(a("14:01:30")).executer(X, tom.id());

        assertThat(courses.nombreDEnregistrements).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.parId(X).orElseThrow().demarreeLe()).contains(T0);
        assertThat(relue(bruno)).isEqualTo(bruno);
        assertThat(relue(chloe)).isEqualTo(chloe);
        assertThat(passagesDe(bruno, chloe)).containsExactlyElementsOf(passagesDeBrunoEtChloe);
    }

    @Test
    @DisplayName("CA3 - RG7 : Course chargée par parIdPourModification (jamais parId), Clock lu une seule fois, Inscription mise à jour une fois")
    void doit_charger_sous_verrou_lire_l_horloge_une_fois_et_mettre_a_jour_une_fois() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);
        HorlogeComptee horloge = new HorlogeComptee(a("14:01:30"));

        new ReintegrerCoureur(courses, inscriptions, passages, horloge).executer(X, tom.id());

        assertThat(courses.nombreDeChargementsPourModification).isEqualTo(1);
        assertThat(courses.nombreDeChargementsParId).isZero();
        assertThat(horloge.lectures.get()).isEqualTo(1);
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3 - RG3 : un identifiant de Passage aléatoire et distinct par Boucle corrigée")
    void doit_generer_un_identifiant_distinct_par_passage_de_correction() {
        courseXAvecBrunoEtChloe(boucles(1, 2, 3), boucles(1, 2, 3));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 2, 1);

        cas(a("14:03:30")).executer(X, tom.id());

        Set<UUID> identifiants = new HashSet<>();
        passagesDe(tom).stream().filter(p -> p.origine() == OriginePassage.CORRECTION)
                .forEach(p -> identifiants.add(p.id()));
        assertThat(identifiants).hasSize(2).doesNotContainNull();
    }

    // ---------- CA3 : Boucles à corriger ----------

    @Test
    @DisplayName("CA3 - RG3 : Tom MANUEL Boucle d'abandon 2 avec {1} réintégré à 14:01:30 (N = 1) : aucun Passage, résultat []")
    void doit_ne_creer_aucun_passage_quand_la_boucle_d_abandon_est_la_boucle_courante() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.MANUEL, 2, 1);

        var resultat = cas(a("14:01:30")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).isEmpty();
        assertThat(passages.nombreDEnregistrements).isZero();
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(tom).motifAbandon()).isEmpty();
        assertThat(relue(tom).boucleAbandon()).isEmpty();
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3 - RG3 : Tom Boucle d'abandon 2 avec {1} réintégré à 14:03:30 (N = 3) : Passages 2 et 3, résultat [2, 3]")
    void doit_corriger_les_boucles_terminees_manquees() {
        courseXAvecBrunoEtChloe(boucles(1, 2, 3), boucles(1, 2, 3));
        tomEnAbandon(MotifAbandon.AUTRE, 2, 1);

        var resultat = cas(a("14:03:30")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).containsExactly(2, 3);
        assertThat(passagesDe(tom)).extracting(Passage::numeroBoucle, Passage::origine)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, OriginePassage.SCAN),
                        org.assertj.core.groups.Tuple.tuple(2, OriginePassage.CORRECTION),
                        org.assertj.core.groups.Tuple.tuple(3, OriginePassage.CORRECTION));
    }

    @Test
    @DisplayName("CA3 - RG3/RG8 : seules les Boucles sans Passage sont corrigées, les Passages CORRECTION existants restent")
    void doit_ne_corriger_que_les_boucles_sans_passage_apres_un_cycle_d_abandon() {
        courseXAvecBrunoEtChloe(boucles(1, 2, 3, 4), boucles(1, 2, 3, 4));
        tomEnAbandon(MotifAbandon.VOLONTAIRE, 4, 1);
        Passage correction2 = Passage.corriger(UUID.randomUUID(), tom, 2);
        Passage correction3 = Passage.corriger(UUID.randomUUID(), tom, 3);
        passages.poser(correction2);
        passages.poser(correction3);

        var resultat = cas(a("14:04:10")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).containsExactly(4);
        assertThat(passagesDe(tom)).hasSize(4).contains(correction2, correction3);
        assertThat(passages.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3 - RG3 : sans Boucle d'abandon enregistrée, la correction commence après le dernier Passage contigu")
    void doit_partir_du_nombre_de_passages_plus_un_sans_boucle_d_abandon_enregistree() {
        courseXAvecBrunoEtChloe(boucles(1, 2, 3), boucles(1, 2, 3));
        tom = Inscription.reconstituer(UUID.randomUUID(), X, UUID.randomUUID(), 3, JetonQr.generer(),
                StatutInscription.ABANDON);
        inscriptions.enregistrer(tom);
        passages.poser(Passage.scanner(UUID.randomUUID(), tom, 1, T0.plusSeconds(10)));

        var resultat = cas(a("14:03:30")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("CA2/CA3 - RG6 : à 14:00:59.999 la Boucle 1 est courante, rien n'est corrigé")
    void doit_ne_pas_corriger_la_boucle_courante_une_milliseconde_avant_sa_fin() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.MANUEL, 1);

        var resultat = cas(a("14:00:59.999")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).isEmpty();
        assertThat(passagesDe(tom)).isEmpty();
    }

    @Test
    @DisplayName("CA2/CA3 - RG6 : à 14:01:00.000 la Boucle 1 est terminée et corrigée")
    void doit_corriger_la_boucle_des_qu_elle_est_terminee() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.MANUEL, 1);

        var resultat = cas(a("14:01:00")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).containsExactly(1);
        assertThat(passagesDe(tom)).extracting(Passage::numeroBoucle, Passage::origine)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, OriginePassage.CORRECTION));
    }

    @Test
    @DisplayName("CA2/CA3 - RG6 : à 14:04:59.999 (derniers instants de la Boucle 5), les Boucles 1 à 4 sont corrigées")
    void doit_corriger_jusqu_a_la_derniere_boucle_terminee_pendant_la_derniere_boucle() {
        courseXAvecBrunoEtChloe(boucles(1, 2, 3, 4), boucles(1, 2, 3, 4));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);

        var resultat = cas(a("14:04:59.999")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).containsExactly(1, 2, 3, 4);
    }

    // ---------- CA3 : refus d'Inscription ----------

    @Test
    @DisplayName("CA3 - RG8 : second appel -> InscriptionNonEnAbandonException (EN_COURSE), aucune écriture supplémentaire")
    void doit_refuser_le_rejeu_sans_ecriture() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);
        cas(a("14:01:30")).executer(X, tom.id());
        int passagesEcrits = passages.nombreDEnregistrements;
        int misesAJour = inscriptions.nombreDeMisesAJour;

        assertThatThrownBy(() -> cas(a("14:01:31")).executer(X, tom.id()))
                .isInstanceOfSatisfying(InscriptionNonEnAbandonException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.EN_COURSE));

        assertThat(passages.nombreDEnregistrements).isEqualTo(passagesEcrits);
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(misesAJour);
        assertThat(passagesDe(tom)).hasSize(1);
    }

    @Test
    @DisplayName("CA3 - RG2 : un coureur EN_COURSE n'est pas réintégrable, aucune écriture")
    void doit_refuser_la_reintegration_d_un_coureur_en_course() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));

        assertThatThrownBy(() -> cas(a("14:01:30")).executer(X, bruno.id()))
                .isInstanceOfSatisfying(InscriptionNonEnAbandonException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.EN_COURSE));

        assertThat(passages.nombreDEnregistrements).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA3 - RG2 : un VAINQUEUR n'est jamais réintégré, aucune écriture")
    void doit_refuser_la_reintegration_d_un_vainqueur() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        Inscription vainqueur = inscrire(X, 4, StatutInscription.VAINQUEUR, null, null, boucles(1));

        assertThatThrownBy(() -> cas(a("14:01:30")).executer(X, vainqueur.id()))
                .isInstanceOfSatisfying(InscriptionNonEnAbandonException.class,
                        e -> assertThat(e.statut()).isEqualTo(StatutInscription.VAINQUEUR));

        assertThat(relue(vainqueur).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(passages.nombreDEnregistrements).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA3 - RG16 : Inscription inconnue -> InscriptionIntrouvableException, aucune écriture")
    void doit_refuser_une_inscription_inconnue() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));

        assertThatThrownBy(() -> cas(a("14:01:30")).executer(X, id(999)))
                .isInstanceOf(InscriptionIntrouvableException.class);

        assertThat(passages.nombreDEnregistrements).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA3 - RG16 : l'Inscription d'une autre Course est introuvable et reste inchangée")
    void doit_refuser_l_inscription_d_une_autre_course() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        courses.enregistrer(course(Y, StatutCourse.EN_COURS, 5, T0));
        Inscription deY = inscrire(Y, 1, StatutInscription.ABANDON, MotifAbandon.MANUEL, 1, boucles());
        courses.nombreDEnregistrements = 0;

        assertThatThrownBy(() -> cas(a("14:01:30")).executer(X, deY.id()))
                .isInstanceOf(InscriptionIntrouvableException.class);

        assertThat(relue(deY).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(deY).motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(passagesDe(deY)).isEmpty();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA3 - RG16 : réintégrer dans X ne touche ni la Course Y ni ses Inscriptions ; N se calcule avec les paramètres de X")
    void doit_ne_toucher_que_la_course_concernee() {
        courseXAvecBrunoEtChloe(boucles(1), boucles(1));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);
        courses.enregistrer(Course.reconstituer(Y, "Backyard Y", JOUR, StatutCourse.EN_COURS,
                new ParametresBoucle(400, 90, 5), 10, 5, Set.of(), T0));
        Inscription tomDeY = inscrire(Y, 3, StatutInscription.ABANDON, MotifAbandon.HORS_DELAI, 1, boucles());

        var resultat = cas(a("14:01:30")).executer(X, tom.id());

        assertThat(resultat.bouclesCorrigees()).containsExactly(1);
        assertThat(relue(tomDeY).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(passagesDe(tomDeY)).isEmpty();
        assertThat(courses.parId(Y).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    // ---------- CA3 : refus de Course, avant toute lecture de l'Inscription ----------

    @Test
    @DisplayName("CA3 - RG1/RG16 : Course inconnue -> CourseIntrouvableException, sans lire d'Inscription")
    void doit_refuser_une_course_inconnue() {
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        DepotPassages depotPassages = mock(DepotPassages.class);
        var casSansCourse = new ReintegrerCoureur(courses, depotInscriptions, depotPassages,
                Clock.fixed(a("14:01:30"), ZoneOffset.UTC));

        assertThatThrownBy(() -> casSansCourse.executer(X, id(5))).isInstanceOf(CourseIntrouvableException.class);

        verifyNoInteractions(depotInscriptions, depotPassages);
    }

    @Test
    @DisplayName("CA3 - RG1 : Course TERMINEE -> exception de Course terminée, y compris pour une Inscription inconnue, Inscription non lue")
    void doit_refuser_une_course_terminee_avant_de_lire_l_inscription() {
        courses.enregistrer(course(X, StatutCourse.TERMINEE, 5, T0));
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        DepotPassages depotPassages = mock(DepotPassages.class);
        var casTermine = new ReintegrerCoureur(courses, depotInscriptions, depotPassages,
                Clock.fixed(a("14:01:30"), ZoneOffset.UTC));

        assertThatThrownBy(() -> casTermine.executer(X, id(5)))
                .isInstanceOf(CourseTermineePourReintegrationException.class);

        verifyNoInteractions(depotInscriptions, depotPassages);
    }

    @Test
    @DisplayName("CA3 - RG1 : Course TERMINEE avec un abandon réintégrable -> refusée, aucun Passage, Inscription inchangée")
    void doit_ne_jamais_rouvrir_une_course_terminee() {
        courses.enregistrer(course(X, StatutCourse.TERMINEE, 5, T0));
        tomEnAbandon(MotifAbandon.MANUEL, 1);
        courses.nombreDEnregistrements = 0;

        assertThatThrownBy(() -> cas(a("14:01:30")).executer(X, tom.id()))
                .isInstanceOf(CourseTermineePourReintegrationException.class);

        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(passages.nombreDEnregistrements).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG1 : Course EN_PREPARATION -> CourseNonDemarreeException avant toute lecture de l'Inscription")
    void doit_refuser_une_course_en_preparation_avant_de_lire_l_inscription() {
        courses.enregistrer(course(X, StatutCourse.EN_PREPARATION, 5, null));
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        DepotPassages depotPassages = mock(DepotPassages.class);
        var casEnPreparation = new ReintegrerCoureur(courses, depotInscriptions, depotPassages,
                Clock.fixed(a("14:01:30"), ZoneOffset.UTC));

        assertThatThrownBy(() -> casEnPreparation.executer(X, id(5))).isInstanceOf(CourseNonDemarreeException.class);

        verifyNoInteractions(depotInscriptions, depotPassages);
    }

    @Test
    @DisplayName("CA3 - RG1 : Course EN_COURS sans heure de départ -> HeureDeDepartInconnueException avant lecture de l'Inscription")
    void doit_refuser_une_course_sans_heure_de_depart_avant_de_lire_l_inscription() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, null));
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        DepotPassages depotPassages = mock(DepotPassages.class);
        var casSansDepart = new ReintegrerCoureur(courses, depotInscriptions, depotPassages,
                Clock.fixed(a("14:01:30"), ZoneOffset.UTC));

        assertThatThrownBy(() -> casSansDepart.executer(X, id(5))).isInstanceOf(HeureDeDepartInconnueException.class);

        verifyNoInteractions(depotInscriptions, depotPassages);
    }

    @Test
    @DisplayName("CA3 - RG1/RG6 : à 14:05:00.000 (t0 + M x durée) -> TempsDeCourseEcouleException avant lecture de l'Inscription")
    void doit_refuser_quand_le_temps_de_course_est_ecoule_avant_de_lire_l_inscription() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 5, T0));
        DepotInscriptions depotInscriptions = mock(DepotInscriptions.class);
        DepotPassages depotPassages = mock(DepotPassages.class);
        var casEcoule = new ReintegrerCoureur(courses, depotInscriptions, depotPassages,
                Clock.fixed(a("14:05:00"), ZoneOffset.UTC));

        assertThatThrownBy(() -> casEcoule.executer(X, id(5))).isInstanceOf(TempsDeCourseEcouleException.class);

        verifyNoInteractions(depotInscriptions, depotPassages);
    }

    // ---------- CA3 : tout ou rien ----------

    @Test
    @DisplayName("CA3 - RG7 : si l'écriture d'un Passage échoue, l'erreur est propagée et l'Inscription reste en abandon")
    void doit_laisser_l_inscription_en_abandon_si_l_ecriture_des_passages_echoue() {
        courseXAvecBrunoEtChloe(boucles(1, 2), boucles(1, 2));
        tomEnAbandon(MotifAbandon.HORS_DELAI, 1);
        IllegalStateException panne = new IllegalStateException("panne de base");
        passages.echecAEnregistrer = panne;

        assertThatThrownBy(() -> cas(a("14:02:30")).executer(X, tom.id())).isSameAs(panne);

        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    // ---------- fabriques ----------

    private static Course course(UUID id, StatutCourse statut, int nombreMaxBoucles, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard " + id, JOUR, statut, new ParametresBoucle(400, 1, 5), 10,
                nombreMaxBoucles, Set.of(), demarreeLe);
    }

    private Inscription inscrire(UUID courseId, int dossard, StatutInscription statut, MotifAbandon motif,
                                 Integer boucleAbandon, Integer[] boucles) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, UUID.randomUUID(), dossard,
                JetonQr.generer(), statut, motif, boucleAbandon);
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

    private List<Passage> passagesDe(Inscription... concernees) {
        Set<UUID> ids = new HashSet<>();
        for (Inscription i : concernees) {
            ids.add(i.id());
        }
        return passages.passages.stream().filter(p -> ids.contains(p.inscriptionId())).toList();
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
