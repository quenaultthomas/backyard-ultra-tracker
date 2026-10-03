package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotLogos;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.LogoInvalideException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envoi ou remplacement du logo d'une Course par un admin (le rôle est contrôlé avant ce cas d'usage). L'état de la
 * Course prime sur la qualité du fichier ; la Course elle-même n'est pas modifiée.
 */
@Service
public class EnregistrerLogo {

    /** Course concernée et empreinte de son nouveau logo. */
    public record Resultat(Course course, String empreinte) {
    }

    private final DepotCourses depotCourses;
    private final DepotLogos depotLogos;

    public EnregistrerLogo(DepotCourses depotCourses, DepotLogos depotLogos) {
        this.depotCourses = depotCourses;
        this.depotLogos = depotLogos;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonModifiableException la Course n'est plus EN_PREPARATION
     * @throws LogoInvalideException octets absents, trop volumineux ou de format non reconnu ; l'ancien logo reste
     */
    @Transactional
    public Resultat executer(UUID idCourse, byte[] octets) {
        Course course = depotCourses.parIdPourModification(idCourse).orElseThrow(CourseIntrouvableException::new);
        course.autoriserModification();
        Logo logo = Logo.depuis(octets);
        depotLogos.enregistrer(idCourse, logo);
        return new Resultat(course, logo.empreinte());
    }
}
