package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests de l'incrément 4.12 : règle des Boucles à corriger (CA1 ; RG3).
 *
 * Signature supposée : {@code static List<Integer> Reintegration.bouclesACorriger(Optional<Integer> boucleAbandon,
 * int bouclesTerminees, Collection<Integer> numerosDejaPasses)} dans une classe utilitaire {@code Reintegration} du
 * domaine. Résultat : numéros croissants {@code k} tels que {@code b0 <= k <= bouclesTerminees} et sans Passage
 * existant ; {@code b0 = boucleAbandon}, ou {@code numerosDejaPasses.size() + 1} si la Boucle d'abandon est absente.
 */
class ReintegrationBouclesACorrigerTest {

    static Stream<Arguments> cas() {
        return Stream.of(
                Arguments.of(1, 1, Set.of(), List.of(1)),
                Arguments.of(2, 3, Set.of(1), List.of(2, 3)),
                Arguments.of(2, 1, Set.of(1), List.of()),
                Arguments.of(3, 2, Set.of(1, 2), List.of()),
                Arguments.of(1, 0, Set.of(), List.of()),
                Arguments.of(1, 3, Set.of(2), List.of(1, 3)),
                Arguments.of(1, 4, Set.of(), List.of(1, 2, 3, 4)));
    }

    @ParameterizedTest(name = "CA1 - RG3 : b0 = {0}, bN = {1}, déjà passées {2} -> {3}")
    @MethodSource("cas")
    @DisplayName("CA1 - RG3 : Boucles à corriger de la Boucle d'abandon à la dernière Boucle terminée, sans doublon")
    void doit_corriger_les_boucles_sans_passage_entre_la_boucle_d_abandon_et_la_derniere_terminee(
            int boucleAbandon, int bouclesTerminees, Set<Integer> dejaPasses, List<Integer> attendues) {
        List<Integer> aCorriger = Reintegration.bouclesACorriger(Optional.of(boucleAbandon), bouclesTerminees,
                dejaPasses);

        assertThat(aCorriger).containsExactlyElementsOf(attendues);
    }

    @Test
    @DisplayName("CA1 - RG3 : la Boucle courante (bouclesTerminees + 1) n'est jamais corrigée")
    void doit_exclure_la_boucle_courante() {
        List<Integer> aCorriger = Reintegration.bouclesACorriger(Optional.of(2), 2, Set.of(1));

        assertThat(aCorriger).containsExactly(2).doesNotContain(3);
    }

    @Test
    @DisplayName("CA1 - RG3 : sans Boucle d'abandon (abandon posé par SQL), b0 = nombre de Passages + 1")
    void doit_partir_du_nombre_de_passages_plus_un_sans_boucle_d_abandon() {
        List<Integer> aCorriger = Reintegration.bouclesACorriger(Optional.empty(), 3, Set.of(1, 2));

        assertThat(aCorriger).containsExactly(3);
    }

    @Test
    @DisplayName("CA1 - RG3 : sans Boucle d'abandon et sans Passage, toutes les Boucles terminées sont corrigées")
    void doit_corriger_toutes_les_boucles_terminees_sans_boucle_d_abandon_ni_passage() {
        List<Integer> aCorriger = Reintegration.bouclesACorriger(Optional.empty(), 2, Set.of());

        assertThat(aCorriger).containsExactly(1, 2);
    }
}
