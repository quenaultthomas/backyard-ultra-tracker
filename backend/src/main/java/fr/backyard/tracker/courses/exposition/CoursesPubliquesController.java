package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCoursesPubliques;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liste publique des Courses pour l'accueil, ouverte à tous par la politique de sécurité (GET uniquement). Jamais
 * mise en cache, aucun paramètre lu, aucun journal.
 */
@RestController
public class CoursesPubliquesController {

    private static final String CHEMIN = "/api/courses";

    private final ListerCoursesPubliques listerCoursesPubliques;

    public CoursesPubliquesController(ListerCoursesPubliques listerCoursesPubliques) {
        this.listerCoursesPubliques = listerCoursesPubliques;
    }

    @GetMapping(CHEMIN)
    public ResponseEntity<CoursesPubliquesReponse> listerCourses() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CoursesPubliquesReponse.depuis(listerCoursesPubliques.executer()));
    }

    /** HEAD, implicitement servi sur un GET par Spring MVC, n'est pas exposé : jamais de 2xx hors GET. */
    @RequestMapping(path = CHEMIN, method = RequestMethod.HEAD)
    public void refuserHead() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(HttpMethod.HEAD.name(), List.of(HttpMethod.GET.name()));
    }
}
