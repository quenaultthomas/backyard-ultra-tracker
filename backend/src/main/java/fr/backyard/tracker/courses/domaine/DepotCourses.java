package fr.backyard.tracker.courses.domaine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port sortant de persistance des Courses, bénévoles affectés compris. Aucun tri : l'ordre de la liste est une
 * règle du domaine.
 */
public interface DepotCourses {

    /** Ajoute la Course, ou remplace celle de même identifiant (bénévoles affectés compris). */
    void enregistrer(Course course);

    List<Course> toutes();

    Optional<Course> parId(UUID id);

    /**
     * Course lue pour être modifiée : deux modifications de la même Course ne s'entrelacent pas (la seconde attend
     * la fin de la première, puis relit). Par défaut, simple lecture (dépôts sans concurrence).
     */
    default Optional<Course> parIdPourModification(UUID id) {
        return parId(id);
    }

    /**
     * Supprime définitivement la Course, son logo et ses affectations de bénévoles. Par défaut, non prise en charge
     * (dépôts qui ne suppriment rien).
     */
    default void supprimer(UUID id) {
        throw new UnsupportedOperationException("Suppression de Course non prise en charge par ce dépôt");
    }

    /** Courses auxquelles ce bénévole est affecté, dans un ordre quelconque. */
    default List<Course> parBenevole(UUID idBenevole) {
        return toutes().stream().filter(course -> course.benevolesAffectes().contains(idBenevole)).toList();
    }
}
