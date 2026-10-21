package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.OriginePassage;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 6.3 : ConsulterSuivi étendu aux statistiques (CA8 à CA10 ; RG2, RG3, RG8, RG9, RG10).
 * Signatures attendues :
 * <ul>
 * <li>{@code ConsulterSuivi(DepotCourses, DepotInscriptions, AnnuairePseudos, DepotPassages, Clock)} ;</li>
 * <li>{@code Suivi(Course course, Instant heureServeur, Optional<Chronometrage> chronometrage,
 * List<CoureurSuivi> coureursEnCourse, List<AbandonSuivi> abandons, List<CoureurSuivi> vainqueurs,
 * StatistiquesCourse statistiques)} : les statistiques sont celles de {@code Course.statistiques(heureServeur,
 * passages)} (domaine), le cas d'usage ne calcule rien ; les paramètres de Boucle se lisent par
 * {@code suivi.course().parametresBoucle()} ;</li>
 * <li>{@code StatistiquesCourse} : voir {@code CourseStatistiquesTest} ;</li>
 * <li>aucun Passage lu (ni Inscription, ni pseudo) quand {@code !course.exposeSesCoureursAuPublic()} ; aucune lecture
 * de Passages sans Inscription ; sinon une seule lecture {@code DepotPassages.parInscriptions} avec toutes les
 * Inscriptions de la Course ; la Course est lue en premier.</li>
 * </ul>
 */
class ConsulterSuiviStatistiquesTest {

    private static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    private static final UUID X = id(1);
    private static final UUID Y = id(2);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEspionDeTest inscriptions = new DepotInscriptionsEspionDeTest();
    private final AnnuaireSuiviDeTest annuaire = new AnnuaireSuiviDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();

