package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.12 : la règle de contrôle {@link AbandonAutomatique#decider}, inchangée, traite un coureur
 * réintégré comme les autres (CA4 ; RG5, RG6). Un Passage CORRECTION est un Passage valide : le prédicat
 * {@code aUnPassageSur} le couvre sans distinguer l'origine. Notation : {@code Tom {1c}} = Passage CORRECTION sur la
 * Boucle 1. Course X : M = 5 ; Course W : M = 2.
 */
class AbandonAutomatiqueReintegresTest {

    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int M_X = 5;
    private static final int M_W = 2;

    private final Map<UUID, Set<Integer>> passages = new HashMap<>();
    private final List<Inscription> inscriptions = new ArrayList<>();

    private void enCourse(int dossard, Integer... boucles) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), COURSE, UUID.randomUUID(), dossard,
                JetonQr.generer(), StatutInscription.EN_COURSE);
        inscriptions.add(inscription);
        passages.put(inscription.id(), Set.of(boucles));
    }

    private AbandonAutomatique.Decision decider(int bouclesTerminees, int nombreMaxBoucles) {
        return AbandonAutomatique.decider(List.copyOf(inscriptions),
                (inscription, boucle) -> passages.get(inscription.id()).contains(boucle),
                bouclesTerminees, nombreMaxBoucles);
    }

    private static List<Integer> dossardsDesVainqueurs(AbandonAutomatique.Decision decision) {
        return decision.vainqueurs().stream().map(Inscription::dossard).toList();
    }

    @Test
    @DisplayName("CA4 - RG5 : X, Bruno {1}, Chloé {1}, Tom {1c}, N = 2 : |P(1)| = 3, personne sur la Boucle 2 : trois vainqueurs")
    void doit_designer_les_trois_vainqueurs_quand_personne_ne_boucle_la_boucle_suivante() {
        enCourse(1, 1);
        enCourse(2, 1);
        enCourse(3, 1);

        var decision = decider(2, M_X);

        assertThat(dossardsDesVainqueurs(decision)).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(decision.abandons()).isEmpty();
        assertThat(decision.courseTerminee()).isTrue();
        assertThat(decision.boucleDeFin()).hasValue(2);
    }

    @Test
    @DisplayName("CA4 - RG5 : W (M = 2), Bruno {1, 2}, Chloé {1, 2}, Tom {1c, 2}, N = 2 : trois vainqueurs")
    void doit_designer_le_reintegre_parmi_les_vainqueurs_partages_de_la_derniere_boucle() {
        enCourse(1, 1, 2);
        enCourse(2, 1, 2);
        enCourse(3, 1, 2);

        var decision = decider(2, M_W);

        assertThat(dossardsDesVainqueurs(decision)).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(decision.abandons()).isEmpty();
        assertThat(decision.courseTerminee()).isTrue();
        assertThat(decision.boucleDeFin()).hasValue(2);
    }

    @Test
    @DisplayName("CA4 - RG5 : W (M = 2), Tom {1c} sans scan de la Boucle 2 : Bruno et Chloé vainqueurs, Tom HORS_DELAI Boucle 2")
    void doit_abandonner_le_reintegre_qui_ne_boucle_pas_la_derniere_boucle() {
        enCourse(1, 1, 2);
        enCourse(2, 1, 2);
        enCourse(3, 1);

        var decision = decider(2, M_W);

        assertThat(dossardsDesVainqueurs(decision)).containsExactlyInAnyOrder(1, 2);
        assertThat(decision.abandons()).extracting(d -> d.inscription().dossard(), AbandonAutomatique.Designation::motif,
                AbandonAutomatique.Designation::boucleAbandon).containsExactly(tuple(3, MotifAbandon.HORS_DELAI, 2));
        assertThat(decision.courseTerminee()).isTrue();
    }

    @Test
    @DisplayName("CA4 - RG6 a : X, Bruno {1}, Chloé {}, Tom {1c}, N = 1 : |P(1)| = 2, Chloé HORS_DELAI Boucle 1, aucune fin")
    void doit_empecher_le_vainqueur_unique_quand_le_reintegre_compte_dans_la_boucle() {
        enCourse(1, 1);
        enCourse(2);
        enCourse(3, 1);

        var decision = decider(1, M_X);

        assertThat(decision.abandons()).extracting(d -> d.inscription().dossard(), AbandonAutomatique.Designation::motif,
                AbandonAutomatique.Designation::boucleAbandon).containsExactly(tuple(2, MotifAbandon.HORS_DELAI, 1));
        assertThat(decision.vainqueurs()).isEmpty();
        assertThat(decision.courseTerminee()).isFalse();
    }

    @Test
    @DisplayName("CA4 - RG6 a : le même cas sans Tom : Bruno vainqueur unique, Chloé HORS_DELAI Boucle 1")
    void doit_designer_le_vainqueur_unique_sans_le_reintegre() {
        enCourse(1, 1);
        enCourse(2);

        var decision = decider(1, M_X);

        assertThat(dossardsDesVainqueurs(decision)).containsExactly(1);
        assertThat(decision.abandons()).extracting(d -> d.inscription().dossard(), AbandonAutomatique.Designation::boucleAbandon)
                .containsExactly(tuple(2, 1));
        assertThat(decision.courseTerminee()).isTrue();
    }

    @Test
    @DisplayName("CA4 - RG5 b : X, Bruno {1, 2, 3}, Chloé {1, 2}, Tom {1, 2c, 3}, N = 3 : |P(3)| = 2, Chloé HORS_DELAI Boucle 3, aucune fin")
    void doit_empecher_un_vainqueur_unique_a_la_boucle_3_quand_le_reintegre_la_boucle() {
        enCourse(1, 1, 2, 3);
        enCourse(2, 1, 2);
        enCourse(3, 1, 2, 3);

        var decision = decider(3, M_X);

        assertThat(decision.abandons()).extracting(d -> d.inscription().dossard(), AbandonAutomatique.Designation::boucleAbandon)
                .containsExactly(tuple(2, 3));
        assertThat(decision.vainqueurs()).isEmpty();
        assertThat(decision.courseTerminee()).isFalse();
    }

    @Test
    @DisplayName("CA4 - RG5 : un réintégré en règle (Bruno, Chloé, Tom tous {1, 2, 3}, N = 3) ne déclenche aucune décision")
    void doit_ne_rien_decider_pour_un_reintegre_en_regle() {
        enCourse(1, 1, 2, 3);
        enCourse(2, 1, 2, 3);
        enCourse(3, 1, 2, 3);

        var decision = decider(3, M_X);

        assertThat(decision.abandons()).isEmpty();
        assertThat(decision.vainqueurs()).isEmpty();
        assertThat(decision.courseTerminee()).isFalse();
    }
}
