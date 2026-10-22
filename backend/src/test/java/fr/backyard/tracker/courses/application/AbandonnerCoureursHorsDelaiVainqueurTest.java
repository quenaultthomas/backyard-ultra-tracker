package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Tests de l'incrément 4.9 (adaptés à 4.10, RG14, voir « ADAPTÉ 4.10 ») : vainqueur unique dans le cas d'usage de contrôle de fin de Boucle (CA3 ; RG1, RG5 à RG7,
 * RG14). Prolonge {@link AbandonnerCoureursHorsDelaiTest}.
 *
 * Signatures supposées : {@code new AbandonnerCoureursHorsDelai(DepotCourses, DepotInscriptions, DepotPassages,
 * Clock)} inchangé ; {@code Resultat executer(UUID courseId)} avec {@code int abandons()} et
 * {@code int vainqueurs()} (4.10 : nombre de vainqueurs, remplace le booléen) ; la Course terminée est écrite par {@code DepotCourses.enregistrer(Course)} (une seule
 * fois) et chaque Inscription modifiée (vainqueur comprise) par {@code DepotInscriptions.mettreAJour} (une fois).
 * Course X : boucles de 1 min, M = 5, t0 = 14:00:00Z ; Bruno (1), Chloé (2), Tom (3).
 * Adaptés à 7.1 (RG2, RG3) sans affaiblissement : chaque instant de contrôle est décalé de {@code GRACE}, délai de
 * grâce d'une Boucle de 1 min (15 s) ; les attentes sont inchangées.
 */
