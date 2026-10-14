package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.7 : règle de progression d'un coureur (CA1 ; RG1, RG2, RG3, RG5, RG6, RG7).
 *
 * Signatures supposées (la spec laisse les noms libres, RG15) :
 * {@code ProgressionCoureur ProgressionCoureur.de(Collection<Passage> passagesDeLInscription,
 * OptionalInt boucleCourante)} (OptionalInt vide = pas de chronométrage, RG7) ;
 * {@code int nombreBouclesTerminees()} ;
 * {@code Optional<Boolean> boucleCouranteTerminee()} (vide quand la Boucle courante est inconnue).
 */
class ProgressionCoureurTest {

    private static final UUID INSCRIPTION = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");

    @Test
    @DisplayName("CA1 - Passages {1, 2, 3} et Boucle 3 : 3 Boucles terminées, a bouclé")
    void doit_avoir_boucle_quand_le_passage_de_la_boucle_courante_existe() {
        var progression = ProgressionCoureur.de(scans(1, 2, 3), OptionalInt.of(3));

        assertThat(progression.nombreBouclesTerminees()).isEqualTo(3);
        assertThat(progression.boucleCouranteTerminee()).contains(true);
    }

    @Test
    @DisplayName("CA1 - Passages {1, 2, 3} et Boucle 4 : 3 Boucles terminées, n'a pas bouclé")
    void doit_etre_encore_en_boucle_apres_la_bascule_sur_la_boucle_suivante() {
        var progression = ProgressionCoureur.de(scans(1, 2, 3), OptionalInt.of(4));

        assertThat(progression.nombreBouclesTerminees()).isEqualTo(3);
        assertThat(progression.boucleCouranteTerminee()).contains(false);
    }

    @Test
    @DisplayName("CA1 - aucun Passage et Boucle 1 : 0 Boucle terminée, n'a pas bouclé")
    void doit_etre_en_boucle_sans_aucun_passage() {
        var progression = ProgressionCoureur.de(List.of(), OptionalInt.of(1));

        assertThat(progression.nombreBouclesTerminees()).isZero();
        assertThat(progression.boucleCouranteTerminee()).contains(false);
    }

    @Test
    @DisplayName("CA1 - Passages {1, 2} SCAN et {3} CORRECTION, Boucle 3 : 3 Boucles terminées, a bouclé (RG6)")
    void doit_compter_un_passage_correction_comme_un_passage_scan() {
        List<Passage> passages = List.of(scan(1), scan(2), correction(3));

        var progression = ProgressionCoureur.de(passages, OptionalInt.of(3));

        assertThat(progression.nombreBouclesTerminees()).isEqualTo(3);
        assertThat(progression.boucleCouranteTerminee()).contains(true);
    }

    @Test
    @DisplayName("CA1 - Passages {1, 2, 3, 4} et Boucle 3 (anomalie d'horloge) : 4 Boucles terminées, a bouclé")
    void doit_compter_tous_les_passages_meme_au_dela_de_la_boucle_courante() {
        var progression = ProgressionCoureur.de(scans(1, 2, 3, 4), OptionalInt.of(3));

        assertThat(progression.nombreBouclesTerminees()).isEqualTo(4);
        assertThat(progression.boucleCouranteTerminee()).contains(true);
    }

    @Test
    @DisplayName("CA1 - Passages {1, 2, 4} et Boucle 3 : 3 Boucles terminées, n'a pas bouclé (le 4 ne vaut pas le 3)")
    void doit_ne_pas_considerer_un_passage_de_numero_superieur_comme_boucle_courante_terminee() {
        var progression = ProgressionCoureur.de(scans(1, 2, 4), OptionalInt.of(3));

        assertThat(progression.nombreBouclesTerminees()).isEqualTo(3);
        assertThat(progression.boucleCouranteTerminee()).contains(false);
    }

    @Test
    @DisplayName("CA1 - Boucle courante inconnue (RG7) : indicateur absent, Boucles terminées renseigné")
    void doit_laisser_l_indicateur_vide_sans_chronometrage_mais_compter_les_boucles() {
        var progression = ProgressionCoureur.de(scans(1, 2), OptionalInt.empty());

        assertThat(progression.boucleCouranteTerminee()).isEmpty();
        assertThat(progression.nombreBouclesTerminees()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA1 - Boucle plafonnée (B = M = 5) avec Passage 5 : a bouclé, 5 Boucles terminées")
    void doit_rester_a_boucle_sur_la_derniere_boucle_autorisee() {
        var progression = ProgressionCoureur.de(scans(1, 2, 3, 4, 5), OptionalInt.of(5));

        assertThat(progression.nombreBouclesTerminees()).isEqualTo(5);
        assertThat(progression.boucleCouranteTerminee()).contains(true);
    }

    @Test
    @DisplayName("CA1 - Boucle plafonnée (B = M = 5) sans Passage 5 : n'a pas bouclé")
    void doit_rester_en_boucle_sur_la_derniere_boucle_sans_passage() {
        var progression = ProgressionCoureur.de(scans(1, 2, 3, 4), OptionalInt.of(5));

        assertThat(progression.nombreBouclesTerminees()).isEqualTo(4);
        assertThat(progression.boucleCouranteTerminee()).contains(false);
    }

    private static List<Passage> scans(int... numeros) {
        return java.util.Arrays.stream(numeros).mapToObj(ProgressionCoureurTest::scan).toList();
    }

    private static Passage scan(int numeroBoucle) {
        return Passage.reconstituer(UUID.randomUUID(), INSCRIPTION, numeroBoucle,
                T0.plusSeconds(numeroBoucle * 30L), OriginePassage.SCAN);
    }

    private static Passage correction(int numeroBoucle) {
        return Passage.reconstituer(UUID.randomUUID(), INSCRIPTION, numeroBoucle, null, OriginePassage.CORRECTION);
    }
}
