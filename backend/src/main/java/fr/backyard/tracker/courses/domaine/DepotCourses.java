package fr.backyard.tracker.courses.domaine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port sortant de persistance des Courses. Aucun tri : l'ordre de la liste est une règle du domaine. */
public interface DepotCourses {

    /** Ajoute la Course, ou remplace celle de même identifiant. */
    void enregistrer(Course course);

    List<Course> toutes();

    Optional<Course> parId(UUID id);
}
