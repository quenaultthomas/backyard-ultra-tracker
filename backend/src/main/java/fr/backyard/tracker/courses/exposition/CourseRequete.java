package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.DeclarerCourse;
import fr.backyard.tracker.courses.application.ModifierCourse;
import java.time.LocalDate;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Corps de POST /api/administration/courses (DeclarerCourseRequete du contrat) et de
 * PUT /api/administration/courses/{id} (ModifierCourseRequete, strictement le même schéma). Tout autre champ (id, statut, logo, bénévoles, démarréeLe…)
 * est ignoré. Les nombres sont lus sur 64 bits : une valeur hors bornes mais représentable atteint la
 * validation du domaine au lieu de rendre le corps illisible.
 */
public record CourseRequete(
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
        return "CourseRequete[contenu masqué]";
    }

    DeclarerCourse.Commande versCommandeDeDeclaration() {
        return new DeclarerCourse.Commande(nom, date, entier(distanceBoucleMetres), entier(dureeBoucleMinutes),
                entier(denivelePositifBoucleMetres), entier(nombreMaxParticipants), entier(nombreMaxBoucles));
    }

    /** @param id identifiant du chemin, qui fait foi (un éventuel id du corps est ignoré) */
    ModifierCourse.Commande versCommandeDeModification(UUID id) {
        return new ModifierCourse.Commande(id, nom, date, entier(distanceBoucleMetres), entier(dureeBoucleMinutes),
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
