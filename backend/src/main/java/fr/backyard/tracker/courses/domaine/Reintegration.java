package fr.backyard.tracker.courses.domaine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/** Règle des Boucles à corriger lors d'une réintégration. Unique définition de cette règle. */
public final class Reintegration {

    private Reintegration() {
    }

    /**
     * Boucles, en ordre croissant, pour lesquelles un Passage CORRECTION est à créer : de la Boucle d'abandon à la
     * dernière Boucle terminée, sans celles qui ont déjà un Passage. La Boucle courante n'est jamais corrigée.
     *
     * @param boucleAbandon Boucle de l'abandon ; absente (données antérieures), le nombre de Passages plus un
     * @param bouclesTerminees nombre de Boucles terminées de la Course à l'instant de la réintégration
     * @param numerosDejaPasses numéros des Boucles ayant déjà un Passage, quelle que soit son origine
     */
    public static List<Integer> bouclesACorriger(Optional<Integer> boucleAbandon, int bouclesTerminees,
                                                 Collection<Integer> numerosDejaPasses) {
        Set<Integer> dejaPasses = Set.copyOf(numerosDejaPasses);
        int premiere = boucleAbandon.orElse(dejaPasses.size() + 1);
        return IntStream.rangeClosed(Math.max(premiere, 1), bouclesTerminees)
                .filter(boucle -> !dejaPasses.contains(boucle))
                .boxed()
                .toList();
    }
}
