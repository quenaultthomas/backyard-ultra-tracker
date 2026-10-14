package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests de l'incrément 4.6 : délai minimal entre deux Passages et anti-doublon (CA1, CA2 ; RG1 à RG3, RG5).
 *
 * Signatures attendues (spec RG3, RG15 ; la spec laisse le nom libre pour la vérification, choix du testeur) :
 * {@code Duration Course.delaiMinimalEntrePassages()} ;
 * {@code void Course.verifierDelaiEntrePassages(int numeroBoucle, Instant scanneLe,
 * IntFunction<Optional<Passage>> passageDeLaBoucle)} qui lève {@code PassageTropRapprocheException}
 * (extends {@code RefusPassageCoureurException}) portant {@code Duration delaiMinimal()},
 * {@code long secondesRestantes()} et {@code int numeroBoucle()}. Le troisième argument fournit le Passage de la
 * Boucle donnée (il n'est jamais appelé pour la Boucle 1).
 */
class CourseAntiDoublonTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final Instant PASSAGE_PRECEDENT = T0.plus(Duration.ofMinutes(59).plusSeconds(50));

    // ---------- CA1 : délai effectif min(2 min, D / 4) ----------

    @ParameterizedTest(name = "CA1 - Boucle de {0} min : délai de {1} s")
    @CsvSource({"1,15", "2,30", "4,60", "7,105", "8,120", "60,120", "1440,120"})
    void doit_plafonner_le_delai_minimal_au_quart_de_la_duree_de_boucle(int dureeMinutes, long delaiSecondes) {
        Course course = courseDeDureeDeBoucle(dureeMinutes);

        assertThat(course.delaiMinimalEntrePassages()).isEqualTo(Duration.ofSeconds(delaiSecondes));
    }

    @Test
    @DisplayName("CA1 - deux Courses de durées de Boucle différentes ont des délais indépendants")
    void doit_calculer_un_delai_independant_pour_chaque_course() {
        Course courteDe1Minute = courseDeDureeDeBoucle(1);
        Course longueDe60Minutes = courseDeDureeDeBoucle(60);

        assertThat(courteDe1Minute.delaiMinimalEntrePassages()).isEqualTo(Duration.ofSeconds(15));
        assertThat(longueDe60Minutes.delaiMinimalEntrePassages()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("CA1 - le délai ne dépend d'aucun état de la Course (en préparation ou en cours)")
    void doit_donner_le_meme_delai_quel_que_soit_le_statut_de_la_course() {
        Course enPreparation = course(StatutCourse.EN_PREPARATION, 60, null);
        Course enCours = course(StatutCourse.EN_COURS, 60, T0);
        Course terminee = course(StatutCourse.TERMINEE, 60, T0);

        assertThat(enPreparation.delaiMinimalEntrePassages())
                .isEqualTo(enCours.delaiMinimalEntrePassages())
                .isEqualTo(terminee.delaiMinimalEntrePassages())
                .isEqualTo(Duration.ofMinutes(2));
    }

    // ---------- CA2 : comparaison stricte s - p < d, Course de 60 min (d = 2 min) ----------

    @Test
    @DisplayName("CA2 - 15 s après le Passage précédent (d = 2 min) : refus, 105 s restantes, délai 120 s")
    void doit_refuser_un_scan_quinze_secondes_apres_le_passage_precedent() {
        Course course = courseDeDureeDeBoucle(60);

        assertThatThrownBy(() -> verifier(course, 2, PASSAGE_PRECEDENT.plusSeconds(15), passageScanneEn1(PASSAGE_PRECEDENT)))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class, e -> {
                    assertThat(e.secondesRestantes()).isEqualTo(105);
                    assertThat(e.delaiMinimal()).isEqualTo(Duration.ofSeconds(120));
                    assertThat(e.numeroBoucle()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("CA2 - 119,999 s après le Passage précédent : refus, 1 seconde restante (arrondi au-dessus)")
    void doit_refuser_a_une_milliseconde_du_delai_en_arrondissant_au_dessus() {
        Course course = courseDeDureeDeBoucle(60);

        assertThatThrownBy(() -> verifier(course, 2, PASSAGE_PRECEDENT.plusMillis(119_999), passageScanneEn1(PASSAGE_PRECEDENT)))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class,
                        e -> assertThat(e.secondesRestantes()).isEqualTo(1));
    }

    @Test
    @DisplayName("CA2 - exactement 120 s après le Passage précédent : accepté (comparaison stricte)")
    void doit_accepter_un_scan_exactement_au_delai_minimal() {
        Course course = courseDeDureeDeBoucle(60);

        assertThatCode(() -> verifier(course, 2, PASSAGE_PRECEDENT.plusSeconds(120), passageScanneEn1(PASSAGE_PRECEDENT)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA2 - 120,001 s après le Passage précédent : accepté")
    void doit_accepter_un_scan_une_milliseconde_apres_le_delai_minimal() {
        Course course = courseDeDureeDeBoucle(60);

        assertThatCode(() -> verifier(course, 2, PASSAGE_PRECEDENT.plusMillis(120_001), passageScanneEn1(PASSAGE_PRECEDENT)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA2 - s - p = {0} ms (d = 2 min) : {1} seconde(s) restante(s)")
    @CsvSource({"0,120", "1,120", "1000,119", "1001,119", "118999,2", "119000,1", "119999,1"})
    void doit_arrondir_au_dessus_les_secondes_restantes_sans_jamais_donner_zero(long ecartMillis, long attendues) {
        Course course = courseDeDureeDeBoucle(60);

        assertThatThrownBy(() -> verifier(course, 2, PASSAGE_PRECEDENT.plusMillis(ecartMillis), passageScanneEn1(PASSAGE_PRECEDENT)))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class,
                        e -> assertThat(e.secondesRestantes()).isEqualTo(attendues));
    }

    // ---------- CA2 : portée (RG1) ----------

    @Test
    @DisplayName("CA2 - Boucle 1 : jamais refusé par l'anti-doublon, et aucun Passage précédent n'est consulté")
    void doit_ne_jamais_controler_l_anti_doublon_sur_la_boucle_1() {
        Course course = courseDeDureeDeBoucle(60);
        IntFunction<Optional<Passage>> aucuneConsultation = boucle -> {
            throw new AssertionError("Aucun Passage précédent ne doit être consulté en Boucle 1");
        };

        assertThatCode(() -> course.verifierDelaiEntrePassages(1, T0.plusSeconds(1), aucuneConsultation))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA2 - seul le Passage de la Boucle N-1 est consulté (N = 3 : Boucle 2)")
    void doit_comparer_uniquement_avec_le_passage_de_la_boucle_precedente() {
        Course course = courseDeDureeDeBoucle(60);
        Instant passageBoucle2 = T0.plus(Duration.ofMinutes(119));
        IntFunction<Optional<Passage>> seulementBoucle2 = boucle -> {
            assertThat(boucle).isEqualTo(2);
            return Optional.of(passageScanne(2, passageBoucle2));
        };

        assertThatThrownBy(() -> course.verifierDelaiEntrePassages(3, passageBoucle2.plusSeconds(15), seulementBoucle2))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class, e -> {
                    assertThat(e.numeroBoucle()).isEqualTo(3);
                    assertThat(e.secondesRestantes()).isEqualTo(105);
                });
    }

    @Test
    @DisplayName("CA2 - Passage précédent d'origine CORRECTION (scanneLe nul) : accepté sans contrôle")
    void doit_ne_pas_controler_quand_le_passage_precedent_est_une_correction() {
        Course course = courseDeDureeDeBoucle(60);
        Passage correction = Passage.corriger(UUID.randomUUID(), inscription(), 1);

        assertThatCode(() -> verifier(course, 2, T0.plus(Duration.ofMinutes(60)).plusSeconds(1), boucle -> Optional.of(correction)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA2 - Boucle N > 1 sans Passage précédent connu : pas d'anti-doublon (la continuité relève d'un autre refus)")
    void doit_ne_pas_lever_d_anti_doublon_quand_aucun_passage_precedent_n_est_fourni() {
        Course course = courseDeDureeDeBoucle(60);

        assertThatCode(() -> verifier(course, 2, T0.plus(Duration.ofMinutes(60)), boucle -> Optional.empty()))
                .doesNotThrowAnyException();
    }

    // ---------- CA2 : Course de 1 min (d = 15 s) ----------

    @Test
    @DisplayName("CA2 - Course de 1 min (d = 15 s), 10 s après le Passage précédent : refus, 5 s restantes")
    void doit_refuser_dix_secondes_apres_le_passage_precedent_sur_une_boucle_de_1_minute() {
        Course course = courseDeDureeDeBoucle(1);
        Instant precedent = T0.plusSeconds(55);

        assertThatThrownBy(() -> verifier(course, 2, precedent.plusSeconds(10), passageScanneEn1(precedent)))
                .isInstanceOfSatisfying(PassageTropRapprocheException.class, e -> {
                    assertThat(e.secondesRestantes()).isEqualTo(5);
                    assertThat(e.delaiMinimal()).isEqualTo(Duration.ofSeconds(15));
                });
    }

    @Test
    @DisplayName("CA2 - Course de 1 min, exactement 15 s après le Passage précédent : accepté")
    void doit_accepter_quinze_secondes_apres_le_passage_precedent_sur_une_boucle_de_1_minute() {
        Course course = courseDeDureeDeBoucle(1);
        Instant precedent = T0.plusSeconds(55);

        assertThatCode(() -> verifier(course, 2, precedent.plusSeconds(15), passageScanneEn1(precedent)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA2 - Course de 1 min, 45 s après le Passage précédent : accepté (enchaînement ordinaire non gêné)")
    void doit_accepter_quarante_cinq_secondes_apres_le_passage_precedent_sur_une_boucle_de_1_minute() {
        Course course = courseDeDureeDeBoucle(1);
        Instant precedent = T0.plusSeconds(20);

        assertThatCode(() -> verifier(course, 2, precedent.plusSeconds(45), passageScanneEn1(precedent)))
                .doesNotThrowAnyException();
    }

    // ---------- outils ----------

    private static void verifier(Course course, int numeroBoucle, Instant scanneLe,
                                 IntFunction<Optional<Passage>> passageDeLaBoucle) {
        course.verifierDelaiEntrePassages(numeroBoucle, scanneLe, passageDeLaBoucle);
    }

    /** Fournit un Passage SCAN de Boucle 1 pour toute demande (le test ne consulte que la Boucle N-1 = 1). */
    private static IntFunction<Optional<Passage>> passageScanneEn1(Instant scanneLe) {
        Passage passage = passageScanne(1, scanneLe);
        return boucle -> Optional.of(passage);
    }

    private static Passage passageScanne(int numeroBoucle, Instant scanneLe) {
        return Passage.scanner(UUID.randomUUID(), inscription(), numeroBoucle, scanneLe);
    }

    private static Inscription inscription() {
        return Inscription.reconstituer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                JetonQr.generer(), StatutInscription.EN_COURSE);
    }

    private static Course courseDeDureeDeBoucle(int dureeMinutes) {
        return course(StatutCourse.EN_COURS, dureeMinutes, T0);
    }

    private static Course course(StatutCourse statut, int dureeMinutes, Instant demarreeLe) {
        return Course.reconstituer(UUID.randomUUID(), "Course de test", JOUR, statut,
                new ParametresBoucle(400, dureeMinutes, 5), 10, 5, Set.of(), demarreeLe);
    }
}
