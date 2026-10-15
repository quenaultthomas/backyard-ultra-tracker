package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.8 : cas d'usage AbandonnerCoureursHorsDelai (CA4 ; RG2, RG7, RG9, RG12, RG18).
 * Adaptés à 4.10 (RG14 : personne n'a bouclé = vainqueurs partagés ; M atteint = vainqueurs partagés, tests « ADAPTÉ 4.10 »)
 * et à 4.9 (RG15) sans affaiblissement : le résultat est un {@code Resultat} (voir ci-dessous) ; le test du
 * plafond de Boucles reçoit un second passant sur la Boucle 5 (un seul passant serait désormais vainqueur, voir
 * {@link AbandonnerCoureursHorsDelaiVainqueurTest}) ; les attentes de 4.8 sont par ailleurs inchangées.
 *
 * Signatures supposées :
 * - {@code new AbandonnerCoureursHorsDelai(DepotCourses, DepotInscriptions, DepotPassages, Clock)} ;
 * - {@code Resultat executer(UUID courseId)} (4.9 : remplace l'entier de 4.8) ; {@code Resultat} expose
 *   {@code int abandons()} (nombre d'Inscriptions mises en abandon) et {@code int vainqueurs()} (4.10 : nombre de vainqueurs) ; Course chargée
 *   par {@code DepotCourses.parIdPourModification} ; Clock lu une seule fois, après le chargement ;
 * - port {@code DepotInscriptions.mettreAJour(Inscription)} : écriture d'une Inscription existante (le double
 *   DepotInscriptionsEnMemoireDeTest la fournit et compte les appels) ; une écriture par Inscription mise en abandon ;
 * - lecture des Passages par {@code DepotPassages.parInscriptions} ou {@code existe} (les deux sont dans le double).
 * Course X : boucles de 1 min, M = 5, t0 = 14:00:00Z.
 */
class AbandonnerCoureursHorsDelaiTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final UUID X = id(1);
    private static final UUID Y = id(2);
    private static final UUID EN_PREPARATION = id(3);
    private static final UUID SANS_DEPART = id(5);
    private static final UUID INCONNUE = id(99);

    private final DepotCoursesDeTest courses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();

    private Inscription bruno;
    private Inscription chloe;
    private Inscription tom;

    private void garnirX() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 1, T0));
        bruno = inscrire(X, 1);
        chloe = inscrire(X, 2);
        tom = inscrire(X, 3);
        scan(bruno, 1);
        scan(tom, 1);
        courses.nombreDEnregistrements = 0;
    }

    private AbandonnerCoureursHorsDelai casA(Instant maintenant) {
        return casAvec(Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private AbandonnerCoureursHorsDelai casAvec(Clock clock) {
        return new AbandonnerCoureursHorsDelai(courses, inscriptions, passages, clock);
    }

    @Test
    @DisplayName("CA4 - X à t0 + 60 s : retourne 1, Chloé ABANDON (HORS_DELAI, boucle 1), Bruno et Tom EN_COURSE, une seule écriture")
    void doit_passer_en_abandon_le_coureur_sans_passage_sur_la_boucle_terminee() {
        garnirX();

        int abandons = casA(T0.plusSeconds(60)).executer(X).abandons();

        assertThat(abandons).isEqualTo(1);
        Inscription chloeApres = relue(chloe);
        assertThat(chloeApres.statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(chloeApres.motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(chloeApres.boucleAbandon()).contains(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - RG7 : second appel au même Clock : retourne 0, aucune écriture supplémentaire")
    void doit_etre_idempotent_au_meme_instant() {
        garnirX();
        casA(T0.plusSeconds(60)).executer(X);
        int ecrituresAvant = inscriptions.nombreDeMisesAJour;

        int abandons = casA(T0.plusSeconds(60)).executer(X).abandons();

        assertThat(abandons).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(ecrituresAvant);
    }

    @Test
    @DisplayName("CA4 - RG7 : appel plus tard sans nouvelle boucle : retourne 0, aucune écriture supplémentaire")
    void doit_etre_idempotent_plus_tard_dans_la_meme_boucle() {
        garnirX();
        casA(T0.plusSeconds(60)).executer(X);
        int ecrituresAvant = inscriptions.nombreDeMisesAJour;

        int abandons = casA(T0.plusMillis(60_001)).executer(X).abandons();

        assertThat(abandons).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(ecrituresAvant);
    }

    @Test
    @DisplayName("CA4 - X à t0 + 59,999 s : retourne 0, aucune écriture")
    void doit_ne_rien_faire_une_milliseconde_avant_la_bascule() {
        garnirX();

        int abandons = casA(T0.plusMillis(59_999)).executer(X).abandons();

        assertThat(abandons).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA2 - ADAPTÉ 4.10 (était « personne en abandon, aucune écriture ») RG1 : personne n'a de passage sur la boucle 1 : 0 abandon, les deux inscrits VAINQUEUR")
    void doit_declarer_vainqueurs_les_inscrits_quand_personne_n_a_boucle() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 1, T0));
        bruno = inscrire(X, 1);
        chloe = inscrire(X, 2);

        var resultat = casA(T0.plusSeconds(60)).executer(X);

        assertThat(resultat.abandons()).isZero();
        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.VAINQUEUR);
    }

    @Test
    @DisplayName("CA4 - un passage CORRECTION compte comme un passage valide : le coureur réintégré n'est pas abandonné")
    void doit_compter_un_passage_de_correction_comme_un_passage_valide() {
        garnirX();
        passages.poser(Passage.corriger(UUID.randomUUID(), chloe, 1));

        int abandons = casA(T0.plusSeconds(60)).executer(X).abandons();

        assertThat(abandons).isZero();
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA4 - RG5 : rattrapage à t0 + 3 min, Bruno {1, 2}, Chloé {1}, Tom {} : Tom boucle 1, Chloé boucle 2, Bruno vainqueur (4.9)")
    void doit_rattraper_les_boucles_manquees_dans_l_ordre() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 1, T0));
        bruno = inscrire(X, 1);
        chloe = inscrire(X, 2);
        tom = inscrire(X, 3);
        scan(bruno, 1);
        scan(bruno, 2);
        scan(chloe, 1);

        int abandons = casA(T0.plus(Duration.ofMinutes(3))).executer(X).abandons();

        assertThat(abandons).isEqualTo(2);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(relue(chloe).boucleAbandon()).contains(2);
        // 4.9 RG1 : Bruno seul passant de la boucle 2 est désormais vainqueur (4.8 : restait EN_COURSE)
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
    }

    @Test
    @DisplayName("CA4 - RG9 : la Course est chargée par parIdPourModification (verrou), jamais par parId")
    void doit_charger_la_course_sous_verrou() {
        garnirX();

        casA(T0.plusSeconds(60)).executer(X);

        assertThat(courses.nombreDeChargementsPourModification).isEqualTo(1);
        assertThat(courses.nombreDeChargementsParId).isZero();
    }

    @Test
    @DisplayName("CA4 - RG9 : le Clock est lu une seule fois par exécution")
    void doit_lire_le_clock_une_seule_fois() {
        garnirX();
        var clock = new ScenarioPassageDeTest.ClockAvanceALaLecture(T0.plusSeconds(60), Duration.ofSeconds(60));

        casAvec(clock).executer(X);

        assertThat(clock.lectures.get()).isEqualTo(1);
    }

    @ParameterizedTest(name = "CA4 - RG2 : Course {0} : 0, aucune écriture, aucune exception")
    @EnumSource(value = StatutCourse.class, names = {"EN_PREPARATION", "TERMINEE"})
    void doit_ignorer_une_course_qui_n_est_pas_en_cours(StatutCourse statut) {
        courses.enregistrer(course(EN_PREPARATION, statut, 1, T0));
        Inscription sans = inscrire(EN_PREPARATION, 1);
        Inscription avec = inscrire(EN_PREPARATION, 2);
        scan(avec, 1);

        int abandons = casA(T0.plus(Duration.ofDays(1))).executer(EN_PREPARATION).abandons();

        assertThat(abandons).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(relue(sans).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(courses.parId(EN_PREPARATION).orElseThrow().statut()).isEqualTo(statut);
    }

    @Test
    @DisplayName("CA4 - RG2 : Course EN_COURS sans heure de départ : 0, aucune écriture")
    void doit_ignorer_une_course_sans_heure_de_depart() {
        courses.enregistrer(course(SANS_DEPART, StatutCourse.EN_COURS, 1, null));
        inscrire(SANS_DEPART, 1);
        scan(inscrire(SANS_DEPART, 2), 1);

        int abandons = casA(T0.plus(Duration.ofDays(1))).executer(SANS_DEPART).abandons();

        assertThat(abandons).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA4 - Course introuvable : 0, aucune exception, aucune écriture")
    void doit_ignorer_une_course_introuvable() {
        assertThatCode(() -> {
            int abandons = casA(T0.plusSeconds(60)).executer(INCONNUE).abandons();
            assertThat(abandons).isZero();
        }).doesNotThrowAnyException();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA4 - RG18 : X (1 min) et Y (90 min) au même Clock : décisions indépendantes, Y ne perd personne")
    void doit_decider_independamment_pour_chaque_course() {
        garnirX();
        courses.enregistrer(course(Y, StatutCourse.EN_COURS, 90, T0));
        Inscription karim = inscrire(Y, 1);
        Inscription sophie = inscrire(Y, 2);
        scan(karim, 1);

        casA(T0.plusSeconds(60)).executer(X);
        int abandonsY = casA(T0.plusSeconds(60)).executer(Y).abandons();

        assertThat(abandonsY).isZero();
        assertThat(relue(sophie).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(karim).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.ABANDON);
    }

    @Test
    @DisplayName("CA4 - RG18 : le contrôle de X n'affecte pas les inscriptions de Y")
    void doit_ne_toucher_que_les_inscriptions_de_la_course_controlee() {
        garnirX();
        courses.enregistrer(course(Y, StatutCourse.EN_COURS, 1, T0));
        Inscription sophie = inscrire(Y, 1);

        casA(T0.plusSeconds(60)).executer(X);

        assertThat(relue(sophie).statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA4 - RG12 : aucun Passage créé, statut de la Course inchangé (décision d'abandon seule, k < M, deux passants), aucune écriture de Course")
    void doit_se_limiter_aux_inscriptions() {
        garnirX();
        int passagesAvant = passages.passages.size();

        casA(T0.plusSeconds(60)).executer(X);

        assertThat(passages.passages).hasSize(passagesAvant);
        assertThat(passages.nombreDEnregistrements).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - ADAPTÉ 4.10 (était « la Course reste EN_COURS ») RG2 : à la fin du temps (t0 + 6 min), pas de boucle 6 ; Bruno et David (2 passants sur la boucle 5 = M) VAINQUEUR, Chloé ABANDON boucle 5, Course TERMINEE")
    void doit_plafonner_au_nombre_max_de_boucles_et_partager_la_victoire() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 1, T0));
        bruno = inscrire(X, 1);
        chloe = inscrire(X, 2);
        Inscription david = inscrire(X, 3);
        for (int boucle = 1; boucle <= 5; boucle++) {
            scan(bruno, boucle);
            scan(david, boucle);
            if (boucle <= 4) {
                scan(chloe, boucle);
            }
        }
        courses.nombreDEnregistrements = 0;

        int abandons = casA(T0.plus(Duration.ofMinutes(6))).executer(X).abandons();

        assertThat(abandons).isEqualTo(1);
        assertThat(relue(chloe).boucleAbandon()).contains(5);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(david).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(courses.nombreDEnregistrements).isEqualTo(1);
    }

    // ---------- fabriques ----------

    private static Course course(UUID id, StatutCourse statut, int dureeBoucleMinutes, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard " + id, JOUR, statut,
                new ParametresBoucle(400, dureeBoucleMinutes, 5), 10, 5, Set.of(), demarreeLe);
    }

    private Inscription inscrire(UUID courseId, int dossard) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, UUID.randomUUID(), dossard,
                JetonQr.generer(), StatutInscription.EN_COURSE);
        inscriptions.enregistrer(inscription);
        return inscription;
    }

    private void scan(Inscription inscription, int boucle) {
        passages.poser(Passage.scanner(UUID.randomUUID(), inscription, boucle,
                T0.plus(Duration.ofMinutes(boucle - 1L)).plusSeconds(10)));
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
