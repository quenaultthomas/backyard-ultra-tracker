package fr.backyard.tracker.comptes.domaine;

import java.util.UUID;

/**
 * Port sortant : retire un bénévole désactivé des Courses qui n'ont pas démarré, en conservant ses autres affectations.
 * Implémenté hors du contexte comptes, qui ne connaît pas les Courses ; s'exécute dans la transaction de l'appelant.
 */
public interface DesaffectationBenevole {

    /** @return le nombre de Courses dont le bénévole a été retiré (0 sans affectation) */
    int desaffecterDesCoursesEnPreparation(UUID benevoleId);
}
