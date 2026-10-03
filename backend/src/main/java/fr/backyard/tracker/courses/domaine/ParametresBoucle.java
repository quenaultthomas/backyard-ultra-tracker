package fr.backyard.tracker.courses.domaine;

/** Paramètres d'une Boucle, identiques pour toute la Course : distance (m), durée (min), dénivelé positif (m). */
public record ParametresBoucle(int distanceMetres, int dureeMinutes, int denivelePositifMetres) {
}
