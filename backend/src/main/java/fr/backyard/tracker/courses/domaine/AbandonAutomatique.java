package fr.backyard.tracker.courses.domaine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Règle de contrôle de fin de Boucle : désigne les Inscriptions EN_COURSE à mettre en abandon hors délai. Les Boucles
 * terminées sont contrôlées dans l'ordre (1 à N) : sur la Boucle k, celles sans Passage sont désignées avec la Boucle
 * k, les autres restent en lice pour k + 1. Si aucune Inscription en lice n'a de Passage sur k, aucune décision n'est
 * prise pour k ni pour les suivantes (fin de course, hors de cette règle). Fonction pure : rien n'est modifié. Unique
 * définition de cette règle.
 */
public final class AbandonAutomatique {

    /** Inscription à mettre en abandon, avec son motif et la Boucle manquée. */
    public record Designation(Inscription inscription, MotifAbandon motif, int boucleAbandon) {
    }

    private AbandonAutomatique() {
    }

    /**
     * @param aUnPassageSur indique si une Inscription a un Passage (de toute origine) sur une Boucle donnée
     * @param bouclesTerminees nombre de Boucles terminées de la Course, voir {@link Course#bouclesTerminees}
     */
    public static List<Designation> designer(Collection<Inscription> inscriptions,
                                             BiPredicate<Inscription, Integer> aUnPassageSur, int bouclesTerminees) {
        List<Designation> designations = new ArrayList<>();
        List<Inscription> enLice = inscriptions.stream().filter(Inscription::estEnCourse).toList();
        for (int boucle = 1; boucle <= bouclesTerminees; boucle++) {
            int numero = boucle;
            List<Inscription> ayantBoucle = enLice.stream().filter(i -> aUnPassageSur.test(i, numero)).toList();
            if (ayantBoucle.isEmpty()) {
                break;
            }
            enLice.stream().filter(i -> !ayantBoucle.contains(i))
                    .forEach(i -> designations.add(new Designation(i, MotifAbandon.HORS_DELAI, numero)));
            enLice = ayantBoucle;
        }
        return List.copyOf(designations);
    }
}
