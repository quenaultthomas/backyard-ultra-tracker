package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.DureeAvanceInvalideException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

/**
 * Corps de POST /api/test/horloge/avance. {@code duree} est lue brute pour qu'un type incorrect (nombre, booléen,
 * objet) donne une erreur de validation sur le champ, comme une chaîne illisible, et non un corps illisible.
 */
public record AvanceHorlogeRequete(JsonNode duree) {

    /** Texte reçu, s'il s'agit d'une chaîne. */
    String texte() {
        return duree != null && duree.isString() ? duree.stringValue() : null;
    }

    /**
     * Durée ISO-8601 de type Duration (jours au plus : mois et années sont refusés), casse indifférente.
     * Les bornes sont vérifiées par le cas d'usage.
     *
     * @throws DureeAvanceInvalideException si la durée est absente, n'est pas une chaîne ou est illisible
     */
    Duration dureeLue() {
        String texte = texte();
        if (texte == null) {
            throw new DureeAvanceInvalideException();
        }
        try {
            return Duration.parse(texte);
        } catch (DateTimeParseException exception) {
            throw new DureeAvanceInvalideException();
        }
    }
}
