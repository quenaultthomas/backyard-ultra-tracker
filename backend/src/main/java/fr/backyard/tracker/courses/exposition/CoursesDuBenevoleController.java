package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCoursesDuBenevole;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courses du bénévole connecté. Réservé à BENEVOLE par la politique de sécurité (/api/benevole/**). L'identité vient
 * uniquement de la session (principal : identifiant du Compte), jamais d'un paramètre.
 */
@RestController
public class CoursesDuBenevoleController {

    private final ListerCoursesDuBenevole listerCoursesDuBenevole;

    public CoursesDuBenevoleController(ListerCoursesDuBenevole listerCoursesDuBenevole) {
        this.listerCoursesDuBenevole = listerCoursesDuBenevole;
    }

    @GetMapping("/api/benevole/courses")
    public List<CourseBenevoleReponse> listerMesCourses(@AuthenticationPrincipal UUID idBenevole) {
        return listerCoursesDuBenevole.executer(idBenevole).stream().map(CourseBenevoleReponse::depuis).toList();
    }
}
