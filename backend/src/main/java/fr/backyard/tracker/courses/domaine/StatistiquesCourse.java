package fr.backyard.tracker.courses.domaine;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Statistiques d'une Course à un instant donné, calculées par {@link Course#statistiques} et jamais stockées. Les
 * cumuls sont ceux de la Course, c'est-à-dire de son coureur le plus avancé.
 *
 * @param bouclesTerminees Boucles terminées par la Course (jamais négatif)
 * @param distanceCumuleeMetres Boucles terminées x distance d'une Boucle
 * @param denivelePositifCumuleMetres Boucles terminées x dénivelé positif d'une Boucle
 * @param allureSecondesParKilometre allure moyenne des Passages scannés, vide sans Passage scanné retenu
 * @param dureeFinaleMillisecondes durée d'une Course TERMINEE (Boucles terminées x durée), vide sinon
 */
public record StatistiquesCourse(int bouclesTerminees, long distanceCumuleeMetres, long denivelePositifCumuleMetres,
                                 OptionalLong allureSecondesParKilometre, OptionalLong dureeFinaleMillisecondes) {

    public StatistiquesCourse {
        Objects.requireNonNull(allureSecondesParKilometre);
        Objects.requireNonNull(dureeFinaleMillisecondes);
    }

    /** Cumuls de distance et de dénivelé en entiers 64 bits, d'après les paramètres de Boucle. */
    static StatistiquesCourse cumulees(int bouclesTerminees, ParametresBoucle parametres, OptionalLong allure,
                                       OptionalLong dureeFinale) {
        return new StatistiquesCourse(bouclesTerminees, (long) bouclesTerminees * parametres.distanceMetres(),
                (long) bouclesTerminees * parametres.denivelePositifMetres(), allure, dureeFinale);
    }
}
