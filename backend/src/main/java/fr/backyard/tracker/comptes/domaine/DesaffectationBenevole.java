package fr.backyard.tracker.comptes.domaine;

import java.util.UUID;

/**
 * Port sortant : retire un bénévole des Courses où il est affecté. Implémenté hors du contexte comptes, qui ne connaît
 * pas les Courses ; s'exécute dans la transaction de l'appelant.
 */
public interface DesaffectationBenevole {

    /**
     * Désactivation : retrait des seules Courses qui n'ont pas démarré, les autres affectations sont conservées.
     *
     * @return le nombre de Courses dont le bénévole a été retiré (0 sans affectation)
     */
    int desaffecterDesCoursesEnPreparation(UUID benevoleId);

    /**
     * Suppression : retrait de toutes les Courses (préparées et terminées), ou d'aucune si l'une est EN_COURS.
     *
     * @return le nombre de Courses dont le bénévole a été retiré (0 sans affectation)
     * @throws BenevoleAffecteCourseEnCoursException au moins une Course EN_COURS : aucune Course n'est modifiée
     */
    int desaffecterDeToutesLesCourses(UUID benevoleId);
}