class AbandonnerCoureursHorsDelaiVainqueurTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    /** 7.1 RG1 : délai de grâce d'une Boucle de 1 min ; une Boucle n'est tranchée qu'à départ(N+1) + GRACE. */
    private static final Duration GRACE = Duration.ofSeconds(15);
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final UUID X = id(1);
    private static final UUID Y = id(2);

    private final DepotCoursesDeTest courses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();

    private Inscription bruno;
    private Inscription chloe;
    private Inscription tom;

    private void courseXAvec(Integer[] pourBruno, Integer[] pourChloe, Integer[] pourTom) {
        courses.enregistrer(course(X, 1, 5));
        bruno = inscrire(X, 1, pourBruno);
        chloe = inscrire(X, 2, pourChloe);
        tom = inscrire(X, 3, pourTom);
        courses.nombreDEnregistrements = 0;
    }

    private static Integer[] boucles(Integer... boucles) {
        return boucles;
    }

    private AbandonnerCoureursHorsDelai casA(Instant maintenant) {
        return casAvec(Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private AbandonnerCoureursHorsDelai casAvec(Clock clock) {
        return new AbandonnerCoureursHorsDelai(courses, inscriptions, passages, clock);
    }

    @Test
    @DisplayName("CA3 - RG1 : X à 14:01:00.000Z, Bruno {1}, Chloé {}, Tom {} : Bruno VAINQUEUR, Chloé et Tom ABANDON (HORS_DELAI, 1), Course TERMINEE")
    void doit_declarer_le_vainqueur_unique_et_terminer_la_course() {
        courseXAvec(boucles(1), boucles(), boucles());

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(resultat.abandons()).isEqualTo(2);
        Inscription brunoApres = relue(bruno);
        assertThat(brunoApres.statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(brunoApres.motifAbandon()).isEmpty();
        assertThat(brunoApres.boucleAbandon()).isEmpty();
        for (Inscription abandonnee : List.of(chloe, tom)) {
            assertThat(relue(abandonnee).statut()).isEqualTo(StatutInscription.ABANDON);
            assertThat(relue(abandonnee).motifAbandon()).contains(MotifAbandon.HORS_DELAI);
            assertThat(relue(abandonnee).boucleAbandon()).contains(1);
        }
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG6 : une seule sauvegarde de la Course et une mise à jour par Inscription concernée (3)")
    void doit_ecrire_la_course_une_fois_et_chaque_inscription_concernee_une_fois() {
        courseXAvec(boucles(1), boucles(), boucles());

        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(courses.nombreDEnregistrements).isEqualTo(1);
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(3);
    }

    @Test
    @DisplayName("CA3 - RG1/RG5 : le demarreeLe de la Course est conservé et aucun Passage n'est créé, modifié ni supprimé")
    void doit_conserver_l_heure_de_depart_et_les_passages() {
        courseXAvec(boucles(1), boucles(), boucles());
        List<Passage> passagesAvant = List.copyOf(passages.passages);

        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(courses.parId(X).orElseThrow().demarreeLe()).contains(T0);
        assertThat(passages.passages).containsExactlyElementsOf(passagesAvant);
        assertThat(passages.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA3 - RG7 : X à 14:00:59.999Z : rien n'est écrit, la Course reste EN_COURS")
    void doit_ne_rien_faire_une_milliseconde_avant_la_bascule() {
        courseXAvec(boucles(1), boucles(), boucles());

        var resultat = casA(T0.plusSeconds(60).plus(GRACE).minusMillis(1)).executer(X);

        assertThat(resultat.vainqueurs()).isZero();
        assertThat(resultat.abandons()).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA3 - RG7 : second appel (même Clock, puis plus tard) : Course TERMINEE ignorée, aucune écriture, pas de second vainqueur")
    void doit_ignorer_une_course_deja_terminee_au_second_appel() {
        courseXAvec(boucles(1), boucles(), boucles());
        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        int misesAJour = inscriptions.nombreDeMisesAJour;
        int sauvegardesCourse = courses.nombreDEnregistrements;

        var memeInstant = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        var plusTard = casA(T0.plusSeconds(60).plus(GRACE).plusMillis(1)).executer(X);

        assertThat(memeInstant.vainqueurs()).isZero();
        assertThat(memeInstant.abandons()).isZero();
        assertThat(plusTard.vainqueurs()).isZero();
        assertThat(plusTard.abandons()).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(misesAJour);
        assertThat(courses.nombreDEnregistrements).isEqualTo(sauvegardesCourse);
        assertThat(inscriptions.inscriptions).filteredOn(i -> i.statut() == StatutInscription.VAINQUEUR).hasSize(1);
    }

    @Test
    @DisplayName("CA3 - RG6 : Course chargée par parIdPourModification (jamais parId), Clock lu une seule fois")
    void doit_charger_sous_verrou_et_lire_le_clock_une_fois() {
        courseXAvec(boucles(1), boucles(), boucles());
        var clock = new ScenarioPassageDeTest.ClockAvanceALaLecture(T0.plusSeconds(60).plus(GRACE), Duration.ofSeconds(60));

        casAvec(clock).executer(X);

        assertThat(courses.nombreDeChargementsPourModification).isEqualTo(1);
        assertThat(courses.nombreDeChargementsParId).isZero();
        assertThat(clock.lectures.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3 - RG3 : Bruno {1}, Chloé {1}, Tom {} : Tom ABANDON, pas de vainqueur, Course EN_COURS, Course non écrite")
    void doit_laisser_la_course_en_cours_quand_deux_coureurs_ont_boucle() {
        courseXAvec(boucles(1), boucles(1), boucles());

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isZero();
        assertThat(resultat.abandons()).isEqualTo(1);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - ADAPTÉ 4.10 (était « aucune décision ») RG1 : Bruno {}, Chloé {}, Tom {} à 14:01:00.000Z : les trois VAINQUEUR sans motif ni boucle, Course TERMINEE, résultat 3 vainqueurs 0 abandon")
    void doit_declarer_vainqueurs_les_trois_quand_personne_n_a_boucle() {
        courseXAvec(boucles(), boucles(), boucles());

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(3);
        assertThat(resultat.abandons()).isZero();
        for (Inscription coureur : List.of(bruno, chloe, tom)) {
            assertThat(relue(coureur).statut()).isEqualTo(StatutInscription.VAINQUEUR);
            assertThat(relue(coureur).motifAbandon()).isEmpty();
            assertThat(relue(coureur).boucleAbandon()).isEmpty();
        }
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(courses.parId(X).orElseThrow().demarreeLe()).contains(T0);
        assertThat(courses.nombreDEnregistrements).isEqualTo(1);
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(3);
        assertThat(passages.passages).isEmpty();
    }

    @Test
    @DisplayName("CA2 - ADAPTÉ 4.10 (était « aucune décision ») RG1 : Bruno {1}, Chloé {1}, Tom {1} puis personne sur la boucle 2 à 14:02:00.000Z : les trois VAINQUEUR, Course TERMINEE")
    void doit_declarer_vainqueurs_les_trois_quand_personne_ne_boucle_la_boucle_suivante() {
        courseXAvec(boucles(1), boucles(1), boucles(1));
        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);

        var resultat = casA(T0.plusSeconds(120).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(3);
        assertThat(resultat.abandons()).isZero();
        assertThat(inscriptions.inscriptions).allMatch(i -> i.statut() == StatutInscription.VAINQUEUR);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG2 : à 14:02:00.000Z, Bruno {1, 2}, Chloé {1}, Tom {1} : Bruno VAINQUEUR, Chloé et Tom ABANDON boucle 2, TERMINEE")
    void doit_declarer_le_vainqueur_a_la_deuxieme_boucle() {
        courseXAvec(boucles(1, 2), boucles(1), boucles(1));
        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);

        var resultat = casA(T0.plusSeconds(120).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).boucleAbandon()).contains(2);
        assertThat(relue(tom).boucleAbandon()).contains(2);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG2/RG7 : rattrapage à 14:03:00.000Z, Bruno {1, 2}, Chloé {1}, Tom {} : Tom boucle 1, Bruno VAINQUEUR, Chloé boucle 2, TERMINEE")
    void doit_rattraper_puis_terminer_a_la_boucle_du_vainqueur() {
        courseXAvec(boucles(1, 2), boucles(1), boucles());

        var resultat = casA(T0.plus(Duration.ofMinutes(3)).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(resultat.abandons()).isEqualTo(2);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(relue(chloe).boucleAbandon()).contains(2);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG2/RG7 : Bruno {1}, Chloé {}, Tom {} avec 3 boucles de retard : décision à la boucle 1 (abandons boucle 1)")
    void doit_decider_a_la_premiere_boucle_meme_apres_plusieurs_boucles_de_retard() {
        courseXAvec(boucles(1), boucles(), boucles());

        casA(T0.plus(Duration.ofMinutes(3)).plus(GRACE)).executer(X);

        assertThat(relue(chloe).boucleAbandon()).contains(1);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
    }

    @Test
    @DisplayName("CA3 - RG4 : Bruno seul inscrit {1} à 14:01:00.000Z : VAINQUEUR, Course TERMINEE, aucun abandon")
    void doit_declarer_vainqueur_le_seul_inscrit() {
        courses.enregistrer(course(X, 1, 5));
        bruno = inscrire(X, 1, boucles(1));

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(resultat.abandons()).isZero();
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA2 - ADAPTÉ 4.10 (était « aucune décision ») RG1 : Bruno seul inscrit {} : VAINQUEUR, Course TERMINEE, résultat 1 vainqueur")
    void doit_declarer_vainqueur_le_seul_inscrit_sans_passage() {
        courses.enregistrer(course(X, 1, 5));
        bruno = inscrire(X, 1, boucles());

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG4 : Chloé seule EN_COURSE (Bruno et Tom ABANDON déjà) {1, 2} à 14:02:00.000Z : VAINQUEUR, TERMINEE, abandons existants intacts")
    void doit_declarer_vainqueur_la_seule_survivante() {
        courseXAvec(boucles(1), boucles(1, 2), boucles(1));
        inscriptions.mettreAJour(bruno.abandonner(MotifAbandon.HORS_DELAI, 2));
        inscriptions.mettreAJour(tom.abandonner(MotifAbandon.MANUEL, 1));
        inscriptions.nombreDeMisesAJour = 0;

        var resultat = casA(T0.plusSeconds(120).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(resultat.abandons()).isZero();
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(bruno).motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(relue(bruno).boucleAbandon()).contains(2);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(1);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG1 : un Passage CORRECTION compte comme un SCAN : Bruno {1}, Chloé {1 en correction}, Tom {} : pas de vainqueur")
    void doit_compter_un_passage_de_correction_dans_les_passants() {
        courseXAvec(boucles(1), boucles(), boucles());
        passages.poser(Passage.corriger(UUID.randomUUID(), chloe, 1));

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isZero();
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA3 - RG8 : M = 2, à 14:02:00.000Z, Bruno {1, 2}, Chloé {1} : Bruno VAINQUEUR, Chloé ABANDON boucle 2, TERMINEE")
    void doit_declarer_le_vainqueur_unique_de_la_derniere_boucle_autorisee() {
        courses.enregistrer(course(X, 1, 2));
        bruno = inscrire(X, 1, boucles(1, 2));
        chloe = inscrire(X, 2, boucles(1));

        var resultat = casA(T0.plusSeconds(120).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).boucleAbandon()).contains(2);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA2 - ADAPTÉ 4.10 (était « Course EN_COURS ») RG2 : W (M = 2), à 14:02:00.000Z, Bruno {1, 2}, Chloé {1, 2}, Tom {1} : Bruno et Chloé VAINQUEUR, Tom ABANDON (HORS_DELAI, 2), TERMINEE")
    void doit_partager_la_victoire_a_la_derniere_boucle_autorisee() {
        courses.enregistrer(course(X, 1, 2));
        bruno = inscrire(X, 1, boucles(1, 2));
        chloe = inscrire(X, 2, boucles(1, 2));
        tom = inscrire(X, 3, boucles(1));
        courses.nombreDEnregistrements = 0;

        var resultat = casA(T0.plusSeconds(120).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(resultat.abandons()).isEqualTo(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(relue(tom).boucleAbandon()).contains(2);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(courses.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - RG2/RG6 : W (M = 2), à 14:01:00.000Z (fin de la Boucle 1, k < M) avec les trois passants : rien, Course EN_COURS")
    void doit_ne_rien_decider_a_la_fin_de_la_premiere_boucle_quand_m_vaut_2() {
        courses.enregistrer(course(X, 1, 2));
        bruno = inscrire(X, 1, boucles(1));
        chloe = inscrire(X, 2, boucles(1));
        tom = inscrire(X, 3, boucles(1));

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isZero();
        assertThat(resultat.abandons()).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA3 - RG14 : Y (boucles de 90 min) au même Clock que X terminée : Y non terminée, ses coureurs intacts")
    void doit_ne_pas_toucher_une_autre_course_non_terminee() {
        courseXAvec(boucles(1), boucles(), boucles());
        courses.enregistrer(course(Y, 90, 5));
        Inscription karim = inscrire(Y, 1, boucles(1));
        Inscription sophie = inscrire(Y, 2, boucles());

        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        var resultatY = casA(T0.plusSeconds(60).plus(GRACE)).executer(Y);

        assertThat(resultatY.vainqueurs()).isZero();
        assertThat(resultatY.abandons()).isZero();
        assertThat(courses.parId(Y).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(relue(karim).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(sophie).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - RG14 : deux Courses terminées au même Clock ont chacune leur vainqueur et leurs abandons")
    void doit_terminer_chaque_course_avec_ses_propres_inscriptions() {
        courseXAvec(boucles(1), boucles(), boucles());
        courses.enregistrer(course(Y, 1, 5));
        Inscription karim = inscrire(Y, 1, boucles());
        Inscription sophie = inscrire(Y, 2, boucles(1));

        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        casA(T0.plusSeconds(60).plus(GRACE)).executer(Y);

        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(sophie).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(karim).boucleAbandon()).contains(1);
        assertThat(inscriptions.inscriptions).filteredOn(i -> i.courseId().equals(X)
                && i.statut() == StatutInscription.VAINQUEUR).hasSize(1);
    }

    // ---------- 4.10 : vainqueurs partagés ----------

    @Test
    @DisplayName("CA2 - RG1/RG6 : X à 14:00:59.999Z, Bruno {}, Chloé {}, Tom {} : rien, Course EN_COURS")
    void doit_ne_rien_faire_une_milliseconde_avant_la_fin_de_la_boucle_sans_passage() {
        courseXAvec(boucles(), boucles(), boucles());

        var resultat = casA(T0.plusSeconds(60).plus(GRACE).minusMillis(1)).executer(X);

        assertThat(resultat.vainqueurs()).isZero();
        assertThat(resultat.abandons()).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.nombreDEnregistrements).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA2 - RG7 : vainqueurs partagés, second appel (même Clock, puis plus tard) : Course TERMINEE ignorée, aucune écriture, pas de vainqueur de plus")
    void doit_ignorer_une_course_terminee_par_vainqueurs_partages_au_second_appel() {
        courseXAvec(boucles(), boucles(), boucles());
        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        int misesAJour = inscriptions.nombreDeMisesAJour;
        int sauvegardesCourse = courses.nombreDEnregistrements;

        var memeInstant = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        var plusTard = casA(T0.plusSeconds(60).plus(GRACE).plusMillis(1)).executer(X);

        assertThat(memeInstant.vainqueurs()).isZero();
        assertThat(memeInstant.abandons()).isZero();
        assertThat(plusTard.vainqueurs()).isZero();
        assertThat(plusTard.abandons()).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(misesAJour);
        assertThat(courses.nombreDEnregistrements).isEqualTo(sauvegardesCourse);
        assertThat(inscriptions.inscriptions).filteredOn(i -> i.statut() == StatutInscription.VAINQUEUR).hasSize(3);
    }

    @Test
    @DisplayName("CA2 - RG7 : vainqueurs partagés, Course chargée par parIdPourModification (jamais parId), Clock lu une seule fois")
    void doit_charger_sous_verrou_et_lire_le_clock_une_fois_pour_des_vainqueurs_partages() {
        courseXAvec(boucles(), boucles(), boucles());
        var clock = new ScenarioPassageDeTest.ClockAvanceALaLecture(T0.plusSeconds(60).plus(GRACE), Duration.ofSeconds(60));

        casAvec(clock).executer(X);

        assertThat(courses.nombreDeChargementsPourModification).isEqualTo(1);
        assertThat(courses.nombreDeChargementsParId).isZero();
        assertThat(clock.lectures.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - RG3 : rattrapage à 14:03:00.000Z, Bruno {1}, Chloé {1}, Tom {} : Tom ABANDON boucle 1, Bruno et Chloé VAINQUEUR, TERMINEE (résultat 2 vainqueurs, 1 abandon)")
    void doit_rattraper_puis_partager_la_victoire() {
        courseXAvec(boucles(1), boucles(1), boucles());

        var resultat = casA(T0.plus(Duration.ofMinutes(3)).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(resultat.abandons()).isEqualTo(1);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA2 - RG3 : cohabitation à 14:03:00.000Z, Bruno {1, 2}, Chloé {1, 2}, Tom {1} : Tom ABANDON boucle 2, Bruno et Chloé VAINQUEUR, TERMINEE")
    void doit_abandonner_puis_partager_la_victoire() {
        courseXAvec(boucles(1, 2), boucles(1, 2), boucles(1));

        var resultat = casA(T0.plus(Duration.ofMinutes(3)).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(resultat.abandons()).isEqualTo(1);
        assertThat(relue(tom).boucleAbandon()).contains(2);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA2 - RG1/RG3 : une Inscription déjà ABANDON n'est jamais vainqueur ni réécrite")
    void doit_ne_pas_toucher_les_inscriptions_deja_en_abandon_lors_du_partage() {
        courseXAvec(boucles(), boucles(), boucles(1));
        inscriptions.mettreAJour(tom.abandonner(MotifAbandon.MANUEL, 1));
        inscriptions.nombreDeMisesAJour = 0;

        var resultat = casA(T0.plusSeconds(120).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2 - RG5 : Course démarrée sans aucune Inscription : Course EN_COURS, aucune écriture")
    void doit_laisser_la_course_en_cours_sans_inscription() {
        courses.enregistrer(course(X, 1, 5));
        courses.nombreDEnregistrements = 0;

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - RG5 : aucune Inscription EN_COURSE (toutes ABANDON ou VAINQUEUR) : Course EN_COURS, aucune écriture")
    void doit_laisser_la_course_en_cours_quand_aucune_inscription_n_est_en_course() {
        courseXAvec(boucles(1), boucles(), boucles());
        inscriptions.mettreAJour(bruno.devenirVainqueur());
        inscriptions.mettreAJour(chloe.abandonner(MotifAbandon.MANUEL, 1));
        inscriptions.mettreAJour(tom.abandonner(MotifAbandon.HORS_DELAI, 1));
        inscriptions.nombreDeMisesAJour = 0;

        var resultat = casA(T0.plusSeconds(120).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isZero();
        assertThat(resultat.abandons()).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isZero();
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(courses.nombreDEnregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - RG6 : Z (M = 1) à 14:01:00.000Z, Bruno {1}, Chloé {1}, Tom {} : Bruno et Chloé VAINQUEUR, Tom ABANDON boucle 1, TERMINEE")
    void doit_partager_la_victoire_quand_une_seule_boucle_est_autorisee() {
        courses.enregistrer(course(X, 1, 1));
        bruno = inscrire(X, 1, boucles(1));
        chloe = inscrire(X, 2, boucles(1));
        tom = inscrire(X, 3, boucles());

        var resultat = casA(T0.plusSeconds(60).plus(GRACE)).executer(X);

        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA2 - RG13 : X terminée par vainqueurs partagés, Y (90 min) au même Clock n'est pas terminée et garde ses inscrits")
    void doit_isoler_la_fin_par_vainqueurs_partages_des_autres_courses() {
        courseXAvec(boucles(), boucles(), boucles());
        courses.enregistrer(course(Y, 90, 5));
        Inscription karim = inscrire(Y, 1, boucles());
        Inscription sophie = inscrire(Y, 2, boucles());

        casA(T0.plusSeconds(60).plus(GRACE)).executer(X);
        var resultatY = casA(T0.plusSeconds(60).plus(GRACE)).executer(Y);

        assertThat(resultatY.vainqueurs()).isZero();
        assertThat(courses.parId(Y).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(relue(karim).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(relue(sophie).statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(courses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    // ---------- fabriques ----------

    private static Course course(UUID id, int dureeBoucleMinutes, int nombreMaxBoucles) {
        return Course.reconstituer(id, "Backyard " + id, JOUR, StatutCourse.EN_COURS,
                new ParametresBoucle(400, dureeBoucleMinutes, 5), 10, nombreMaxBoucles, Set.of(), T0);
    }

    private Inscription inscrire(UUID courseId, int dossard, Integer[] boucles) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, UUID.randomUUID(), dossard,
                JetonQr.generer(), StatutInscription.EN_COURSE);
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
