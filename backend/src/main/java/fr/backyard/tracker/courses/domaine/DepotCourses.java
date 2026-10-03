package fr.backyard.tracker.courses.domaine;

import java.util.List;

/** Port sortant de persistance des Courses. Aucun tri : l'ordre de la liste est une règle du domaine. */
public interface DepotCourses {

    void enregistrer(Course course);

    List<Course> toutes();
}
