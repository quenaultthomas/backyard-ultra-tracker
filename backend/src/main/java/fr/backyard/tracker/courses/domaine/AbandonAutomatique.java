package fr.backyard.tracker.courses.domaine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BiPredicate;

/**
 * Règle de contrôle de fin de Boucle. Les Boucles terminées sont examinées dans l'ordre (k de 1 à N), parmi les
 * Inscriptions EN_COURSE restant en lice :
 * <ul>
 * <li>aucune en lice : aucune décision ;</li>
 * <li>personne n'a de Passage sur k : toutes les Inscriptions en lice sont vainqueurs (vainqueurs partagés) ;</li>
 * <li>une seule en a un, ou k est la dernière Boucle autorisée : les Inscriptions ayant un Passage sur k sont
 * vainqueurs, les autres en lice abandonnent hors délai sur k ;</li>
 * <li>sinon, les autres en lice abandonnent hors délai sur k et l'examen passe à k + 1.</li>
 * </ul>
 * Toute désignation de vainqueurs termine la Course : les Boucles suivantes ne sont pas examinées. Fonction pure :
 * rien n'est modifié. Unique définition de cette règle.
 */
public final class AbandonAutomatique {

    /** Inscription à mettre en abandon, avec son motif et la Boucle manquée. */
    public record Designation(Inscription inscription, MotifAbandon motif, int boucleAbandon) {
    }

    /**
     * Décision du contrôle : abandons à appliquer, vainqueurs éventuels et indicateur de fin de Course.
     *
     * @param vainqueurs Inscriptions à déclarer vainqueurs (vide, une ou plusieurs)
     * @param courseTerminee la Course est à terminer, vrai dès qu'il y a au moins un vainqueur
     * @param boucleDeFin Boucle décisive, vide si la Course n'est pas à terminer
     */
    public record Decision(List<Designation> abandons, List<Inscription> vainqueurs, boolean courseTerminee,
                           OptionalInt boucleDeFin) {

        public Decision {
            abandons = List.copyOf(abandons);
            vainqueurs = List.copyOf(vainqueurs);
            Objects.requireNonNull(boucleDeFin);
        }

        private static Decision sansFin(List<Designation> abandons) {
            return new Decision(abandons, List.of(), false, OptionalInt.empty());
        }

        private static Decision finDeCourse(List<Designation> abandons, List<Inscription> vainqueurs, int boucle) {
            return new Decision(abandons, vainqueurs, true, OptionalInt.of(boucle));
        }
    }

    private AbandonAutomatique() {
    }

    /**
     * @param aUnPassageSur indique si une Inscription a un Passage (de toute origine) sur une Boucle donnée
     * @param bouclesTerminees nombre de Boucles terminées de la Course, voir {@link Course#bouclesTerminees}
     * @param nombreMaxBoucles dernière Boucle autorisée de la Course
     */
    public static Decision decider(Collection<Inscription> inscriptions,
                                   BiPredicate<Inscription, Integer> aUnPassageSur, int bouclesTerminees,
                                   int nombreMaxBoucles) {
        List<Designation> abandons = new ArrayList<>();
        List<Inscription> enLice = inscriptions.stream().filter(Inscription::estEnCourse).toList();
        for (int boucle = 1; boucle <= bouclesTerminees && !enLice.isEmpty(); boucle++) {
            int numero = boucle;
            List<Inscription> ayantBoucle = enLice.stream().filter(i -> aUnPassageSur.test(i, numero)).toList();
            if (ayantBoucle.isEmpty()) {
                return Decision.finDeCourse(abandons, enLice, numero);
            }
            enLice.stream().filter(i -> !ayantBoucle.contains(i))
                    .forEach(i -> abandons.add(new Designation(i, MotifAbandon.HORS_DELAI, numero)));
            if (ayantBoucle.size() == 1 || numero >= nombreMaxBoucles) {
                return Decision.finDeCourse(abandons, ayantBoucle, numero);
            }
            enLice = ayantBoucle;
        }
        return Decision.sansFin(abandons);
    }
}
