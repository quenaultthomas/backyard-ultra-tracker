package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Déclaration d'une Course par un admin (le rôle de l'appelant est contrôlé avant ce cas d'usage). */
@Service
public class DeclarerCourse {

    /** Fuseau des Courses : la date du jour est celle de Paris, quel que soit le fuseau du serveur. */
    static final ZoneId FUSEAU_DES_COURSES = ZoneId.of("Europe/Paris");

    /** Saisie de déclaration, telle que reçue (champs éventuellement absents). */
    public record Commande(String nom, LocalDate date, Integer distanceBoucleMetres, Integer dureeBoucleMinutes,
                           Integer denivelePositifBoucleMetres, Integer nombreMaxParticipants,
                           Integer nombreMaxBoucles) {
    }

    private final DepotCourses depotCourses;
    private final Clock horloge;

    public DeclarerCourse(DepotCourses depotCourses, Clock horloge) {
        this.depotCourses = depotCourses;
        this.horloge = horloge;
    }

    /** @throws DonneesCourseInvalidesException toutes les violations de saisie ; rien n'est enregistré */
    @Transactional
    public Course executer(Commande commande) {
        Course course = Course.declarer(commande.nom(), commande.date(), commande.distanceBoucleMetres(),
                commande.dureeBoucleMinutes(), commande.denivelePositifBoucleMetres(),
                commande.nombreMaxParticipants(), commande.nombreMaxBoucles(), aujourdhui());
        depotCourses.enregistrer(course);
        return course;
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(FUSEAU_DES_COURSES));
    }
}
