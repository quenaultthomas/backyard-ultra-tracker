package fr.backyard.tracker.courses.domaine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiPredicate;

/**
 * Règle de contrôle de fin de Boucle. Les Boucles terminées sont examinées dans l'ordre (k de 1 à N), parmi les
 * Inscriptions EN_COURSE restant en lice : si personne n'a de Passage sur k, aucune décision n'est prise pour k ni
 * pour les suivantes ; si une seule en a un, elle est vainqueur, les autres en lice abandonnent hors délai sur k et la
 * Course est à terminer (les Boucles suivantes ne sont pas examinées) ; si au moins deux en ont un, les autres
 * abandonnent hors délai sur k et l'examen passe à k + 1. Fonction pure : rien n'est modifié. Unique définition de
 * cette règle.
 */
public final class AbandonAutomatique {

    /** Inscription à mettre en abandon, avec son motif et la Boucle manquée. */
    public record Designation(Inscription inscription, MotifAbandon motif, int boucleAbandon) {
    }

    /**
     * Décision du contrôle : abandons à appliquer, vainqueur éventuel et indicateur de fin de Course.
     *
     * @param courseTerminee la Course est à terminer
     * @param boucleDeFin Boucle sur laquelle la fin de Course est décidée, vide si la Course n'est pas à terminer
     */
    public record Decision(List<Designation> abandons, Optional<Inscription> vainqueur, boolean courseTerminee,
                           OptionalInt boucleDeFin) {

        public Decision {
            abandons = List.copyOf(abandons);
            Objects.requireNonNull(vainqueur);
            Objects.requireNonNull(boucleDeFin);
        }
    }

    private AbandonAutomatique() {
    }

    /**
     * @param aUnPassageSur indique si une Inscription a un Passage (de toute origine) sur une Boucle donnée
     * @param bouclesTerminees nombre de Boucles terminées de la Course, voir {@link Course#bouclesTerminees}
     */
    public static Decision decider(Collection<Inscription> inscriptions,
                                   BiPredicate<Inscription, Integer> aUnPassageSur, int bouclesTerminees) {
        List<Designation> abandons = new ArrayList<>();
        List<Inscription> enLice = inscriptions.stream().filter(Inscription::estEnCourse).toList();
        for (int boucle = 1; boucle <= bouclesTerminees; boucle++) {
            int numero = boucle;
            List<Inscription> ayantBoucle = enLice.stream().filter(i -> aUnPassageSur.test(i, numero)).toList();
            if (ayantBoucle.isEmpty()) {
                break;
            }
            enLice.stream().filter(i -> !ayantBoucle.contains(i))
                    .forEach(i -> abandons.add(new Designation(i, MotifAbandon.HORS_DELAI, numero)));
            if (ayantBoucle.size() == 1) {
                return new Decision(abandons, Optional.of(ayantBoucle.getFirst()), true,
                        OptionalInt.of(numero));
            }
            enLice = ayantBoucle;
        }
        return new Decision(abandons, Optional.empty(), false, OptionalInt.empty());
    }
}
