package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Modification d'une Course par un admin (le rôle de l'appelant est contrôlé avant ce cas d'usage). */
@Service
public class ModifierCourse {

    /** Nouvelle saisie complète d'une Course existante, telle que reçue (champs éventuellement absents). */
    public record Commande(UUID id, String nom, LocalDate date, Integer distanceBoucleMetres,
                           Integer dureeBoucleMinutes, Integer denivelePositifBoucleMetres,
                           Integer nombreMaxParticipants, Integer nombreMaxBoucles) {
    }

    private final DepotCourses depotCourses;
    private final Clock horloge;

    public ModifierCourse(DepotCourses depotCourses, Clock horloge) {
        this.depotCourses = depotCourses;
        this.horloge = horloge;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonModifiableException la Course n'est plus EN_PREPARATION
     * @throws DonneesCourseInvalidesException toutes les violations de saisie ; rien n'est enregistré
     */
    @Transactional
    public Course executer(Commande commande) {
        Course course = depotCourses.parId(commande.id()).orElseThrow(CourseIntrouvableException::new);
        Course modifiee = course.modifier(commande.nom(), commande.date(), commande.distanceBoucleMetres(),
                commande.dureeBoucleMinutes(), commande.denivelePositifBoucleMetres(),
                commande.nombreMaxParticipants(), commande.nombreMaxBoucles(),
                CalendrierDesCourses.aujourdhui(horloge));
        depotCourses.enregistrer(modifiee);
        return modifiee;
    }
}
