package fr.backyard.tracker.courses.application;

import java.util.List;

/**
 * Résultat du retrait d'un bénévole supprimé : nombre de Courses dont il a été retiré, ou Courses EN_COURS bloquantes
 * (non vide : refus, aucune Course modifiée, {@code retirees} nul).
 */
public record RetraitAffectations(int retirees, List<CourseBloquante> bloquantes) {

    public RetraitAffectations {
        bloquantes = List.copyOf(bloquantes);
    }

    public boolean estRefuse() {
        return !bloquantes.isEmpty();
    }
}