    @BeforeEach
    void garnir() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_COURS, T0, 6_700));
        depotCourses.enregistrer(course(Y, StatutCourse.EN_COURS, T0, 1_000));
    }

    // ---------- CA8 ----------

    @Test
    @DisplayName("CA8 - EN_PREPARATION : ni Inscriptions, ni pseudos, ni Passages lus ; statistiques à zéro, listes vides")
    void doit_ne_rien_lire_et_renvoyer_des_statistiques_a_zero_pour_une_course_en_preparation() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_PREPARATION, null, 6_700));
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        Inscription bruno = inscrire(X, 2, "Bruno", StatutInscription.EN_COURSE);
        Inscription karim = inscrire(X, 3, "Karim", StatutInscription.EN_COURSE);
        passages.poser(scan(alice, 1, T0.plus(Duration.ofMinutes(50))));
        passages.poser(scan(bruno, 1, T0.plus(Duration.ofMinutes(40))));
        passages.poser(scan(karim, 1, T0.plus(Duration.ofMinutes(55))));
        inscriptions.appels.clear();

        var suivi = consulterA(T0.plus(Duration.ofHours(3))).executer(X);

        assertThat(inscriptions.appels).isEmpty();
        assertThat(annuaire.appels).isEmpty();
        assertThat(passages.nombreDeLecturesGroupees).isZero();
        assertThat(passages.nombreDeLecturesParBoucle).isZero();
        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(suivi.abandons()).isEmpty();
        assertThat(suivi.vainqueurs()).isEmpty();
        assertThat(suivi.course().parametresBoucle().distanceMetres()).isEqualTo(6_700);
        assertThat(suivi.course().parametresBoucle().denivelePositifMetres()).isEqualTo(35);
        assertThat(suivi.statistiques().bouclesTerminees()).isZero();
        assertThat(suivi.statistiques().distanceCumuleeMetres()).isZero();
        assertThat(suivi.statistiques().denivelePositifCumuleMetres()).isZero();
        assertThat(suivi.statistiques().allureSecondesParKilometre()).isEmpty();
        assertThat(suivi.statistiques().dureeFinaleMillisecondes()).isEmpty();
    }

    @Test
    @DisplayName("CA8 - EN_COURS sans Inscription : Passages non lus, Boucles terminées selon le temps")
    void doit_ne_pas_lire_les_passages_sans_inscription_et_suivre_le_temps() {
        var suivi = consulterA(T0.plus(Duration.ofMinutes(150))).executer(X);

        assertThat(passages.nombreDeLecturesGroupees).isZero();
        assertThat(passages.nombreDeLecturesParBoucle).isZero();
        assertThat(suivi.statistiques().bouclesTerminees()).isEqualTo(2);
        assertThat(suivi.statistiques().distanceCumuleeMetres()).isEqualTo(13_400L);
        assertThat(suivi.statistiques().denivelePositifCumuleMetres()).isEqualTo(70L);
        assertThat(suivi.statistiques().allureSecondesParKilometre()).isEmpty();
        assertThat(suivi.statistiques().dureeFinaleMillisecondes()).isEmpty();
    }

    @Test
    @DisplayName("CA8 - TERMINEE sans Inscription : Passages non lus, 0 Boucle, durée finale 0")
    void doit_ne_pas_lire_les_passages_d_une_course_terminee_sans_inscription() {
        depotCourses.enregistrer(course(X, StatutCourse.TERMINEE, T0, 6_700));

        var suivi = consulterA(T0.plus(Duration.ofHours(3))).executer(X);

        assertThat(passages.nombreDeLecturesGroupees).isZero();
        assertThat(suivi.statistiques().bouclesTerminees()).isZero();
        assertThat(suivi.statistiques().dureeFinaleMillisecondes()).hasValue(0L);
    }

    @Test
    @DisplayName("CA8 - EN_COURS sans heure de départ : statistiques à zéro et sans allure malgré des Passages")
    void doit_renvoyer_des_statistiques_a_zero_pour_une_course_en_cours_sans_heure_de_depart() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_COURS, null, 6_700));
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        passages.poser(scan(alice, 1, T0.plus(Duration.ofMinutes(50))));

        var suivi = consulterA(T0.plus(Duration.ofHours(3))).executer(X);

        assertThat(suivi.statistiques().bouclesTerminees()).isZero();
        assertThat(suivi.statistiques().distanceCumuleeMetres()).isZero();
        assertThat(suivi.statistiques().allureSecondesParKilometre()).isEmpty();
        assertThat(suivi.statistiques().dureeFinaleMillisecondes()).isEmpty();
    }

    // ---------- CA9 ----------

    @Test
    @DisplayName("CA9 - une seule lecture groupée avec les identifiants des 3 Inscriptions (EN_COURSE, ABANDON, VAINQUEUR)")
    void doit_lire_les_passages_en_un_seul_appel_avec_toutes_les_inscriptions() {
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        Inscription bruno = inscrireAbandon(X, 2, "Bruno", MotifAbandon.MANUEL, 1);
        Inscription karim = inscrire(X, 3, "Karim", StatutInscription.VAINQUEUR);
        poserLesTroisScansDuCa4(alice, bruno, karim);

        consulterA(T0.plus(Duration.ofMinutes(61))).executer(X);

        assertThat(passages.nombreDeLecturesGroupees).isEqualTo(1);
        assertThat(passages.appelsGroupes.getFirst()).containsExactlyInAnyOrder(alice.id(), bruno.id(), karim.id());
        assertThat(passages.nombreDeLecturesParBoucle).isZero();
    }

    @Test
    @DisplayName("CA9 - les Passages de toutes les Inscriptions, quel que soit leur statut, comptent dans l'allure")
    void doit_compter_dans_l_allure_les_passages_des_en_course_des_abandons_et_des_vainqueurs() {
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        Inscription bruno = inscrireAbandon(X, 2, "Bruno", MotifAbandon.MANUEL, 1);
        Inscription karim = inscrire(X, 3, "Karim", StatutInscription.VAINQUEUR);
        poserLesTroisScansDuCa4(alice, bruno, karim);

        var suivi = consulterA(T0.plus(Duration.ofMinutes(61))).executer(X);

        // écarts 50, 40 et 55 min : 8 700 000 ms / (3 x 6700 m) = 432,8
        assertThat(suivi.statistiques().allureSecondesParKilometre()).hasValue(433L);
        assertThat(suivi.statistiques().bouclesTerminees()).isEqualTo(1);
        assertThat(suivi.statistiques().distanceCumuleeMetres()).isEqualTo(6_700L);
    }

    @Test
    @DisplayName("CA9 - la lecture reste unique avec 40 Inscriptions de 3 Passages chacune (pas de N+1)")
    void doit_garder_une_seule_lecture_de_passages_quel_que_soit_le_nombre_d_inscrits() {
        var ids = new ArrayList<UUID>();
        for (int dossard = 1; dossard <= 40; dossard++) {
            Inscription inscription = inscrire(X, dossard, "Coureur" + dossard, StatutInscription.EN_COURSE);
            ids.add(inscription.id());
            for (int boucle = 1; boucle <= 3; boucle++) {
                passages.poser(scan(inscription, boucle, T0.plus(Duration.ofMinutes((boucle - 1) * 60L + 50))));
            }
        }

        consulterA(T0.plus(Duration.ofMinutes(190))).executer(X);

        assertThat(passages.nombreDeLecturesGroupees).isEqualTo(1);
        assertThat(passages.appelsGroupes.getFirst()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(passages.nombreDeLecturesParBoucle).isZero();
    }

    @Test
    @DisplayName("CA9 - isolation : les Passages d'une autre Course n'influencent pas les statistiques ; chaque Course a ses paramètres")
    void doit_ignorer_les_passages_d_une_autre_course() {
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        Inscription bruno = inscrire(Y, 1, "Bruno", StatutInscription.EN_COURSE);
        passages.poser(scan(alice, 1, T0.plus(Duration.ofMinutes(50))));
        passages.poser(scan(bruno, 1, T0.plus(Duration.ofMinutes(10))));

        var suiviX = consulterA(T0.plus(Duration.ofMinutes(61))).executer(X);
        var suiviY = consulterA(T0.plus(Duration.ofMinutes(61))).executer(Y);

        // X : 3 000 000 ms / 6700 m = 447,76 ; Y : 600 000 ms / 1000 m = 600
        assertThat(suiviX.statistiques().allureSecondesParKilometre()).hasValue(448L);
        assertThat(suiviY.statistiques().allureSecondesParKilometre()).hasValue(600L);
        assertThat(suiviX.statistiques().distanceCumuleeMetres()).isEqualTo(6_700L);
        assertThat(suiviY.statistiques().distanceCumuleeMetres()).isEqualTo(1_000L);
        assertThat(passages.appelsGroupes.getFirst()).containsExactly(alice.id());
    }

    @Test
    @DisplayName("CA9 - Passages CORRECTION : comptés en Boucles (TERMINEE) mais exclus de l'allure")
    void doit_exclure_les_corrections_de_l_allure_et_les_compter_en_boucles_pour_une_course_terminee() {
        depotCourses.enregistrer(course(X, StatutCourse.TERMINEE, T0, 6_700));
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.VAINQUEUR);
        passages.poser(scan(alice, 1, T0.plus(Duration.ofMinutes(50))));
        passages.poser(Passage.corriger(UUID.randomUUID(), alice, 2));
        passages.poser(Passage.corriger(UUID.randomUUID(), alice, 3));

        var suivi = consulterA(T0.plus(Duration.ofHours(10))).executer(X);

        assertThat(suivi.statistiques().bouclesTerminees()).isEqualTo(3);
        assertThat(suivi.statistiques().dureeFinaleMillisecondes()).hasValue(10_800_000L);
        assertThat(suivi.statistiques().allureSecondesParKilometre()).hasValue(448L);
    }

    @Test
    @DisplayName("CA9 - TERMINEE : mêmes statistiques à deux instants d'horloge différents, aucune écriture")
    void doit_figer_les_statistiques_d_une_course_terminee() {
        depotCourses.enregistrer(course(X, StatutCourse.TERMINEE, T0, 6_700));
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.VAINQUEUR);
        Inscription bruno = inscrireAbandon(X, 2, "Bruno", MotifAbandon.HORS_DELAI, 3);
        for (int boucle = 1; boucle <= 3; boucle++) {
            passages.poser(scan(alice, boucle, T0.plus(Duration.ofMinutes((boucle - 1) * 60L + 50))));
        }
        passages.poser(scan(bruno, 1, T0.plus(Duration.ofMinutes(40))));
        passages.poser(scan(bruno, 2, T0.plus(Duration.ofMinutes(100))));
        depotCourses.nombreDEnregistrements = 0;

        var tot = consulterA(T0.plus(Duration.ofHours(3))).executer(X).statistiques();
        var tard = consulterA(T0.plus(Duration.ofHours(10))).executer(X).statistiques();

        assertThat(tard).isEqualTo(tot);
        assertThat(tot.bouclesTerminees()).isEqualTo(3);
        assertThat(tot.distanceCumuleeMetres()).isEqualTo(20_100L);
        assertThat(tot.denivelePositifCumuleMetres()).isEqualTo(105L);
        assertThat(tot.dureeFinaleMillisecondes()).hasValue(10_800_000L);
        assertThat(depotCourses.nombreDEnregistrements).isZero();
        assertThat(passages.nombreDEnregistrements).isZero();
    }

    // ---------- CA10 ----------

    @Test
    @DisplayName("CA10 - la Course est lue avant les Inscriptions, les pseudos et les Passages")
    void doit_lire_la_course_avant_toute_autre_lecture() {
        List<String> journal = new ArrayList<>();
        depotCourses.journalDesLectures = journal;
        inscriptions.journalDesLectures = journal;
        annuaire.journalDesLectures = journal;
        passages.journalDesLectures = journal;
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        passages.poser(scan(alice, 1, T0.plus(Duration.ofMinutes(50))));
        journal.clear();

        consulterA(T0.plus(Duration.ofMinutes(61))).executer(X);

        assertThat(journal).hasSize(4).first().isEqualTo("course");
        assertThat(journal).containsExactlyInAnyOrder("course", "inscriptions", "pseudos", "passages");
    }

    @Test
    @DisplayName("CA10 - heureServeur, chronométrage et listes restent corrects avec des Passages présents")
    void doit_conserver_heure_serveur_chronometrage_et_listes_avec_des_passages() {
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        Inscription bruno = inscrireAbandon(X, 2, "Bruno", MotifAbandon.HORS_DELAI, 1);
        passages.poser(scan(alice, 1, T0.plus(Duration.ofMinutes(50))));
        passages.poser(scan(alice, 2, T0.plus(Duration.ofMinutes(100))));
        passages.poser(scan(bruno, 1, T0.plus(Duration.ofMinutes(40))));
        Instant maintenant = T0.plus(Duration.ofMinutes(130));

        var suivi = consulterA(maintenant).executer(X);

        assertThat(suivi.heureServeur()).isEqualTo(maintenant);
        var chrono = suivi.chronometrage().orElseThrow();
        assertThat(chrono.boucleCourante()).isEqualTo(3);
        assertThat(chrono.dureeEcoulee()).isEqualTo(Duration.ofMinutes(130));
        assertThat(chrono.tempsAvantFinBoucle()).isEqualTo(Duration.ofMinutes(50));
        assertThat(suivi.coureursEnCourse()).extracting(c -> c.dossard(), c -> c.pseudo())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "Alice"));
        assertThat(suivi.abandons()).extracting(a -> a.dossard(), a -> a.pseudo())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "Bruno"));
        assertThat(suivi.abandons().getFirst().boucleAbandon()).contains(1);
        assertThat(suivi.abandons().getFirst().motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(suivi.vainqueurs()).isEmpty();
    }

    @Test
    @DisplayName("CA10 - toString de la réponse n'affiche que l'identifiant de la Course (ni pseudo, ni jeton, ni Passage)")
    void doit_masquer_pseudos_jetons_et_passages_dans_les_to_string() {
        Inscription alice = inscrire(X, 1, "Alicette", StatutInscription.EN_COURSE);
        Passage passage = scan(alice, 1, T0.plus(Duration.ofMinutes(50)));
        passages.poser(passage);

        var suivi = consulterA(T0.plus(Duration.ofMinutes(61))).executer(X);

        assertThat(suivi.toString()).contains(X.toString())
                .doesNotContain("Alicette", alice.jetonQr().valeur(), passage.id().toString(),
                        alice.id().toString());
        assertThat(suivi.coureursEnCourse().getFirst().toString()).doesNotContain("Alicette",
                alice.jetonQr().valeur());
    }

    // ---------- fabriques ----------

    private ConsulterSuivi consulterA(Instant maintenant) {
        return new ConsulterSuivi(depotCourses, inscriptions.port, annuaire, passages, new DepotLogosDeTest(),
                Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private void poserLesTroisScansDuCa4(Inscription alice, Inscription bruno, Inscription karim) {
        passages.poser(scan(alice, 1, T0.plus(Duration.ofMinutes(50))));
        passages.poser(scan(bruno, 1, T0.plus(Duration.ofMinutes(40))));
        passages.poser(scan(karim, 1, T0.plus(Duration.ofMinutes(55))));
    }

    private static Passage scan(Inscription inscription, int boucle, Instant scanneLe) {
        return Passage.reconstituer(UUID.randomUUID(), inscription.id(), boucle, scanneLe, OriginePassage.SCAN);
    }

    private Inscription inscrire(UUID courseId, int dossard, String pseudo, StatutInscription statut) {
        return enregistrer(courseId, dossard, pseudo, statut, null, null);
    }

    private Inscription inscrireAbandon(UUID courseId, int dossard, String pseudo, MotifAbandon motif, int boucle) {
        return enregistrer(courseId, dossard, pseudo, StatutInscription.ABANDON, motif, boucle);
    }

    private Inscription enregistrer(UUID courseId, int dossard, String pseudo, StatutInscription statut,
                                    MotifAbandon motif, Integer boucle) {
        UUID compteId = UUID.randomUUID();
        annuaire.pseudos.put(compteId, pseudo);
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                JetonQr.generer(), statut, motif, boucle);
        inscriptions.enMemoire.enregistrer(inscription);
        return inscription;
    }

    private static Course course(UUID id, StatutCourse statut, Instant demarreeLe, int distanceBoucleMetres) {
        return Course.reconstituer(id, "Backyard " + id, LocalDate.of(2026, 10, 9), statut,
                new ParametresBoucle(distanceBoucleMetres, 60, 35), 100, 5, Set.of(), demarreeLe);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
