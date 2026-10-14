package fr.backyard.tracker.courses.domaine;

import java.util.Collection;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Progression d'un coureur dérivée de ses Passages, jamais stockée. Tout Passage est valide, qu'il vienne d'un scan
 * ou d'une correction.
 *
 * @param nombreBouclesTerminees nombre de Passages de l'Inscription (numéros contigus de 1 à ce nombre)
 * @param boucleCouranteTerminee l'Inscription a un Passage sur la Boucle courante ; vide sans Boucle courante
 */
public record ProgressionCoureur(int nombreBouclesTerminees, Optional<Boolean> boucleCouranteTerminee) {

    /**
     * @param passagesDeLInscription Passages d'une seule Inscription
     * @param boucleCourante Boucle courante de la Course, vide sans chronométrage
     */
    public static ProgressionCoureur de(Collection<Passage> passagesDeLInscription, OptionalInt boucleCourante) {
        Optional<Boolean> aBoucle = boucleCourante.isPresent()
                ? Optional.of(aUnPassageSur(passagesDeLInscription, boucleCourante.getAsInt()))
                : Optional.empty();
        return new ProgressionCoureur(passagesDeLInscription.size(), aBoucle);
    }

    /** Vrai seulement si la Boucle courante est connue et que le coureur a bouclé. */
    public boolean aBoucle() {
        return boucleCouranteTerminee.orElse(false);
    }

    private static boolean aUnPassageSur(Collection<Passage> passages, int numeroBoucle) {
        return passages.stream().anyMatch(passage -> passage.numeroBoucle() == numeroBoucle);
    }
}
