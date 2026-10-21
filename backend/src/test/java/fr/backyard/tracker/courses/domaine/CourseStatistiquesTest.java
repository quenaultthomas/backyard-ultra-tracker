package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 6.3 : statistiques de Course dérivées dans le domaine (CA1 à CA7 ; RG4 à RG8, RG10).
 * Signatures attendues :
 * <ul>
 * <li>{@code Course#statistiques(Instant maintenant, Collection<Passage> passages)} retourne
 * {@code StatistiquesCourse} ; les Passages sont ceux (toute origine) de toutes les Inscriptions de la Course ;</li>
 * <li>{@code StatistiquesCourse} (record du domaine) : {@code int bouclesTerminees()},
 * {@code long distanceCumuleeMetres()}, {@code long denivelePositifCumuleMetres()},
 * {@code OptionalLong allureSecondesParKilometre()}, {@code OptionalLong dureeFinaleMillisecondes()} ;</li>
 * <li>{@code Course#bouclesTerminees(Instant)} et {@code Course#chronometrage(Instant)} existantes sont réutilisées.</li>
 * </ul>
 */
class CourseStatistiquesTest {

    private static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    private static final long HEURE_MS = 3_600_000L;
    private static final UUID ALICE = id(11);
    private static final UUID BRUNO = id(12);
    private static final UUID KARIM = id(13);

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - EN_COURS à t0 + 2h30 : 2 Boucles, 13 400 m, 70 m D+")
    void doit_compter_deux_boucles_terminees_et_cumuler_distance_et_denivele_a_deux_heures_trente() {
        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(150)), List.of());

        assertThat(stats.bouclesTerminees()).isEqualTo(2);
        assertThat(stats.distanceCumuleeMetres()).isEqualTo(13_400L);
        assertThat(stats.denivelePositifCumuleMetres()).isEqualTo(70L);
    }

    @Test
    @DisplayName("CA1 - un Passage sur la Boucle courante ne fait pas augmenter les Boucles terminées")
    void doit_ignorer_un_passage_de_la_boucle_courante_pour_les_boucles_terminees() {
        var passages = List.of(scan(ALICE, 3, T0.plus(Duration.ofMinutes(145))));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(150)), passages);

        assertThat(stats.bouclesTerminees()).isEqualTo(2);
        assertThat(stats.distanceCumuleeMetres()).isEqualTo(13_400L);
        assertThat(stats.denivelePositifCumuleMetres()).isEqualTo(70L);
    }

    @Test
    @DisplayName("CA1 - au départ (t0) : 0 Boucle, 0 m, 0 m D+")
    void doit_etre_a_zero_au_depart() {
        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0, List.of());

        assertThat(stats.bouclesTerminees()).isZero();
        assertThat(stats.distanceCumuleeMetres()).isZero();
        assertThat(stats.denivelePositifCumuleMetres()).isZero();
    }

    @Test
    @DisplayName("CA1 - les Boucles terminées EN_COURS sont celles de Course.bouclesTerminees (définition unique)")
    void doit_reutiliser_course_boucles_terminees_pour_une_course_en_cours() {
        var course = courseX(StatutCourse.EN_COURS, T0);
        for (long minutes : new long[] {0, 59, 60, 119, 120, 299, 300, 900}) {
            Instant maintenant = T0.plus(Duration.ofMinutes(minutes));

            assertThat(course.statistiques(maintenant, List.of()).bouclesTerminees())
                    .as("à t0 + %d min", minutes).isEqualTo(course.bouclesTerminees(maintenant));
        }
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - à t0 + 120 min exactement : 2 Boucles terminées")
    void doit_compter_deux_boucles_a_la_milliseconde_exacte_du_depart_de_la_boucle_3() {
        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plusMillis(2 * HEURE_MS), List.of());

        assertThat(stats.bouclesTerminees()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2 - à t0 + 120 min - 1 ms : 1 Boucle terminée")
    void doit_compter_une_boucle_une_milliseconde_avant_la_frontiere() {
        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plusMillis(2 * HEURE_MS - 1), List.of());

        assertThat(stats.bouclesTerminees()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - à t0 + 5 h : 5 Boucles ; à t0 + 10 h : toujours 5 (plafond nombreMaxBoucles)")
    void doit_plafonner_les_boucles_terminees_au_nombre_max_de_boucles() {
        var course = courseX(StatutCourse.EN_COURS, T0);

        assertThat(course.statistiques(T0.plusMillis(5 * HEURE_MS), List.of()).bouclesTerminees()).isEqualTo(5);
        assertThat(course.statistiques(T0.plusMillis(10 * HEURE_MS), List.of()).bouclesTerminees()).isEqualTo(5);
        assertThat(course.statistiques(T0.plusMillis(10 * HEURE_MS), List.of()).distanceCumuleeMetres())
                .isEqualTo(33_500L);
    }

    @Test
    @DisplayName("CA2 - EN_COURS sans heure de départ : 0 Boucle, cumuls 0, allure vide, durée finale vide")
    void doit_etre_a_zero_et_sans_allure_pour_une_course_en_cours_sans_heure_de_depart() {
        var passages = List.of(scan(ALICE, 1, T0.plus(Duration.ofMinutes(50))));

        var stats = courseX(StatutCourse.EN_COURS, null).statistiques(T0.plus(Duration.ofHours(3)), passages);

        assertThat(stats.bouclesTerminees()).isZero();
        assertThat(stats.distanceCumuleeMetres()).isZero();
        assertThat(stats.denivelePositifCumuleMetres()).isZero();
        assertThat(stats.allureSecondesParKilometre()).isEmpty();
        assertThat(stats.dureeFinaleMillisecondes()).isEmpty();
    }

    @Test
    @DisplayName("CA2 - EN_PREPARATION : 0, 0, 0, allure vide, durée finale vide")
    void doit_etre_a_zero_et_sans_allure_ni_duree_pour_une_course_en_preparation() {
        var stats = courseX(StatutCourse.EN_PREPARATION, null).statistiques(T0.plus(Duration.ofHours(3)), List.of());

        assertThat(stats.bouclesTerminees()).isZero();
        assertThat(stats.distanceCumuleeMetres()).isZero();
        assertThat(stats.denivelePositifCumuleMetres()).isZero();
        assertThat(stats.allureSecondesParKilometre()).isEmpty();
        assertThat(stats.dureeFinaleMillisecondes()).isEmpty();
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3a - TERMINEE, vainqueur unique (Alice 1 à 3, Bruno 1 à 2) : 3 Boucles, durée 10 800 000 ms")
    void doit_deriver_trois_boucles_et_trois_heures_pour_un_vainqueur_unique() {
        var passages = new ArrayList<Passage>();
        passages.addAll(scansRegulieres(ALICE, 3));
        passages.addAll(scansRegulieres(BRUNO, 2));

        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plusMillis(3 * HEURE_MS), passages);

        assertThat(stats.bouclesTerminees()).isEqualTo(3);
        assertThat(stats.dureeFinaleMillisecondes()).hasValue(10_800_000L);
        assertThat(stats.distanceCumuleeMetres()).isEqualTo(20_100L);
        assertThat(stats.denivelePositifCumuleMetres()).isEqualTo(105L);
    }

    @Test
    @DisplayName("CA3 - TERMINEE : même résultat quel que soit l'instant (deux instants différents)")
    void doit_donner_les_memes_statistiques_a_deux_instants_differents_pour_une_course_terminee() {
        var passages = new ArrayList<Passage>();
        passages.addAll(scansRegulieres(ALICE, 3));
        passages.addAll(scansRegulieres(BRUNO, 2));
        var course = courseX(StatutCourse.TERMINEE, T0);

        var tot = course.statistiques(T0.plusMillis(3 * HEURE_MS), passages);
        var tard = course.statistiques(T0.plus(Duration.ofHours(10)), passages);

        assertThat(tard).isEqualTo(tot);
    }

    @Test
    @DisplayName("CA3b - TERMINEE, vainqueurs partagés sans Passage sur la Boucle 4 : 3 Boucles, durée 3 h (pas 4)")
    void doit_deriver_trois_boucles_et_trois_heures_pour_des_vainqueurs_partages_sans_passage_sur_la_boucle_n() {
        var passages = new ArrayList<Passage>();
        passages.addAll(scansRegulieres(ALICE, 3));
        passages.addAll(scansRegulieres(BRUNO, 3));

        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plusMillis(4 * HEURE_MS), passages);

        assertThat(stats.bouclesTerminees()).isEqualTo(3);
        assertThat(stats.dureeFinaleMillisecondes()).hasValue(3 * HEURE_MS);
    }

    @Test
    @DisplayName("CA3c - TERMINEE par nombreMaxBoucles (Passages jusqu'à la Boucle 5) : 5 Boucles, 5 h")
    void doit_deriver_cinq_boucles_et_cinq_heures_pour_une_course_terminee_par_le_nombre_max_de_boucles() {
        var passages = new ArrayList<Passage>();
        passages.addAll(scansRegulieres(ALICE, 5));
        passages.addAll(scansRegulieres(BRUNO, 5));

        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plusMillis(5 * HEURE_MS), passages);

        assertThat(stats.bouclesTerminees()).isEqualTo(5);
        assertThat(stats.dureeFinaleMillisecondes()).hasValue(5 * HEURE_MS);
    }

    @Test
    @DisplayName("CA3d - TERMINEE sans aucun Passage : 0 Boucle, cumuls 0, allure vide, durée 0")
    void doit_deriver_zero_pour_une_course_terminee_sans_passage() {
        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plusMillis(HEURE_MS), List.of());

        assertThat(stats.bouclesTerminees()).isZero();
        assertThat(stats.distanceCumuleeMetres()).isZero();
        assertThat(stats.denivelePositifCumuleMetres()).isZero();
        assertThat(stats.allureSecondesParKilometre()).isEmpty();
        assertThat(stats.dureeFinaleMillisecondes()).hasValue(0L);
    }

    @Test
    @DisplayName("CA3e - TERMINEE par abandon manuel du dernier coureur (Passages 1 et 2) : 2 Boucles, durée 2 h")
    void doit_deriver_deux_boucles_et_deux_heures_pour_un_dernier_coureur_abandonne_manuellement() {
        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plusMillis(3 * HEURE_MS),
                scansRegulieres(ALICE, 2));

        assertThat(stats.bouclesTerminees()).isEqualTo(2);
        assertThat(stats.dureeFinaleMillisecondes()).hasValue(2 * HEURE_MS);
    }

    @Test
    @DisplayName("CA3e - la durée finale est vide pour EN_COURS, même avec des Passages")
    void doit_ne_pas_avoir_de_duree_finale_pour_une_course_en_cours() {
        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plusMillis(2 * HEURE_MS + 1),
                scansRegulieres(ALICE, 2));

        assertThat(stats.dureeFinaleMillisecondes()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - TERMINEE : les Boucles terminées sont le plus grand numéro de Boucle, même avec des trous")
    void doit_prendre_le_plus_grand_numero_de_boucle_des_passages_pour_une_course_terminee() {
        var passages = List.of(scan(ALICE, 1, T0.plus(Duration.ofMinutes(50))),
                scan(BRUNO, 1, T0.plus(Duration.ofMinutes(40))),
                scan(BRUNO, 2, T0.plus(Duration.ofMinutes(100))));

        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plusMillis(3 * HEURE_MS), passages);

        assertThat(stats.bouclesTerminees()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - TERMINEE : un Passage CORRECTION compte comme une Boucle terminée")
    void doit_compter_un_passage_de_correction_comme_une_boucle_terminee_pour_une_course_terminee() {
        var passages = List.of(scan(ALICE, 1, T0.plus(Duration.ofMinutes(50))), correction(ALICE, 2),
                correction(ALICE, 3));

        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plusMillis(4 * HEURE_MS), passages);

        assertThat(stats.bouclesTerminees()).isEqualTo(3);
        assertThat(stats.dureeFinaleMillisecondes()).hasValue(3 * HEURE_MS);
    }

    // ---------- CA4 ----------

    @Test
    @DisplayName("CA4 - EN_COURS, 3 scans aux écarts 50, 40 et 55 min : allure 433 s/km")
    void doit_calculer_l_allure_moyenne_ponderee_par_passage_pour_une_course_en_cours() {
        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(150)),
                passagesDuCa4());

        assertThat(stats.allureSecondesParKilometre()).hasValue(433L);
    }

    @Test
    @DisplayName("CA4 - TERMINEE avec les mêmes Passages : même allure 433 s/km")
    void doit_calculer_la_meme_allure_pour_une_course_terminee() {
        var stats = courseX(StatutCourse.TERMINEE, T0).statistiques(T0.plus(Duration.ofHours(10)),
                passagesDuCa4());

        assertThat(stats.allureSecondesParKilometre()).hasValue(433L);
    }

    // ---------- CA5 ----------

    @Test
    @DisplayName("CA5 - deux Passages CORRECTION en plus : l'allure vaut toujours 433 (corrections exclues)")
    void doit_exclure_les_passages_de_correction_de_l_allure() {
        var passages = new ArrayList<>(passagesDuCa4());
        passages.add(correction(BRUNO, 2));
        passages.add(correction(BRUNO, 3));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(210)), passages);

        assertThat(stats.allureSecondesParKilometre()).hasValue(433L);
    }

    @Test
    @DisplayName("CA5 - uniquement des Passages CORRECTION : allure vide")
    void doit_avoir_une_allure_vide_avec_uniquement_des_corrections() {
        var passages = List.of(correction(BRUNO, 1), correction(BRUNO, 2));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(150)), passages);

        assertThat(stats.allureSecondesParKilometre()).isEmpty();
    }

    @Test
    @DisplayName("CA5 - aucun Passage : allure vide")
    void doit_avoir_une_allure_vide_sans_aucun_passage() {
        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(150)), List.of());

        assertThat(stats.allureSecondesParKilometre()).isEmpty();
    }

    @Test
    @DisplayName("CA5 - un coureur sans aucun SCAN (seulement corrigé) ne change pas l'allure")
    void doit_ne_pas_faire_contribuer_a_l_allure_un_coureur_sans_scan() {
        var passages = new ArrayList<>(passagesDuCa4());
        passages.add(correction(KARIM, 1));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(150)), passages);

        assertThat(stats.allureSecondesParKilometre()).hasValue(433L);
    }

    // ---------- CA6 ----------

    @Test
    @DisplayName("CA6 - un seul Passage à t0 + 3,35 s (T / (n x d) = 0,5) : arrondi demi-supérieur, 1")
    void doit_arrondir_vers_le_haut_un_demi_seconde_par_kilometre() {
        var passages = List.of(scan(ALICE, 1, T0.plusMillis(3_350)));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plusSeconds(10), passages);

        assertThat(stats.allureSecondesParKilometre()).hasValue(1L);
    }

    @Test
    @DisplayName("CA6 - juste sous le demi (3,349 s) : arrondi à 0")
    void doit_arrondir_vers_le_bas_juste_sous_le_demi() {
        var passages = List.of(scan(ALICE, 1, T0.plusMillis(3_349)));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plusSeconds(10), passages);

        assertThat(stats.allureSecondesParKilometre()).hasValue(0L);
    }

    @Test
    @DisplayName("CA6 - un quotient de 2,5 est arrondi à 3 (demi-supérieur)")
    void doit_arrondir_deux_et_demi_a_trois() {
        var course = course(StatutCourse.EN_COURS, T0, new ParametresBoucle(2_000, 60, 35), 5);
        var passages = List.of(scan(ALICE, 1, T0.plusMillis(5_000)));

        var stats = course.statistiques(T0.plusSeconds(10), passages);

        assertThat(stats.allureSecondesParKilometre()).hasValue(3L);
    }

    @Test
    @DisplayName("CA6 - un seul Passage à t0 exactement (écart 0) : allure 0, non vide")
    void doit_retenir_un_ecart_nul_avec_une_allure_de_zero() {
        var passages = List.of(scan(ALICE, 1, T0));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plusSeconds(10), passages);

        assertThat(stats.allureSecondesParKilometre()).hasValue(0L);
    }

    @Test
    @DisplayName("CA6 - un écart négatif (donnée incohérente) est exclu : allure vide s'il est seul")
    void doit_exclure_un_ecart_negatif_de_l_allure() {
        var passages = List.of(scan(ALICE, 1, T0.minusSeconds(1)));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plusSeconds(10), passages);

        assertThat(stats.allureSecondesParKilometre()).isEmpty();
    }

    @Test
    @DisplayName("CA6 - un écart négatif n'entre pas dans n : seul le scan valide compte")
    void doit_ne_pas_compter_un_ecart_negatif_dans_le_nombre_de_passages_retenus() {
        var passages = List.of(scan(ALICE, 1, T0.minusSeconds(1)), scan(BRUNO, 1, T0.plus(Duration.ofMinutes(50))));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(61)), passages);

        // 3 000 000 ms / 6700 m = 447,76 s/km, arrondi 448 (et non 224 si le passage incohérent comptait dans n)
        assertThat(stats.allureSecondesParKilometre()).hasValue(448L);
    }

    @Test
    @DisplayName("CA6 - distance de Boucle de 1 m : aucune division par zéro")
    void doit_calculer_l_allure_pour_une_boucle_d_un_metre() {
        var course = course(StatutCourse.EN_COURS, T0, new ParametresBoucle(1, 60, 0), 5);
        var passages = List.of(scan(ALICE, 1, T0.plusMillis(1_000)));

        var stats = course.statistiques(T0.plusSeconds(10), passages);

        assertThat(stats.allureSecondesParKilometre()).hasValue(1_000L);
    }

    @Test
    @DisplayName("CA6 - heure de départ inconnue : allure vide malgré un Passage SCAN")
    void doit_avoir_une_allure_vide_sans_heure_de_depart() {
        var passages = List.of(scan(ALICE, 1, T0.plus(Duration.ofMinutes(50))));

        var stats = courseX(StatutCourse.TERMINEE, null).statistiques(T0.plus(Duration.ofHours(2)), passages);

        assertThat(stats.allureSecondesParKilometre()).isEmpty();
    }

    @Test
    @DisplayName("CA6 - l'écart d'un scan se mesure depuis le début de SA Boucle, non depuis t0")
    void doit_mesurer_l_ecart_depuis_le_debut_de_la_boucle_du_passage() {
        var passages = List.of(scan(ALICE, 3, T0.plus(Duration.ofMinutes(120 + 30))));

        var stats = courseX(StatutCourse.EN_COURS, T0).statistiques(T0.plus(Duration.ofMinutes(190)), passages);

        // 30 min = 1 800 000 ms pour 6700 m = 268,66 s/km
        assertThat(stats.allureSecondesParKilometre()).hasValue(269L);
    }

    // ---------- CA7 ----------

    @Test
    @DisplayName("CA7 - d = 50 000, h = 10 000, M = 24, 24 Boucles : 1 200 000 m et 240 000 m D+ en 64 bits")
    void doit_cumuler_distance_et_denivele_maximaux_sans_depassement() {
        var course = course(StatutCourse.TERMINEE, T0, new ParametresBoucle(50_000, 60, 10_000), 24);

        var stats = course.statistiques(T0.plus(Duration.ofHours(30)), scansRegulieres(ALICE, 24, 60));

        assertThat(stats.bouclesTerminees()).isEqualTo(24);
        assertThat(stats.distanceCumuleeMetres()).isEqualTo(1_200_000L);
        assertThat(stats.denivelePositifCumuleMetres()).isEqualTo(240_000L);
    }

    // ---------- fabriques ----------

    private static List<Passage> passagesDuCa4() {
        return List.of(scan(ALICE, 1, T0.plus(Duration.ofMinutes(50))),
                scan(BRUNO, 1, T0.plus(Duration.ofMinutes(40))),
                scan(ALICE, 2, T0.plus(Duration.ofMinutes(60 + 55))));
    }

    private static Course courseX(StatutCourse statut, Instant demarreeLe) {
        return course(statut, demarreeLe, new ParametresBoucle(6_700, 60, 35), 5);
    }

    private static Course course(StatutCourse statut, Instant demarreeLe, ParametresBoucle parametres,
                                 int nombreMaxBoucles) {
        return Course.reconstituer(id(1), "Backyard des Crêtes", LocalDate.of(2026, 10, 9), statut, parametres, 50,
                nombreMaxBoucles, Set.of(), demarreeLe);
    }

    /** Passages SCAN de la Boucle 1 à {@code nombreDeBoucles}, chacun 50 min après le départ de sa Boucle. */
    private static List<Passage> scansRegulieres(UUID inscription, int nombreDeBoucles) {
        return scansRegulieres(inscription, nombreDeBoucles, 60);
    }

    private static List<Passage> scansRegulieres(UUID inscription, int nombreDeBoucles, int dureeBoucleMinutes) {
        var passages = new ArrayList<Passage>();
        for (int boucle = 1; boucle <= nombreDeBoucles; boucle++) {
            passages.add(scan(inscription, boucle,
                    T0.plus(Duration.ofMinutes((long) (boucle - 1) * dureeBoucleMinutes + 50))));
        }
        return passages;
    }

    private static Passage scan(UUID inscription, int boucle, Instant scanneLe) {
        return Passage.reconstituer(UUID.randomUUID(), inscription, boucle, scanneLe, OriginePassage.SCAN);
    }

    private static Passage correction(UUID inscription, int boucle) {
        return Passage.reconstituer(UUID.randomUUID(), inscription, boucle, null, OriginePassage.CORRECTION);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
