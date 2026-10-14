package fr.backyard.tracker.courses.domaine;

import java.util.Collection;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Bouclage de la Boucle courante parmi les coureurs encore en course, dérivé de leurs progressions et jamais stocké.
 * La somme des deux nombres vaut le nombre de coureurs considérés.
 *
 * @param nombreAyantBoucle coureurs ayant un Passage sur la Boucle courante
 * @param nombreEnBoucle coureurs sans Passage sur la Boucle courante
 */
public record Bouclage(int nombreAyantBoucle, int nombreEnBoucle) {

    /** Bouclage des progressions données ; vide sans Boucle courante (pas de chronométrage). */
    public static Optional<Bouclage> de(Collection<ProgressionCoureur> progressions, OptionalInt boucleCourante) {
        if (boucleCourante.isEmpty()) {
            return Optional.empty();
        }
        int ayantBoucle = (int) progressions.stream().filter(ProgressionCoureur::aBoucle).count();
        return Optional.of(new Bouclage(ayantBoucle, progressions.size() - ayantBoucle));
    }
}
