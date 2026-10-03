package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.DeclarerCourse;
import java.time.LocalDate;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Corps de POST /api/administration/courses. Tout autre champ (id, statut, logo, bénévoles, démarréeLe…)
 * est ignoré. Les nombres sont lus sur 64 bits : une valeur hors bornes mais représentable atteint la
 * validation du domaine au lieu de rendre le corps illisible.
 */
public record DeclarerCourseRequete(
        String nom,
        @JsonDeserialize(using = DateCourseDeserializer.class) LocalDate date,
        Long distanceBoucleMetres,
        Long dureeBoucleMinutes,
        Long denivelePositifBoucleMetres,
        Long nombreMaxParticipants,
        Long nombreMaxBoucles) {

    /** Le contenu saisi n'apparaît jamais dans le journal, même au niveau DEBUG de Spring. */
    @Override
    public String toString() {
        return "DeclarerCourseRequete[contenu masqué]";
    }

    DeclarerCourse.Commande versCommande() {
        return new DeclarerCourse.Commande(nom, date, entier(distanceBoucleMetres), entier(dureeBoucleMinutes),
                entier(denivelePositifBoucleMetres), entier(nombreMaxParticipants), entier(nombreMaxBoucles));
    }

    /**
     * Conversion technique saturée vers int : une valeur au-delà de l'intervalle des int reste au-delà,
     * le domaine la refuse donc comme hors bornes sans que ses bornes soient dupliquées ici.
     */
    private static Integer entier(Long valeur) {
        return valeur == null ? null : Math.clamp(valeur, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }
}
